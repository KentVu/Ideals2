package org.rri.ideals.server.completions

import com.intellij.codeInsight.completion.CompletionInitializationContext
import com.intellij.codeInsight.completion.CompletionInitializationUtil
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.OffsetTranslator
import com.intellij.codeInsight.completion.OffsetsInFile
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupArranger
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.client.ClientSessionsManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Pair
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageEditorUtil
import com.intellij.reference.SoftReference
import org.jetbrains.annotations.NonNls

class CompletionInfo(editor: Editor, project: Project) {
    val initContext: CompletionInitializationContext
    val parameters: CompletionParameters
    val arranger: LookupArrangerImpl
    val lookup: LookupImpl

    init {
        val process = VoidCompletionProcess()
        initContext = CompletionInitializationUtil.createCompletionInitializationContext(
            project,
            editor,
            editor.caretModel.primaryCaret,
            1,
            CompletionType.BASIC
        )
        checkNotNull(initContext)

        val topLevelOffsets =
            OffsetsInFile(initContext.getFile(), initContext.getOffsetMap()).toTopLevelFile()
        PsiDocumentManager.getInstance(initContext.getProject()).commitAllDocuments()
        val hostCopyOffsets =
            insertDummyIdentifier(initContext, process, topLevelOffsets)

        val finalOffsets =
            CompletionInitializationUtil.toInjectedIfAny(initContext.getFile(), hostCopyOffsets)
        parameters = CompletionInitializationUtil.createCompletionParameters(
            initContext,
            process,
            finalOffsets
        )
        arranger = LookupArrangerImpl(parameters)

        val session = ClientSessionsManager.getProjectSession(project)
        lookup = LookupImpl(session, editor, arranger)
    }

    class LookupArrangerImpl(private val parameters: CompletionParameters) :
        LookupArranger() {
        internal val elementsWithMatcher: ArrayList<LookupElementWithMatcher> = ArrayList()

        /* todo
    Add completion results sorting
   */
        fun addElement(completionItem: CompletionResult) {
            val presentation = LookupElementPresentation()
            ReadAction.run<RuntimeException> {
                completionItem.lookupElement.renderElement(
                    presentation
                )
            }
            registerMatcher(completionItem.lookupElement, completionItem.prefixMatcher)
            elementsWithMatcher.add(
                LookupElementWithMatcher(
                    completionItem.lookupElement,
                    completionItem.prefixMatcher
                )
            )
            super.addElement(completionItem.lookupElement, presentation)
        }

        override fun arrangeItems(
            lookup: Lookup,
            onExplicitAction: Boolean
        ): Pair<List<LookupElement>, Int> {
            val toSelect = 0
            return Pair(
                elementsWithMatcher.stream().map(LookupElementWithMatcher::lookupElement).toList(),
                toSelect
            )
        }

        override fun createEmptyCopy(): LookupArranger {
            return LookupArrangerImpl(parameters)
        }
    }

    /*
   This method is analogue for insertDummyIdentifier in CompletionInitializationUtil.java from idea 201.6668.113.
   There is CompletionProcessEx in ideas source code, that can't be reached publicly,
   but it uses only getHostOffsets and registerChildDisposable, that we can determine by ourselves.
   So solution is to copy that code with our replacement for getHostOffsets and registerChildDisposable calls.
   Other private methods from CompletionInitializationUtil are copied below too.
  */
    private fun insertDummyIdentifier(
        initContext: CompletionInitializationContext,
        indicator: VoidCompletionProcess,
        topLevelOffsets: OffsetsInFile
    ): OffsetsInFile {
        val hostEditor = InjectedLanguageEditorUtil.getTopLevelEditor(initContext.editor)
        val hostMap = topLevelOffsets.offsets
        val forbidCaching = false

        val hostCopy = obtainFileCopy(topLevelOffsets.file, forbidCaching)
        val copyDocument = hostCopy.viewProvider.document
        val dummyIdentifier = initContext.dummyIdentifier
        val startOffset = hostMap.getOffset(CompletionInitializationContext.START_OFFSET)
        val endOffset = hostMap.getOffset(CompletionInitializationContext.SELECTION_END_OFFSET)

        indicator.registerChildDisposable {
            OffsetTranslator(
                hostEditor.document,
                initContext.file,
                copyDocument,
                startOffset,
                endOffset,
                dummyIdentifier
            )
        }

        val copyOffsets = topLevelOffsets.replaceInCopy(
            hostCopy, startOffset, endOffset, dummyIdentifier
        ).get()
        check(hostCopy.isValid) { "PsiFile copy is not valid anymore" }
        return copyOffsets
    }

    companion object {
        private val LOG = Logger.getInstance(
            CompletionInfo::class.java
        )

        private val FILE_COPY_KEY =
            Key.create<SoftReference<Pair<PsiFile, Document>>>("CompletionFileCopy")

        private fun obtainFileCopy(
            file: PsiFile,
            forbidCaching: Boolean
        ): PsiFile {
            val virtualFile = file.virtualFile
            val mayCacheCopy =
                !forbidCaching && file.isPhysical &&  // Idea developer: "we don't want to cache code fragment copies even if they appear to be physical"
                        virtualFile != null && virtualFile.isInLocalFileSystem
            if (mayCacheCopy) {
                val cached = SoftReference.dereference(
                    file.getUserData(
                        FILE_COPY_KEY
                    )
                )
                if (cached != null && isCopyUpToDate(cached.second, cached.first, file)) {
                    val copy = cached.first
                    assertCorrectOriginalFile("Cached", file, copy)
                    return copy
                }
            }

            val copy = file.copy() as PsiFile
            if (copy.isPhysical || copy.viewProvider.isEventSystemEnabled) {
                LOG.error(
                    "File copy should be non-physical and non-event-system-enabled! Language=" +
                            file.language +
                            "; file=" +
                            file +
                            " of " +
                            file.javaClass
                )
            }
            assertCorrectOriginalFile("New", file, copy)

            if (mayCacheCopy) {
                val document = checkNotNull(copy.viewProvider.document)
                syncAcceptSlashR(file.viewProvider.document, document)
                file.putUserData(FILE_COPY_KEY, SoftReference(Pair.create(copy, document)))
            }
            return copy
        }

        private fun syncAcceptSlashR(originalDocument: Document?, documentCopy: Document) {
            if (originalDocument !is DocumentImpl || documentCopy !is DocumentImpl) {
                return
            }

            documentCopy.setAcceptSlashR(originalDocument.acceptsSlashR())
        }

        private fun isCopyUpToDate(
            document: Document,
            copyFile: PsiFile,
            originalFile: PsiFile
        ): Boolean {
            if ((copyFile.javaClass != originalFile.javaClass) || !copyFile.isValid || (copyFile.name != originalFile.name)) {
                return false
            }
            /*
     Idea developers:
     the psi file cache might have been cleared by some external activity,
     in which case PSI-document sync may stop working
     */
            val current = PsiDocumentManager.getInstance(copyFile.project).getPsiFile(document)
            return current != null && current.viewProvider.getPsi(copyFile.language) === copyFile
        }

        private fun fileInfo(file: PsiFile): @NonNls String {
            return file.toString() + " of " + file.javaClass +
                    " in " + file.viewProvider + ", languages=" + file.viewProvider.languages +
                    ", physical=" + file.isPhysical
        }

        // this assertion method is copied from package-private method in CompletionAssertions class
        private fun assertCorrectOriginalFile(
            prefix: @NonNls String?,
            file: PsiFile,
            copy: PsiFile
        ) {
            if (copy.originalFile !== file) {
                throw AssertionError(
                    """$prefix copied file doesn't have correct original: noOriginal=${copy.originalFile === copy}
 file ${fileInfo(file)}
 copy ${fileInfo(copy)}"""
                )
            }
        }
    }
}