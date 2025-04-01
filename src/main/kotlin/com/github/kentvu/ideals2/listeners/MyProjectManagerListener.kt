package com.github.kentvu.ideals2.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class MyProjectManagerListener : ProjectManagerListener {
    @Suppress("removal")
    override fun projectOpened(project: Project) {
        super.projectOpened(project)
    }
}
