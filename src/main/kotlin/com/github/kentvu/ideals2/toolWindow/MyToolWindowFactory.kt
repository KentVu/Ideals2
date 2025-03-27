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
import com.github.kentvu.ideals2.services.MyProjectService
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBList
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JTextField


class MyToolWindowFactory : ToolWindowFactory {

    init {
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(toolWindow)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    class MyToolWindow(toolWindow: ToolWindow) {

        private val service = toolWindow.project.service<MyProjectService>()

        fun getContent() = JBPanel<JBPanel<*>>().apply {
            add(JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JTextField(5))
                add(Box.createHorizontalGlue())
                //add(Box.createVerticalGlue())
                add(JButton(MyBundle.message("startLspButton")).apply {
                    addActionListener {
                        service.startLspServer()
                    }
                })
            })
            //mutableListOf("aaa", "bbb")
            val dataModel = DefaultListModel<String>()
            add(JBList(dataModel))
            dataModel.addElement("aaa")
            /*add(Box.createVerticalGlue())
            add(JBPanel<JBPanel<*>>().apply {
                val label = JBLabel(MyBundle.message("randomLabel", "?"))

                add(label)
                add(JButton(MyBundle.message("shuffle")).apply {
                    addActionListener {
                        label.text = MyBundle.message("randomLabel", service.getRandomNumber())
                    }
                })
            })*/
        }
    }
}
