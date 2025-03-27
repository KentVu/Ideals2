package org.rri.ideals.server.completions

import com.google.gson.Gson
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.codeInsight.template.impl.Variable
import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.ui.DeferredIcon
import com.intellij.ui.IconManager
import com.intellij.ui.PlatformIcons
import com.intellij.util.SlowOperations
import io.github.furstenheim.CopyDown
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionItemLabelDetails
import org.eclipse.lsp4j.CompletionItemTag
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.InsertTextMode
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.completions.util.IconUtil.compareIcons
import org.rri.ideals.server.completions.util.TextEditRearranger.findOverlappingTextEditsInRangeFromMainTextEditToSnippetsAndMergeThem
import org.rri.ideals.server.completions.util.TextEditWithOffsets
import org.rri.ideals.server.util.EditorUtil.createEditor
import org.rri.ideals.server.util.LspProgressIndicator
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.MiscUtil.offsetToPosition
import org.rri.ideals.server.util.MiscUtil.positionToOffset
import org.rri.ideals.server.util.MiscUtil.with
import org.rri.ideals.server.util.MiscUtil.wrap
import org.rri.ideals.server.util.TextUtil.textEditFromDocs
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Function
import java.util.stream.Collectors

@Service(Service.Level.PROJECT)
class CompletionService(private val project: Project) : Disposable {
    private val cachedDataRef = AtomicReference(CompletionData.EMPTY_DATA)

    override fun dispose() {
    }

    fun computeCompletions(executorContext: ExecutorContext): List<CompletionItem> {
        LOG.info("start completion")
        val cancelChecker = checkNotNull(executorContext.cancelToken)
        try {
            return doComputeCompletions(
                executorContext.psiFile, executorContext.editor,
                cancelChecker
            )
        } finally {
            cancelChecker.checkCanceled()
        }
    }

    fun resolveCompletion(
        unresolved: CompletionItem,
        cancelChecker: CancelChecker
    ): CompletionItem {
        LOG.info("start completion resolve")
        val completionResolveData: CompletionItemData =
            Gson().fromJson<CompletionItemData>(
                unresolved.data.toString(),
                CompletionItemData::class.java
            )
        try {
            return doResolve(
                completionResolveData.completionDataVersion,
                completionResolveData.lookupElementIndex, unresolved, cancelChecker
            )
        } finally {
            cancelChecker.checkCanceled()
        }
    }


    private fun doResolve(
        completionDataVersion: Int, lookupElementIndex: Int,
        unresolved: CompletionItem, cancelChecker: CancelChecker
    ): CompletionItem {
        val copyThatCalledCompletionDocRef = Ref<Document>()
        val copyToInsertDocRef = Ref<Document>()
        val snippetBoundsRef = Ref<TextRange>()

        val diff: ArrayList<TextEdit>
        val snippetBounds: TextRange
        val copyToInsertDoc: Document
        val copyThatCalledCompletionDoc: Document
        val disposable = Disposer.newDisposable()
        val cachedData = cachedDataRef.get()
        try {
            if (completionDataVersion != cachedData.version) {
                return unresolved
            }

            prepareCompletionAndHandleInsert(
                cachedData,
                lookupElementIndex,
                cancelChecker,
                copyThatCalledCompletionDocRef,
                copyToInsertDocRef,
                snippetBoundsRef,
                disposable, unresolved
            )

            copyToInsertDoc = copyToInsertDocRef.get()
            copyThatCalledCompletionDoc = copyThatCalledCompletionDocRef.get()
            checkNotNull(copyToInsertDoc)
            checkNotNull(copyThatCalledCompletionDoc)

            snippetBounds = snippetBoundsRef.get()
            diff = ArrayList(textEditFromDocs(copyThatCalledCompletionDoc, copyToInsertDoc))

            if (diff.isEmpty()) {
                return unresolved
            }

            val unresolvedTextEdit = unresolved.textEdit.left

            val replaceElementStartOffset =
                positionToOffset(copyThatCalledCompletionDoc, unresolvedTextEdit.range.start)
            val replaceElementEndOffset =
                positionToOffset(copyThatCalledCompletionDoc, unresolvedTextEdit.range.end)

            val newTextAndAdditionalEdits =
                findOverlappingTextEditsInRangeFromMainTextEditToSnippetsAndMergeThem(
                    toListOfEditsWithOffsets(diff, copyThatCalledCompletionDoc),
                    replaceElementStartOffset, replaceElementEndOffset,
                    copyThatCalledCompletionDoc.text, snippetBounds
                )

            unresolved.additionalTextEdits = toListOfTextEdits(
                newTextAndAdditionalEdits.additionalEdits,
                copyThatCalledCompletionDoc
            )

            unresolvedTextEdit.newText = newTextAndAdditionalEdits.mainEdit.newText
            return unresolved
        } finally {
            WriteCommandAction.runWriteCommandAction(
                project
            ) { Disposer.dispose(disposable) }
        }
    }

    private fun doComputeCompletions(
        psiFile: PsiFile,
        editor: Editor,
        cancelChecker: CancelChecker
    ): List<CompletionItem> {
        val process: VoidCompletionProcess = VoidCompletionProcess(); val resultRef = Ref<List<CompletionItem>>()
        try {
            // need for icon load
            Registry.get("psi.deferIconLoading").setValue(false, process)
            val lookupElementsWithMatcherRef: Ref<List<LookupElementWithMatcher>> =
                Ref<List<LookupElementWithMatcher>>()
            val completionDataVersionRef = Ref<Int>()
            // invokeAndWait is necessary for editor creation and completion call
            ProgressManager.getInstance().runProcess({
                ApplicationManager.getApplication().invokeAndWait {
                    val compInfo: CompletionInfo = CompletionInfo(editor, project)
                    val ideaCompService =
                        checkNotNull(com.intellij.codeInsight.completion.CompletionService.getCompletionService())
                    ideaCompService.performCompletion(
                        compInfo.parameters
                    ) { result: CompletionResult ->
                        compInfo.lookup.addItem(result.lookupElement, result.prefixMatcher)
                        compInfo.arranger.addElement(result)
                    }

                    val elementsWithMatcher = compInfo.arranger.elementsWithMatcher
                    lookupElementsWithMatcherRef.set(elementsWithMatcher)

                    // version and data manipulations here are thread safe because they are done inside invokeAndWait
                    val newVersion = 1 + cachedDataRef.get().version
                    completionDataVersionRef.set(newVersion)
                    cachedDataRef.set(
                        CompletionData(
                            elementsWithMatcher,
                            newVersion,
                            offsetToPosition(
                                editor.document,
                                editor.caretModel.offset
                            ),
                            editor.document.text,
                            psiFile.language
                        )
                    )
                }
            }, LspProgressIndicator(cancelChecker))
            ReadAction.run<RuntimeException> {
                var version = completionDataVersionRef.get()
                if (version == null) {
                    version = cachedDataRef.get().version + 1
                }
                val lookupElements: List<LookupElementWithMatcher>? =
                    lookupElementsWithMatcherRef.get()
                if (lookupElements == null) {
                    resultRef.set(listOf())
                    return@run
                }
                resultRef.set(
                    convertLookupElementsWithMatcherToCompletionItems(
                        lookupElements,
                        editor.document,
                        offsetToPosition(
                            editor.document,
                            editor.caretModel.offset
                        ),
                        version
                    )
                )
            }
        } finally {
            WriteCommandAction.runWriteCommandAction(
                project
            ) { Disposer.dispose(process) }
        }
        return resultRef.get()
    }

    private fun convertLookupElementsWithMatcherToCompletionItems(
        lookupElementsWithMatchers: List<LookupElementWithMatcher>,
        document: Document,
        position: Position,
        completionDataVersion: Int
    ): List<CompletionItem> {
        val result = ArrayList<CompletionItem>()
        val currentCaretOffset = positionToOffset(document, position)
        for (i in lookupElementsWithMatchers.indices) {
            val lookupElementWithMatcher: LookupElementWithMatcher = lookupElementsWithMatchers[i]
            val item =
                createLspCompletionItem(
                    lookupElementWithMatcher.lookupElement,
                    with<Range>(
                        Range()
                    ) { range: Range ->
                        range.start = offsetToPosition(
                            document,
                            currentCaretOffset - lookupElementWithMatcher.prefixMatcher
                                .getPrefix()
                                .length
                        )
                        range.end = position
                    })
            item.data = CompletionItemData(completionDataVersion, i)
            result.add(item)
        }
        return result
    }

    private fun prepareCompletionAndHandleInsert(
        cachedData: CompletionData,
        lookupElementIndex: Int,
        cancelChecker: CancelChecker,
        copyThatCalledCompletionDocRef: Ref<Document>,
        copyToInsertDocRef: Ref<Document>,
        snippetBoundsRef: Ref<TextRange>,
        disposable: Disposable,
        unresolved: CompletionItem
    ) {
        val cachedLookupElementWithMatcher: LookupElementWithMatcher =
            cachedData.lookupElementsWithMatcher[lookupElementIndex]
        val copyToInsertRef = Ref<PsiFile>()
        ApplicationManager.getApplication().runReadAction {
            copyToInsertRef.set(
                PsiFileFactory.getInstance(project).createFileFromText(
                    "copy",
                    cachedData.language,
                    cachedData.fileText,
                    true,
                    true,
                    true
                )
            )
            val copyThatCalledCompletion = copyToInsertRef.get().copy() as PsiFile

            copyThatCalledCompletionDocRef.set(
                getDocument(
                    copyThatCalledCompletion
                )
            )
            copyToInsertDocRef.set(getDocument(copyToInsertRef.get()))
        }

        val copyToInsert = copyToInsertRef.get()

        ProgressManager.getInstance().runProcess({
            ApplicationManager.getApplication().invokeAndWait {
                val editor = createEditor(
                    disposable, copyToInsert,
                    cachedData.position
                )
                val completionInfo: CompletionInfo = CompletionInfo(editor, project)

                val targets: List<DocumentationTarget?> =
                    IdeDocumentationTargetProvider.getInstance(project).documentationTargets(
                        editor,
                        copyToInsert, cachedLookupElementWithMatcher.lookupElement
                    )
                if (!targets.isEmpty()) {
                    unresolved.documentation =
                        toLspDocumentation(
                            targets[0]!!
                        )
                }

                handleInsert(
                    cachedData,
                    cachedLookupElementWithMatcher,
                    editor,
                    copyToInsert,
                    completionInfo
                )
                val caretOffset = editor.caretModel.offset
                snippetBoundsRef.set(TextRange(caretOffset, caretOffset))

                val templateState = TemplateManagerImpl.getTemplateState(editor)
                val document = editor.document
                if (templateState != null) {
                    handleSnippetsInsert(snippetBoundsRef, copyToInsert, templateState, document)
                } else {
                    WriteCommandAction.runWriteCommandAction(
                        project, null, null,
                        { document.insertString(caretOffset, "$0") }, copyToInsert
                    )
                }
            }
        }, LspProgressIndicator(cancelChecker))
    }

    private fun handleSnippetsInsert(
        snippetBoundsRef: Ref<TextRange>,
        copyToInsert: PsiFile,
        templateState: TemplateState,
        document: Document
    ) {
        val template = templateState.template

        while (!templateState.isLastVariable) {
            val lookup =
                LookupManager.getActiveLookup(templateState.editor) as LookupImpl?
            if (lookup != null) {
                // IDEA still use this deprecated method in completion selectItem
                SlowOperations.allowSlowOperations<RuntimeException> {
                    lookup.finishLookup(
                        '\t'
                    )
                }
            } else {
                WriteCommandAction.runWriteCommandAction(
                    project, null, null,
                    { templateState.nextTab() })
            }
        }

        val variableToSegments =
            template.variables
                .stream()
                .collect(
                    Collectors.toMap(
                        Variable::getName,
                        { variable: Variable? -> ArrayList<TextEditWithOffsets>() })
                )
        variableToSegments["END"] = ArrayList()
        val variableToNumber = HashMap<String, Int>()
        for (i in 0..<template.variableCount) {
            variableToNumber[template.getVariableNameAt(i)] = i + 1
        }
        variableToNumber["END"] = 0
        for (i in 0..<template.segmentsCount) {
            val segmentRange = templateState.getSegmentRange(i)
            val segmentOffsetStart = segmentRange.startOffset
            val segmentOffsetEnd = segmentRange.endOffset
            val segmentName = template.getSegmentName(i)
            variableToSegments[segmentName]!!.add(
                TextEditWithOffsets(
                    TextRange(
                        segmentOffsetStart,
                        segmentOffsetEnd
                    ),
                    "\${" + variableToNumber[segmentName].toString() + ":" + document.text.substring(
                        segmentOffsetStart,
                        segmentOffsetEnd
                    ) + "}"
                )
            )
        }
        val sortedLspSegments =
            variableToSegments.values.stream()
                .flatMap { obj: ArrayList<TextEditWithOffsets> -> obj.stream() }.sorted().toList()
        WriteCommandAction.runWriteCommandAction(
            project, null, null,
            { templateState.gotoEnd(false) }
        )

        WriteCommandAction.runWriteCommandAction(project, null, null, {
            for (i in sortedLspSegments.indices.reversed()) {
                val lspSegment = sortedLspSegments[i]
                if (lspSegment.range.getStartOffset() == lspSegment.range
                        .getEndOffset()
                ) {
                    document.insertString(
                        lspSegment.range.getStartOffset(),
                        lspSegment.newText
                    )
                } else {
                    document.replaceString(
                        lspSegment.range.getStartOffset(),
                        lspSegment.range.getEndOffset(), lspSegment.newText
                    )
                }
            }
        }, copyToInsert)
        snippetBoundsRef.set(
            TextRange(
                sortedLspSegments[0].range.getStartOffset(),
                sortedLspSegments[sortedLspSegments.size - 1].range.getEndOffset()
            )
        )
    }

    private class CompletionData(
        lookupElementsWithMatcher: List<LookupElementWithMatcher>,
        val version: Int,
        val position: Position,
        val fileText: String,  // file text at the moment of the completion invocation
        val language: Language
    ) {
        val lookupElementsWithMatcher: List<LookupElementWithMatcher> =
            lookupElementsWithMatcher

        companion object {
            val EMPTY_DATA: CompletionData = CompletionData(
                listOf<LookupElementWithMatcher>(), 0, Position(), "", Language.ANY
            )
        }
    }

    private fun handleInsert(
        cachedData: CompletionData,
        cachedLookupElementWithMatcher: LookupElementWithMatcher,
        editor: Editor,
        copyToInsert: PsiFile,
        completionInfo: CompletionInfo
    ) {
        prepareCompletionInfoForInsert(completionInfo, cachedLookupElementWithMatcher)

        completionInfo.lookup
            .finishLookup('\n', cachedLookupElementWithMatcher.lookupElement)

        val currentOffset = editor.caretModel.offset

        WriteCommandAction.runWriteCommandAction(
            project
        ) {
            val context =
                CompletionUtil.createInsertionContext(
                    cachedData.lookupElementsWithMatcher.stream()
                        .map(LookupElementWithMatcher::lookupElement).toList(),
                    cachedLookupElementWithMatcher.lookupElement,
                    '\n',
                    editor,
                    copyToInsert,
                    currentOffset,
                    CompletionUtil.calcIdEndOffset(
                        completionInfo.initContext.getOffsetMap(),
                        editor,
                        currentOffset
                    ),
                    completionInfo.initContext.getOffsetMap()
                )
            cachedLookupElementWithMatcher.lookupElement.handleInsert(context)
        }
    }

    private fun prepareCompletionInfoForInsert(
        completionInfo: CompletionInfo,
        lookupElementWithMatcher: LookupElementWithMatcher
    ) {
        val prefixMatcher = lookupElementWithMatcher.prefixMatcher

        completionInfo.lookup.addItem(lookupElementWithMatcher.lookupElement, prefixMatcher)

        completionInfo.arranger
            .registerMatcher(lookupElementWithMatcher.lookupElement, prefixMatcher)
        completionInfo.arranger.addElement(
            lookupElementWithMatcher.lookupElement,
            LookupElementPresentation()
        )
    }

    companion object {
        private val LOG = Logger.getInstance(
            CompletionService::class.java
        )

        private fun toListOfEditsWithOffsets(
            list: ArrayList<TextEdit>,
            document: Document
        ): List<TextEditWithOffsets> {
            return list.stream().map { textEdit: TextEdit? ->
                TextEditWithOffsets(
                    textEdit!!, document
                )
            }.toList()
        }

        private fun toListOfTextEdits(
            additionalEdits: List<TextEditWithOffsets>,
            document: Document
        ): List<TextEdit> {
            return additionalEdits.stream()
                .map { editWithOffsets: TextEditWithOffsets -> editWithOffsets.toTextEdit(document) }
                .toList()
        }

        private fun createLspCompletionItem(
            lookupElement: LookupElement,
            textEditRange: Range
        ): CompletionItem {
            val resItem = CompletionItem()
            val d = Disposer.newDisposable()
            try {
                val presentation = LookupElementPresentation.renderElement(lookupElement)

                val contextInfo = StringBuilder()
                for (textFragment in presentation.tailFragments) {
                    contextInfo.append(textFragment.text)
                }

                val lDetails = CompletionItemLabelDetails()
                lDetails.detail = contextInfo.toString()

                val tagList = ArrayList<CompletionItemTag>()
                if (presentation.isStrikeout) {
                    tagList.add(CompletionItemTag.Deprecated)
                }
                resItem.insertTextFormat = InsertTextFormat.Snippet
                resItem.label = presentation.itemText
                resItem.labelDetails = lDetails
                resItem.insertTextMode = InsertTextMode.AsIs
                resItem.filterText = lookupElement.lookupString
                resItem.textEdit =
                    Either.forLeft(
                        TextEdit(
                            textEditRange,
                            lookupElement.lookupString
                        )
                    )

                resItem.detail = presentation.typeText
                resItem.tags = tagList

                var icon = presentation.icon
                if (icon is DeferredIcon) {
                    icon = icon.baseIcon
                }
                if (icon == null) {
                    resItem.kind = CompletionItemKind.Keyword
                    return resItem
                }
                var kind: CompletionItemKind? = null
                val iconManager: IconManager = IconManager.getInstance()
                if (compareIcons(icon, AllIcons.Nodes.Method, PlatformIcons.Method) ||
                    compareIcons(icon, AllIcons.Nodes.AbstractMethod, PlatformIcons.AbstractMethod)
                ) {
                    kind = CompletionItemKind.Method
                } else if (compareIcons(icon, AllIcons.Nodes.Module, "nodes/Module.svg")
                    || compareIcons(icon, AllIcons.Nodes.IdeaModule, PlatformIcons.IdeaModule)
                    || compareIcons(icon, AllIcons.Nodes.JavaModule, PlatformIcons.JavaModule)
                    || compareIcons(icon, AllIcons.Nodes.ModuleGroup, "nodes/moduleGroup.svg")
                ) {
                    kind = CompletionItemKind.Module
                } else if (compareIcons(icon, AllIcons.Nodes.Function, PlatformIcons.Function)) {
                    kind = CompletionItemKind.Function
                } else if (compareIcons(icon, AllIcons.Nodes.Interface, PlatformIcons.Interface) ||
                    compareIcons(
                        icon,
                        iconManager.tooltipOnlyIfComposite(AllIcons.Nodes.Interface),
                        PlatformIcons.Interface
                    )
                ) {
                    kind = CompletionItemKind.Interface
                } else if (compareIcons(icon, AllIcons.Nodes.Folder, PlatformIcons.Folder)) {
                    kind = CompletionItemKind.Folder
                } else if (compareIcons(
                        icon,
                        AllIcons.Nodes.MethodReference,
                        PlatformIcons.MethodReference
                    )
                ) {
                    kind = CompletionItemKind.Reference
                } else if (compareIcons(icon, AllIcons.Nodes.TextArea, "nodes/textArea.svg")) {
                    kind = CompletionItemKind.Text
                } else if (compareIcons(icon, AllIcons.Nodes.Type, "nodes/type.svg")) {
                    kind = CompletionItemKind.TypeParameter
                } else if (compareIcons(icon, AllIcons.Nodes.Property, PlatformIcons.Property)) {
                    kind = CompletionItemKind.Property
                } else if (compareIcons(
                        icon,
                        AllIcons.FileTypes.Any_type,
                        "fileTypes/anyType.svg"
                    ) /* todo can we find that?*/) {
                    kind = CompletionItemKind.File
                } else if (compareIcons(icon, AllIcons.Nodes.Enum, PlatformIcons.Enum)) {
                    kind = CompletionItemKind.Enum
                } else if (compareIcons(icon, AllIcons.Nodes.Variable, PlatformIcons.Variable) ||
                    compareIcons(icon, AllIcons.Nodes.Parameter, PlatformIcons.Parameter) ||
                    compareIcons(icon, AllIcons.Nodes.NewParameter, "nodes/newParameter.svg")
                ) {
                    kind = CompletionItemKind.Variable
                } else if (compareIcons(icon, AllIcons.Nodes.Constant, "nodes/constant.svg")) {
                    kind = CompletionItemKind.Constant
                } else if (compareIcons(icon, AllIcons.Nodes.Class, PlatformIcons.Class) ||
                    compareIcons(
                        icon,
                        iconManager.tooltipOnlyIfComposite(AllIcons.Nodes.Class),
                        PlatformIcons.Class
                    ) ||
                    compareIcons(icon, AllIcons.Nodes.Class, PlatformIcons.Class) ||
                    compareIcons(icon, AllIcons.Nodes.AbstractClass, PlatformIcons.AbstractClass)
                ) {
                    kind = CompletionItemKind.Class
                } else if (compareIcons(icon, AllIcons.Nodes.Field, PlatformIcons.Field)) {
                    kind = CompletionItemKind.Field
                } else if (compareIcons(icon, AllIcons.Nodes.Template, "nodes/template.svg")) {
                    kind = CompletionItemKind.Snippet
                }
                resItem.kind = kind

                return resItem
            } catch (e: Throwable) {
                throw wrap(e)
            } finally {
                Disposer.dispose(d)
            }
        }


        private fun toLspDocumentation(target: DocumentationTarget): Either<String, MarkupContent?> {
            try {
                val future =
                    ApplicationManager.getApplication().executeOnPooledThread<MarkupContent> {
                        val res = computeDocumentationBlocking(target.createPointer())
                            ?: return@executeOnPooledThread null
                        val html = res.html
                        val htmlToMarkdownConverter = CopyDown()
                        val ans = htmlToMarkdownConverter.convert(html)
                        MarkupContent(MarkupKind.MARKDOWN, ans)
                    }
                val result = future.get()
                return if (result != null) Either.forRight(result) else Either.forRight(null)
            } catch (e: Exception) {
                LOG.error("Failed to compute documentation", e)
                return Either.forRight(null)
            }
        }
    }
}
