package org.rri.ideals.server

import com.intellij.codeInsight.daemon.impl.EditorTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.rri.ideals.server.util.MiscUtil.asWriteAction
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.MiscUtil.invokeWithPsiFileInReadAction
import org.rri.ideals.server.util.MiscUtil.resolvePsiFile
import org.rri.ideals.server.util.TextUtil.toTextRange
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

@Service(Service.Level.PROJECT)
class ManagedDocuments(private val project: Project) {
    private val docs = ConcurrentHashMap<LspPath, VersionedTextDocumentIdentifier?>()

    fun startManaging(textDocument: TextDocumentItem) {
        val uri = textDocument.uri

        if (!canAccept(uri)) {
            return
        }

        val path = LspPath.fromLspUri(uri)

        if (docs.containsKey(path)) {
            LOG.warn("URI was opened again without being closed, resetting: $path")
            docs.remove(path)
        }
        LOG.debug("Handling textDocument/didOpen for: $path")

        // forcibly refresh file system to handle newly created files
        val virtualFile = path.refreshAndFindVirtualFile()
        if (virtualFile == null) {
            LOG.warn("Couldn't find virtual file: $path")
            return
        }

        ApplicationManager.getApplication().invokeAndWait(asWriteAction {
            val editor = getSelectedEditor(virtualFile)
            val doc = Optional.ofNullable(editor)
                .map { obj: Editor -> obj.document }
                .orElse(null)

            if (doc == null) return@asWriteAction  // todo handle


            if (doc.isWritable) {
                // set IDEA's copy of the document to have the text with potential unsaved in-memory changes from the client
                doc.setText(normalizeText(textDocument.text))
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }

            // In a unit test, active editors are not updated automatically
            if (ApplicationManager.getApplication().isUnitTestMode) {
                val editors = ArrayList(
                    EditorTracker.getInstance(project).activeEditors
                )
                editors.add(editor)
                EditorTracker.getInstance(project).activeEditors = editors
            }
        })

        val docVersion = Optional.of(textDocument.version)
            .filter { version: Int? -> version != 0 }
            .orElse(null)
        docs[path] = VersionedTextDocumentIdentifier(uri, docVersion)
    }


    fun updateDocument(params: DidChangeTextDocumentParams) {
        val textDocument = params.textDocument
        val contentChanges = params.contentChanges

        val uri = textDocument.uri
        if (!canAccept(uri)) return

        val path = LspPath.fromLspUri(uri)

        val managedTextDocId = docs.get(path)
        requireNotNull(managedTextDocId) { "document isn't being managed: $uri" }

        // Version number of our document should be (theirs - number of content changes)
        // If stored version is null, this means the document has been just saved or opened
        if (managedTextDocId.version != null && managedTextDocId.version != (textDocument.version - contentChanges.size)) {
            LOG.warn(
                String.format(
                    "Version mismatch on document change - " +
                            "ours: %d, theirs: %d", managedTextDocId.version, textDocument.version
                )
            )
            return
        }

        val file = resolvePsiFile(project, path)

        if (file == null) {
            LOG.warn("Couldn't resolve PSI file at: $path")
            return
        }

        // all updates must go through CommandProcessor
        ApplicationManager.getApplication().invokeAndWait {
            CommandProcessor.getInstance().executeCommand(
                project, asWriteAction {
                    val doc =
                        getDocument(file)
                    if (doc == null) {
                        LOG.warn("Attempted to get Document for updating but it was null: $path")
                        return@asWriteAction
                    }

                    /*  todo make it configurable
              if(managedTextDoc.contents != doc.text) {
                val change = Diff.buildChanges(managedTextDoc.contents, doc.text)
                LOG.error("Ground truth differed upon change! Old: \n${managedTextDoc.contents}\nNew: \n${doc.text}")
                return@Runnable
              }
              LOG.debug("Doc before:\n\n${doc.text}\n\n")
    */
                    if (!doc.isWritable) {
                        LOG.warn("Document isn't writable: $path")
                        return@asWriteAction
                    }

                    try {
                        applyContentChangeEventChanges(doc, contentChanges)
                    } catch (e: Exception) {
                        LOG.error("Error on documentChange", e)
                    }

                    // Commit changes to the PSI tree, but not to disk
                    PsiDocumentManager.getInstance(project).commitDocument(doc)

                    // Update the ground truth
                    docs[path] = textDocument
                }, "LSP: UpdateDocument", "", UndoConfirmationPolicy.REQUEST_CONFIRMATION
            )
        }
    }

    fun syncDocument(textDocument: TextDocumentIdentifier) {
        val uri = textDocument.uri

        if (!canAccept(uri)) return

        val path = LspPath.fromLspUri(uri)

        if (!docs.containsKey(path)) {
            LOG.warn("Tried handling didSave, but the document isn't being managed: $path")
            return
        }

        ApplicationManager.getApplication().invokeAndWait(
            asWriteAction {
                invokeWithPsiFileInReadAction(
                    project, path,
                    { psi ->
                        val doc = getDocument(psi)
                        if (doc == null)
                            return@invokeWithPsiFileInReadAction  // todo handle

                        VirtualFileManager.getInstance().refreshWithoutFileWatcher(false)
                        FileDocumentManager.getInstance().reloadFromDisk(doc)
                        PsiDocumentManager.getInstance(project).commitAllDocuments()
                    })
            })

        // drop stored version to bring it in sync with the client (if there was any mismatch)
        docs[path] = VersionedTextDocumentIdentifier(uri, null)
    }

    fun stopManaging(textDocument: TextDocumentIdentifier) {
        val uri = textDocument.uri
        if (!canAccept(uri)) return

        val path = LspPath.fromLspUri(uri)

        val virtualFile = path.findVirtualFile()
        if (virtualFile != null) {
            ApplicationManager.getApplication().invokeAndWait {
                FileEditorManager.getInstance(project).closeFile(virtualFile)
            }
        }

        if (docs.remove(path) == null) {
            LOG.warn("Attempted to close document without opening it at: $path")
        }
    }

    fun getSelectedEditor(virtualFile: VirtualFile): Editor? {
        val fileEditorManager = FileEditorManager.getInstance(project)

        return Optional.ofNullable(
            FileEditorManager.getInstance(project).getSelectedEditor(virtualFile)
        )
            .map { fileEditor: FileEditor -> (fileEditor as TextEditor).editor }
            .orElseGet {
                fileEditorManager.openTextEditor(
                    OpenFileDescriptor(
                        project,
                        virtualFile,
                        0
                    ), false
                )
            }
    }

    private fun applyContentChangeEventChanges(
        doc: Document,
        contentChanges: List<TextDocumentContentChangeEvent>
    ) {
        contentChanges.forEach(Consumer { it: TextDocumentContentChangeEvent ->
            applyChange(
                doc,
                it
            )
        })
    }

    companion object {
        private val LOG = Logger.getInstance(
            ManagedDocuments::class.java
        )

        private fun canAccept(uri: String): Boolean {
            return uri.matches("^(file|jar|jrt):/.*".toRegex())
        }

        private fun applyChange(doc: Document, change: TextDocumentContentChangeEvent) {
            val text = normalizeText(change.text)
            if (change.range == null) {
                // Change is the full insertText of the document
                doc.setText(text)
            } else {
                val textRange = toTextRange(doc, change.range)

                doc.replaceString(textRange.startOffset, textRange.endOffset, text)
            }
        }

        private fun normalizeText(text: String): String {
            return text.replace("\r\n", "\n")
        }
    }
}
