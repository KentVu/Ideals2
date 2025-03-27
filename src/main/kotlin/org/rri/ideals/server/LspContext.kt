package org.rri.ideals.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.eclipse.lsp4j.ClientCapabilities

class LspContext private constructor(
    val client: MyLanguageClient,
    val clientCapabilities: ClientCapabilities
) {
    private val config: Map<String, String> = HashMap()

    fun getConfigValue(key: String): String? {
        return config[key]
    }

    companion object {
        private val KEY = Key<LspContext>(
            LspContext::class.java.canonicalName
        )

        fun createContext(
            project: Project,
            client: MyLanguageClient,
            clientCapabilities: ClientCapabilities
        ) {
            project.putUserData(KEY, LspContext(client, clientCapabilities))
        }

        fun getContext(project: Project): LspContext {
            val result = project.getUserData(KEY)
            checkNotNull(result) { "LSP context hasn't been created" }

            return result
        }
    }
}
