package com.github.kentvu.ideals2.actions

import com.github.kentvu.ideals2.services.LspService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Messages


class StartLspServer : AnAction() {
    private val service = service<LspService>()

    override fun update(e: AnActionEvent) {
        // Set the availability based on whether a project is open
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        service.startLspServer(8989)
        // Using the event, create and show a dialog
        val currentProject = event.project
        val message =
            StringBuilder(event.presentation.text + " Selected!")
        // If an element is selected in the editor, add info about it.
        val selectedElement = event.getData(CommonDataKeys.NAVIGATABLE)
        if (selectedElement != null) {
            message.append("\nSelected Element: ").append(selectedElement)
        }
        val title = event.presentation.description
        Messages.showMessageDialog(
            currentProject,
            message.toString(),
            title,
            Messages.getInformationIcon()
        )
    }
}
