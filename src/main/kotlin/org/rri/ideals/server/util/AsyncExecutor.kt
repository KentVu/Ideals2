package org.rri.ideals.server.util

import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.AppExecutorUtil
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.rri.ideals.server.LspPath
import org.rri.ideals.server.ManagedDocuments
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.util.MiscUtil.computeInEDTAndWait
import org.rri.ideals.server.util.MiscUtil.resolvePsiFile
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Function

class AsyncExecutor<R> private constructor(builder: Builder<R>) {
    private val cancellable: Boolean
    private val runInEDT: Boolean
    private val executor: Executor = AppExecutorUtil.getAppExecutorService()
    private val project: Project
    private val psiFile: PsiFile?
    private val position: Position?

    init {
        this.cancellable = builder.cancellable
        this.project = builder.project!!
        this.psiFile = builder.psiFile
        this.position = builder.position
        this.runInEDT = builder.runInEDT
    }

    fun compute(action: Function<ExecutorContext, R>): CompletableFuture<R> {
        return if (cancellable) {
            CompletableFutures.computeAsync(
                executor
            ) { cancelToken: CancelChecker? ->
                getResult(
                    action,
                    cancelToken
                )
            }
        } else {
            CompletableFuture.supplyAsync({ getResult(action, null) }, executor)
        }
    }

    private fun getResult(
        action: Function<ExecutorContext, R>,
        cancelToken: CancelChecker?
    ): R? {
        val editor = computeInEDTAndWait {
            val textEditor = Optional.ofNullable(psiFile)
                .map { file: PsiFile? ->
                    project.getService(
                        ManagedDocuments::class.java
                    ).getSelectedEditor(file!!.virtualFile)
                }
                .orElse(null)
            if (textEditor != null && position != null) {
                textEditor.caretModel.moveToLogicalPosition(
                    LogicalPosition(
                        position.line,
                        position.character
                    )
                )
            }
            textEditor
        }

        if (editor == null || psiFile == null) {
            return null
        }

        val context = ExecutorContext(psiFile, editor, cancelToken)

        try {
            return if (runInEDT) {
                computeInEDTAndWait { action.apply(context) }
            } else {
                action.apply(context)
            }
        } finally {
            cancelToken?.checkCanceled()
        }
    }

    class Builder<R> {
        internal var cancellable: Boolean = false
        var runInEDT: Boolean = false
        var project: Project? = null
        var position: Position? = null
        var psiFile: PsiFile? = null

        fun cancellable(cancellable: Boolean): Builder<R> {
            this.cancellable = cancellable
            return this
        }

        fun executorContext(project: Project, uri: String, position: Position?): Builder<R> {
            this.project = project
            val resolvedFile = resolvePsiFile(project, LspPath.fromLspUri(uri))
            this.psiFile = resolvedFile?.originalFile
            this.position = position
            return this
        }

        fun runInEDT(runInEDT: Boolean): Builder<R> {
            this.runInEDT = runInEDT
            return this
        }

        fun build(): AsyncExecutor<R> {
            return AsyncExecutor(this)
        }
    }

    companion object {
        fun <R> builder(): Builder<R> {
            return Builder()
        }
    }
}
