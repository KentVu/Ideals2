package com.github.kentvu.ideals2.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.github.kentvu.ideals2.MyBundle
import com.github.kentvu.ideals2.ServerState
import com.github.kentvu.ideals2.services.LspService
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.rd.createLifetime
import com.intellij.ui.components.JBList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JTextField


class MyToolWindowFactory : ToolWindowFactory, DumbService.DumbModeListener {

    init {
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(toolWindow)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.project.messageBus.connect().subscribe(
            DumbService.DUMB_MODE,
            this
        )
    }

    /** shouldBeAvailable only when NOT Dumb! */
    override fun shouldBeAvailable(project: Project) = !DumbService.isDumb(project)
    override fun exitDumbMode() {
        // Autostart LSP server after DumbMode exit.
        //service<LspService>().startLspServer(8989)
    }

    class MyToolWindow(toolWindow: ToolWindow) {

        //private val service = toolWindow.project.service<MyProjectService>()
        private val service = service<LspService>()
        private val scope  = toolWindow.disposable.createLifetime().coroutineScope

        fun getContent() = JBPanel<JBPanel<*>>().apply {
            //layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            val dataModel = DefaultListModel<String>()
            //service.serverState.collect
            val jTextField = JTextField("8989", 5)
            add(JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.LINE_AXIS)
                add(JBLabel(MyBundle.message("selectPortLabel")))
                add(jTextField)
                add(Box.createHorizontalGlue())
                val jButton = JButton(MyBundle.message("startLspButton"))
                add(jButton.apply {
                    addActionListener {
                        try {
                            startLspServer(jTextField.text.toInt())
                        } catch (nfe: NumberFormatException) {
                            dataModel.addElement("NumberFormatException: " + nfe.message)
                        }
                    }
                })
                // todo dispose when server stop!
                scope.launch(Dispatchers.EDT) {
                    service.serverState.collect {
                        dataModel.addElement(it.toString())
                        if (it == ServerState.Stopped) {
                            jButton.text = MyBundle.message("startLspButton")
                        } else
                            jButton.text = "Stop"
                    }
                }
            }, BorderLayout.NORTH)
            //add(Box.createVerticalGlue())
            add(JBPanel<JBPanel<*>>().apply {
                //mutableListOf("aaa", "bbb")
                add(JBList(dataModel))
            }, BorderLayout.CENTER)
        }

        private fun startLspServer(port: Int) {
            if (service.serverState.value == ServerState.Stopped) {
                service.startLspServer(port)
            } else {
                service.stopLspServer()
            }
        }
    }
}
