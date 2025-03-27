package org.rri.ideals.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFileManager
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.RenameFilesParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import org.rri.ideals.server.symbol.WorkspaceSymbolService
import java.util.concurrent.CompletableFuture

class MyWorkspaceService(private val session: LspSession) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
    }

    override fun didRenameFiles(params: RenameFilesParams) {
        // Refresh file system to avoid false positives in diagnostics (see #38)
        //
        // TODO
        //  it would probably be better to move this into didOpen
        //  because the order of and delays between calls didClose/didOpen/didRenameFiles
        //  during file rename seems client-specific
        //  so VFS refresh may happen too late and thus have no effect
        ApplicationManager.getApplication().invokeAndWait {
            VirtualFileManager.getInstance().syncRefresh()
        }
    }

    private fun workspaceSymbol(): WorkspaceSymbolService {
        return session.project.getService(WorkspaceSymbolService::class.java)
    }

    @Suppress("deprecation")
    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation?>, List<WorkspaceSymbol>>> {
        return workspaceSymbol().runSearch(params.query)
    }
}
