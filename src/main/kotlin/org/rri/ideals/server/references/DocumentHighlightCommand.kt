package org.rri.ideals.server.references

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.featureStatistics.FeatureUsageTracker
import com.intellij.find.FindManager
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.find.impl.FindManagerImpl
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveResult
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetUtil
import com.intellij.util.containers.ContainerUtil
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Range
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.commands.LspCommand
import org.rri.ideals.server.util.EditorUtil.findTargetElement
import org.rri.ideals.server.util.MiscUtil.offsetToPosition
import java.util.Arrays
import java.util.Objects
import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream

class DocumentHighlightCommand : LspCommand<List<DocumentHighlight?>>() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "DocumentHighlight call" }

    override val isCancellable: Boolean
        get() = false

    override fun execute(ctx: ExecutorContext): List<DocumentHighlight> {
        return try {
            findHighlights(ctx.psiFile.project, ctx.editor, ctx.psiFile)
        } catch (e: IndexNotReadyException) {
            listOf()
        }
    }

    private fun findHighlights(
        project: Project,
        editor: Editor,
        file: PsiFile
    ): List<DocumentHighlight> {
        val handler = HighlightUsagesHandler.createCustomHandler<PsiElement>(editor, file)
        return if (handler != null) getHighlightsFromHandler(
            handler,
            editor
        ) else getHighlightsFromUsages(project, editor, file)
    }

    private fun getHighlightsFromHandler(
        handler: HighlightUsagesHandlerBase<PsiElement>,
        editor: Editor
    ): List<DocumentHighlight> {
        val featureId = handler.featureId

        if (featureId != null) {
            FeatureUsageTracker.getInstance().triggerFeatureUsed(featureId)
        }

        // NOTE: Not able to use handler.selectTargets()
        handler.computeUsages(handler.targets)

        val reads = textRangesToHighlights(handler.readUsages, editor, DocumentHighlightKind.Read)
        val writes =
            textRangesToHighlights(handler.writeUsages, editor, DocumentHighlightKind.Write)

        return Stream.concat(reads.stream(), writes.stream()).collect(Collectors.toList())
    }

    private fun textRangesToHighlights(
        usages: List<TextRange>,
        editor: Editor,
        kind: DocumentHighlightKind
    ): List<DocumentHighlight> {
        return usages.stream().map { textRange: TextRange ->
            DocumentHighlight(
                textRangeToRange(editor, textRange),
                kind
            )
        }
            .collect(Collectors.toList())
    }

    private fun getHighlightsFromUsages(
        project: Project,
        editor: Editor,
        file: PsiFile
    ): List<DocumentHighlight> {
        val ref = Ref<List<DocumentHighlight>>()
        DumbService.getInstance(project).withAlternativeResolveEnabled {
            val usageTargets = getUsageTargets(editor, file)
            if (usageTargets == null) {
                ref.set(listOf())
            } else {
                val result = Arrays.stream(usageTargets)
                    .map { usage: UsageTarget ->
                        extractDocumentHighlightFromRaw(
                            project,
                            file,
                            editor,
                            usage
                        )
                    }
                    .flatMap { obj: List<DocumentHighlight> -> obj.stream() }
                    .collect(
                        Collectors.toList()
                    )
                ref.set(result)
            }
        }
        return ref.get()
    }


    private fun getUsageTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        var usageTargets: Array<UsageTarget>? = UsageTargetUtil.findUsageTargets(editor, file)
        if (usageTargets.contentEquals(UsageTarget.EMPTY_ARRAY)) {
            usageTargets = getUsageTargetsFromNavItem(editor, file)
        }
        if (usageTargets == null) {
            usageTargets = getUsageTargetsFromPolyVariantReference(editor)
        }
        return usageTargets
    }

    private fun getUsageTargetsFromNavItem(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        var targetElement =
            findTargetElement(editor) ?: return null
        if (targetElement !== file) { // Compare references
            if (targetElement !is NavigationItem) {
                targetElement = targetElement.navigationElement
            }
            if (targetElement is NavigationItem) {
                return arrayOf(PsiElement2UsageTargetAdapter(targetElement, true))
            }
        }
        return null
    }

    private fun getUsageTargetsFromPolyVariantReference(editor: Editor): Array<UsageTarget>? {
        val ref = TargetElementUtil.findReference(editor)

        if (ref is PsiPolyVariantReference) {
            val results = ref.multiResolve(false)

            if (results.size > 0) {
                return ContainerUtil.mapNotNull(
                    results,
                    { result: ResolveResult ->
                        val element = result.element
                        if (element == null) null else PsiElement2UsageTargetAdapter(element, true)
                    }, UsageTarget.EMPTY_ARRAY
                )
            }
        }
        return null
    }

    private fun extractDocumentHighlightFromRaw(
        project: Project,
        file: PsiFile,
        editor: Editor,
        usage: UsageTarget
    ): List<DocumentHighlight> {
        if (usage is PsiElement2UsageTargetAdapter) {
            val target = usage.targetElement
                ?: return listOf()
            val refs = findRefsToElement(target, project, file)
            return refsToHighlights(target, file, editor, refs)
        } else {
            return listOf()
        }
    }

    private fun findRefsToElement(
        target: PsiElement,
        project: Project,
        file: PsiFile
    ): Collection<PsiReference> {
        val findUsagesManager =
            (FindManager.getInstance(project) as FindManagerImpl).findUsagesManager
        val handler = findUsagesManager.getFindUsagesHandler(target, true)

        // in case of injected file, use host file to highlight all occurrences of the target in each injected file
        val context = InjectedLanguageManager.getInstance(project).getTopLevelFile(file)

        val searchScope = LocalSearchScope(context)
        val result = handler?.findReferencesToHighlight(target, searchScope)
            ?: ReferencesSearch.search(target, searchScope, false).findAll()
        return result.stream().filter { o: PsiReference? -> Objects.nonNull(o) }.toList()
    }

    private fun refsToHighlights(
        element: PsiElement,
        file: PsiFile,
        editor: Editor,
        refs: Collection<PsiReference>
    ): List<DocumentHighlight> {
        val detector = ReadWriteAccessDetector.findDetector(element)
        val highlights = ArrayList<DocumentHighlight>()

        if (detector != null) {
            val readRefs = ArrayList<PsiReference>()
            val writeRefs = ArrayList<PsiReference>()
            for (ref in refs) {
                if (detector.getReferenceAccess(
                        element,
                        ref
                    ) == ReadWriteAccessDetector.Access.Read
                ) {
                    readRefs.add(ref)
                } else {
                    writeRefs.add(ref)
                }
            }
            addHighlights(highlights, readRefs, editor, DocumentHighlightKind.Read)
            addHighlights(highlights, writeRefs, editor, DocumentHighlightKind.Write)
        } else {
            addHighlights(highlights, refs, editor, DocumentHighlightKind.Text)
        }

        val range = HighlightUsagesHandler.getNameIdentifierRange(file, element)
        if (range != null) {
            val kind = if (detector != null && detector.isDeclarationWriteAccess(element))
                DocumentHighlightKind.Write
            else
                DocumentHighlightKind.Text
            highlights.add(DocumentHighlight(textRangeToRange(editor, range), kind))
        }
        return highlights
    }

    private fun addHighlights(
        highlights: MutableList<DocumentHighlight>,
        refs: Collection<PsiReference>,
        editor: Editor,
        kind: DocumentHighlightKind
    ) {
        val textRanges = ArrayList<TextRange>(refs.size)
        for (ref in refs) {
            HighlightUsagesHandler.collectRangesToHighlight(ref, textRanges)
        }
        val toAdd = textRanges.stream()
            .map { textRange: TextRange ->
                DocumentHighlight(
                    textRangeToRange(editor, textRange),
                    kind
                )
            }.toList()
        highlights.addAll(toAdd)
    }

    private fun textRangeToRange(editor: Editor, range: TextRange): Range {
        val doc = editor.document
        return Range(
            offsetToPosition(doc, range.startOffset),
            offsetToPosition(doc, range.endOffset)
        )
    }
}
