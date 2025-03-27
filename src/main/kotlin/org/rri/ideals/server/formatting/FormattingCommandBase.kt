package org.rri.ideals.server.formatting

import com.intellij.application.options.CodeStyle
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
//import com.jetbrains.python.PythonLanguage
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.TextEdit
import org.rri.ideals.server.commands.LspCommand

abstract class FormattingCommandBase protected constructor(private val formattingOptions: FormattingOptions) :
    LspCommand<List<TextEdit?>>() {
    private fun getConfiguredSettings(copy: PsiFile): CodeStyleSettings {
        val codeStyleSettings: CodeStyleSettings =
            CodeStyleSettingsManager.getInstance().cloneSettings(CodeStyle.getSettings(copy))
        val indentOptions: CommonCodeStyleSettings.IndentOptions = codeStyleSettings.getIndentOptionsByFile(copy)
        /*try {
            if (copy.getLanguage() == PythonLanguage.getInstance()) {
                codeStyleSettings.getCustomSettings<T>(PyCodeStyleSettings::class.java).BLANK_LINE_AT_FILE_END =
                    formattingOptions.isInsertFinalNewline
            }
        } catch (ignored: NoClassDefFoundError) {
        }*/

        indentOptions.TAB_SIZE = formattingOptions.tabSize
        indentOptions.INDENT_SIZE = formattingOptions.tabSize
        indentOptions.USE_TAB_CHARACTER = !formattingOptions.isInsertSpaces

        return codeStyleSettings
    }

    protected fun doWithTemporaryCodeStyleSettingsForFile(
        psiFile: PsiFile,
        action: Runnable
    ) {
        CodeStyle.doWithTemporarySettings(
            psiFile.getProject(),
            getConfiguredSettings(psiFile),
            action
        )
    }
}
