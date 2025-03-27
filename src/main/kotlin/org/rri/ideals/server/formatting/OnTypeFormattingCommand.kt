package org.rri.ideals.server.formatting

import com.intellij.ide.highlighter.HighlighterFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtilEx
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.util.EditorUtil.withEditor
import org.rri.ideals.server.util.TextUtil.differenceAfterAction
import java.util.function.Consumer
import java.util.function.Supplier

class OnTypeFormattingCommand(
    private val position: Position,
    formattingOptions: FormattingOptions,
    private val triggerCharacter: Char
) : FormattingCommandBase(formattingOptions) {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "on type formatting" }

    override val isCancellable: Boolean
        get() = false

    override fun execute(ctx: ExecutorContext): List<TextEdit?> {
        LOG.info(messageSupplier.get())
        return differenceAfterAction(
            ctx.psiFile
        ) { psiFile: PsiFile -> this.typeAndReformatIfNeededInFile(psiFile) }
    }

    fun typeAndReformatIfNeededInFile(psiFile: PsiFile) {
        withEditor(
            Disposer.newDisposable(), psiFile, position,
            { editor: Editor? ->
                ApplicationManager.getApplication().runWriteAction {
                    if (editor?.let { deleteTypedChar(it) } == true) {
                        return@runWriteAction
                    }
                    requireNotNull(editor)
                    PsiDocumentManager.getInstance(psiFile.project).commitDocument(editor.document)

                    if (editor is EditorEx) {
                        editor.highlighter =
                            HighlighterFactory.createHighlighter(
                                psiFile.project,
                                psiFile.fileType
                            )
                    }
                    doWithTemporaryCodeStyleSettingsForFile(
                        psiFile
                    ) {
                        TypedAction.getInstance().actionPerformed(
                            editor,
                            triggerCharacter,
                            EditorUtil.getEditorDataContext(editor)
                        )
                    }
                }
            })
    }

    private fun deleteTypedChar(editor: Editor): Boolean {
        val insertedCharPos = editor.caretModel.offset - 1

        if (editor.document.text[insertedCharPos] != triggerCharacter) {
            // if triggered character and actual are not the same
            LOG.warn("Inserted and triggered characters are not the same")
            return false
        }

        editor.selectionModel.setSelection(
            insertedCharPos,
            insertedCharPos + 1
        )
        ApplicationManager.getApplication().runWriteAction {
            WriteCommandAction.runWriteCommandAction(
                editor.project
            ) { EditorModificationUtilEx.deleteSelectedText(editor) }
        }

        editor.caretModel.moveToLogicalPosition(
            LogicalPosition(position.line, position.character - 1)
        )
        return true
    }

    companion object {
        private val LOG = Logger.getInstance(
            OnTypeFormattingCommand::class.java
        )
    }
}
