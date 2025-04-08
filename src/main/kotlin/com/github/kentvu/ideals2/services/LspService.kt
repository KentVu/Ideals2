package com.github.kentvu.ideals2.services

import com.github.kentvu.ideals2.LspServerRunner
import com.github.kentvu.ideals2.ServerState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.rri.ideals.server.LspPath
import java.nio.file.Files
import java.nio.file.Paths

@Service
class LspService(
    private val scope: CoroutineScope
) {
    private lateinit var runner: LspServerRunner
    private val _state: MutableStateFlow<ServerState> = MutableStateFlow(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _state

    fun startLspServer(port: Int): Job {
        if (serverState.value == ServerState.Stopped) {
            thisLogger().info("Start Lsp Server on port $port")
            runner = LspServerRunner(port, _state)
            val job = scope.launch {
                runner.launch().await()
            }
            return job
        }
        return scope.launch { }
    }

    fun setServerState(state: ServerState) {
        _state.value = state
    }

    fun stopLspServer() {
        //stopRequest = true
        runner.stop()
    }

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

        fun resolveProjectFromRoot(rootPath: LspPath): Project {
            require(Files.isDirectory(rootPath.toPath())) { "Isn't a directory: $rootPath" }
            val mgr = ProjectManagerEx.getInstanceEx()
            //findOrLoadProject
            return mgr.openProjects.find {
                LspPath.fromLocalPath(Paths.get(requireNotNull(it.basePath))) == rootPath
            } ?: error("Project @$rootPath has not been opened")
        }
    }
}
