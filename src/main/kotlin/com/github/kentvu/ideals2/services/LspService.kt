package com.github.kentvu.ideals2.services

import com.github.kentvu.ideals2.ServerState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import org.rri.ideals.server.LspPath
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Objects

@Service
class LspService {

    companion object {
        fun ensureSameProject(project: Project, root: LspPath) {
            // TODO: in-memory virtual files for testing have temp:/// prefix, figure out how to resolve the document from them
            // otherwise it gets confusing to have to look up the line and column being tested in the test document
            require(Files.isDirectory(root.toPath())) { "Isn't a directory: $root" }
            require(
                LspPath.fromLocalPath(
                    Paths.get(requireNotNull(project.basePath))
                ) == root
            )
        }
    }
}