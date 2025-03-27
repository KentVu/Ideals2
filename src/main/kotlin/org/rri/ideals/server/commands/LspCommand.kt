package org.rri.ideals.server.commands

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.rri.ideals.server.util.AsyncExecutor
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

abstract class LspCommand<R> {
    protected abstract val messageSupplier: Supplier<String>

    protected abstract val isCancellable: Boolean

    protected abstract fun execute(ctx: ExecutorContext): R

    protected open val isRunInEdt: Boolean
        get() = true

    fun runAsync(
        project: Project,
        textDocumentIdentifier: TextDocumentIdentifier
    ): CompletableFuture<R> {
        return runAsync(project, textDocumentIdentifier.uri, null)
    }

    fun runAsync(
        project: Project,
        textDocumentIdentifier: TextDocumentIdentifier,
        position: Position?
    ): CompletableFuture<R> {
        return runAsync(project, textDocumentIdentifier.uri, position)
    }

    fun runAsync(project: Project, uri: String, position: Position?): CompletableFuture<R> {
        LOG.info(messageSupplier.get())
        val client = AsyncExecutor.builder < R  > ()
            .executorContext(project, uri, position)
            .cancellable(isCancellable)
            .runInEDT(isRunInEdt)
            .build()

        return client.compute { ctx: ExecutorContext -> this.execute(ctx) }
    }

    companion object {
        private val LOG = Logger.getInstance(
            LspCommand::class.java
        )
    }
}
