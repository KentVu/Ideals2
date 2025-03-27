package org.rri.ideals.server.formatting

import com.intellij.CodeStyleBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.TextUtil.differenceAfterAction
import org.rri.ideals.server.util.TextUtil.toTextRange
import java.util.function.Supplier

class FormattingCommand(private val lspRange: Range?, formattingOptions: FormattingOptions) :
    FormattingCommandBase(formattingOptions) {
    override fun execute(context: ExecutorContext): List<TextEdit> {
        // create reformat results
        LOG.info(messageSupplier.get())
        return differenceAfterAction(
            context.psiFile
        ) { copy: PsiFile -> reformatPsiFile(context, copy) }
    }

    fun reformatPsiFile(context: ExecutorContext, psiFile: PsiFile) {
        CommandProcessor
            .getInstance()
            .executeCommand(
                psiFile.project,
                {
                    doWithTemporaryCodeStyleSettingsForFile(
                        psiFile
                    ) { doReformat(psiFile, getConfiguredTextRange(psiFile)) }
                },  // this name is necessary for ideas blackbox TextRange formatting
                CodeStyleBundle.message("process.reformat.code"),
                null
            )

        checkNotNull(context.cancelToken)
        context.cancelToken.checkCanceled()
    }

    private fun getConfiguredTextRange(psiFile: PsiFile): TextRange {
        val doc = checkNotNull(getDocument(psiFile))
        val textRange = if (lspRange != null) {
            toTextRange(doc, lspRange)
        } else {
            TextRange(0, psiFile.textLength)
        }
        return textRange
    }

    override val messageSupplier: Supplier<String>
        get() = Supplier { "Format call" }

    override val isCancellable: Boolean
        get() = true

    companion object {
        private val LOG = Logger.getInstance(
            FormattingCommand::class.java
        )

        private fun doReformat(psiFile: PsiFile, textRange: TextRange) {
            ApplicationManager.getApplication().runWriteAction {
                CodeStyleManager.getInstance(psiFile.project)
                    .reformatText(
                        psiFile,
                        java.util.List.of(textRange)
                    )
            }
        }
    }
}
