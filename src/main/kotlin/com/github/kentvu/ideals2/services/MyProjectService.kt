package com.github.kentvu.ideals2.services

import com.github.kentvu.ideals2.LspServerRunner
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.github.kentvu.ideals2.MyBundle
import com.github.kentvu.ideals2.ServerState
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class MyProjectService(
    private val project: Project,
    private val scope: CoroutineScope
) {
    private val _state: MutableStateFlow<ServerState> = MutableStateFlow(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _state

    init {
        thisLogger().info(MyBundle.message("projectService", project.name))
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    fun getRandomNumber() = (1..100).random()
    fun onServerState(action: (ServerState) -> Unit) {
        /*scope.launch(Dispatchers.EDT) {
            //serverState.coll
            action()
        }*/
    }

    fun startLspServer(port: Int): Job {
        thisLogger().info("Start Lsp Server on port $port")
        return scope.launch {
            LspServerRunner(project, port, scope).launch().await()
        }
    }

    fun setServerState(state: ServerState) {
        _state.value = state
    }
}
