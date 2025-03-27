package com.github.kentvu.ideals2.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.github.kentvu.ideals2.MyBundle
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.PROJECT)
class MyProjectService(
    project: Project,
    private val cs: CoroutineScope
) {

    init {
        thisLogger().info(MyBundle.message("projectService", project.name))
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    fun getRandomNumber() = (1..100).random()
    fun startLspServer() {
        thisLogger().info("Start Lsp Server here")
        LspServerRunner().launch()
    }
}
