package org.rri.ideals.server.references

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.fileEditor.impl.EditorCompositeModel
import com.intellij.openapi.fileEditor.impl.EditorFileSwapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asFlow
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.rri.ideals.server.LspPath
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.commands.LspCommand
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.MiscUtil.getPsiElementRange
import org.rri.ideals.server.util.MiscUtil.offsetToPosition
import org.rri.ideals.server.util.MiscUtil.positionToOffset
import org.rri.ideals.server.util.MiscUtil.psiElementToLocation
import org.rri.ideals.server.util.MiscUtil.psiElementToLocationLink
import org.rri.ideals.server.util.MiscUtil.resolvePsiFile
import java.util.Objects
import java.util.stream.Collectors
import java.util.stream.Stream
import kotlin.coroutines.CoroutineContext

abstract class FindDefinitionCommandBase :
    LspCommand<Either<List<Location?>, List<LocationLink?>>>() {
    override val isCancellable: Boolean
        get() = false

    override fun execute(ctx: ExecutorContext): Either<List<Location?>, List<LocationLink?>> {
        val editor = ctx.editor
        val file = ctx.psiFile
        val doc = editor.document
        val offset = editor.caretModel.offset

        val originalElem = file.findElementAt(offset)
        val originalRange = getPsiElementRange(doc, originalElem)

        val definitions = findDefinitions(editor, offset)
            .filter { o: PsiElement? -> Objects.nonNull(o) }
            .map { targetElem: PsiElement ->
                if (targetElem.containingFile == null) {
                    return@map null
                }
                val loc = findSourceLocation(file.project, targetElem)
                if (loc != null) {
                    return@map LocationLink(loc.uri, loc.range, loc.range, originalRange)
                } else {
                    val targetDoc = if (targetElem.containingFile == file)
                        doc
                    else
                        getDocument(targetElem.containingFile)!!
                    return@map psiElementToLocationLink(targetElem, targetDoc, originalRange)
                }
            }
            .filter { o: LocationLink? -> Objects.nonNull(o) }
            .collect(Collectors.toList())

        return Either.forRight(definitions)
    }

    protected abstract fun findDefinitions(editor: Editor, offset: Int): Stream<PsiElement>

    companion object {
        private val EDITOR_FILE_SWAPPER_EP_NAME =
            ExtensionPointName<EditorFileSwapper>("com.intellij.editorFileSwapper")

        /**
         * Tries to find the corresponding source file location for this element.
         *
         *
         * Depends on the element contained in a library's class file and the corresponding sources jar/zip attached
         * to the library.
         */
        private fun findSourceLocation(project: Project, element: PsiElement): Location? {
            val file = element.containingFile.originalFile
            val doc = getDocument(file) ?: return null

            val location = psiElementToLocation(element, file)
                ?: return null
            val disposable = Disposer.newDisposable()
            try {
                val editor = newEditorComposite(
                    project,
                    file.virtualFile
                )
                    ?: return null
                Disposer.register(disposable, editor)

                val psiAwareEditor = EditorFileSwapper.findSinglePsiAwareEditor(editor.allEditors)
                    ?: return location
                psiAwareEditor.editor.caretModel.moveToOffset(
                    positionToOffset(
                        doc,
                        location.range.start
                    )
                )

                val newFilePair = EDITOR_FILE_SWAPPER_EP_NAME.extensionList.stream()
                    .map { fileSwapper: EditorFileSwapper ->
                        fileSwapper.getFileToSwapTo(
                            project,
                            editor
                        )
                    }
                    .filter { o: Pair<VirtualFile?, Int?>? -> Objects.nonNull(o) }
                    .findFirst()

                if (newFilePair.isEmpty || newFilePair.get().first == null) {
                    return location
                }

                val sourcePsiFile = resolvePsiFile(
                    project,
                    LspPath.fromVirtualFile(newFilePair.get().first!!)
                )
                if (sourcePsiFile == null) {
                    return location
                }
                val sourceDoc = getDocument(sourcePsiFile)
                    ?: return location
                val virtualFile = newFilePair.get().first
                val offset = if (newFilePair.get().first != null) newFilePair.get().second else 0
                checkNotNull(virtualFile)
                checkNotNull(offset)
                return Location(
                    LspPath.fromVirtualFile(virtualFile).toLspUri(),
                    Range(offsetToPosition(sourceDoc, offset), offsetToPosition(sourceDoc, offset))
                )
            } finally {
                Disposer.dispose(disposable)
            }
        }

        private fun newEditorComposite(project: Project, file: VirtualFile?): EditorComposite? {
            if (file == null) {
                return null
            }
            val providers = FileEditorProviderManager.getInstance().getProviderList(project, file)
            if (providers.isEmpty()) {
                return null
            }
            val editorsWithProviders = providers.stream().map { provider: FileEditorProvider ->
                checkNotNull(provider)
                assert(provider.accept(project, file))
                val editor = provider.createEditor(project, file)
                assert(editor.isValid)
                FileEditorWithProvider(editor, provider)
            }.toList()
            return KotlinHelper.getEditorComposite(
                file,
                listOf<EditorCompositeModel>(EditorCompositeModel(editorsWithProviders)).asFlow<EditorCompositeModel>(),
                project,
                object : CoroutineScope {
                    override val coroutineContext: CoroutineContext =
                        Dispatchers.Default.plus(Job(null))
                }
            )
        }
    }
}
