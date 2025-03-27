package org.rri.ideals.server

import com.intellij.openapi.project.Project

interface LspSession {
    val project: Project
}
