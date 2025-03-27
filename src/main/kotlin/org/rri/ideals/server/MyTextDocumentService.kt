package org.rri.ideals.server

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.util.concurrency.AppExecutorUtil
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams
import org.eclipse.lsp4j.DocumentRangeFormattingParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeDefinitionParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.lsp4j.jsonrpc.CompletableFutures
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import org.rri.ideals.server.ManagedDocuments
import org.rri.ideals.server.codeactions.ActionData
import org.rri.ideals.server.codeactions.CodeActionService
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.completions.CompletionService
import org.rri.ideals.server.extensions.ClassFileContentsCommand
import org.rri.ideals.server.extensions.Runnable
import org.rri.ideals.server.extensions.RunnablesCommand
import org.rri.ideals.server.formatting.FormattingCommand
import org.rri.ideals.server.formatting.OnTypeFormattingCommand
import org.rri.ideals.server.hover.HoverCommand
import org.rri.ideals.server.references.DocumentHighlightCommand
import org.rri.ideals.server.references.FindDefinitionCommand
import org.rri.ideals.server.references.FindImplementationCommand
import org.rri.ideals.server.references.FindTypeDefinitionCommand
import org.rri.ideals.server.references.FindUsagesCommand
import org.rri.ideals.server.rename.RenameCommand
import org.rri.ideals.server.signature.SignatureHelpService
import org.rri.ideals.server.symbol.DocumentSymbolService
import org.rri.ideals.server.util.AsyncExecutor
import org.rri.ideals.server.util.Metrics.run
import java.util.concurrent.CompletableFuture
import java.util.function.Function

class MyTextDocumentService(private val session: LspSession) : TextDocumentService,
    ExperimentalProtocolExtensions {
    override fun didOpen(params: DidOpenTextDocumentParams) {
        val textDocument = params.textDocument

        val path = LspPath.fromLspUri(textDocument.uri)

        run(
            { "didOpen: $path" },
            {
                documents().startManaging(textDocument)
                if (DumbService.isDumb(session.project)) {
                    LOG.debug("Sending indexing started: $path")
                    LspContext.getContext(session.project).client.notifyIndexStarted()
                }
            })
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val path = LspPath.fromLspUri(params.textDocument.uri)

        run(
            { "didChange: $path" },
            {
                documents().updateDocument(params)
            })
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        documents().stopManaging(params.textDocument)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        documents().syncDocument(params.textDocument)
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location?>, List<LocationLink?>>> {
        return FindDefinitionCommand()
            .runAsync(session.project, params.textDocument, params.position)
    }

    override fun typeDefinition(params: TypeDefinitionParams): CompletableFuture<Either<List<Location?>, List<LocationLink?>>> {
        return FindTypeDefinitionCommand()
            .runAsync(session.project, params.textDocument, params.position)
    }

    override fun implementation(params: ImplementationParams): CompletableFuture<Either<List<Location?>, List<LocationLink?>>> {
        return FindImplementationCommand()
            .runAsync(session.project, params.textDocument, params.position)
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location?>> {
        return FindUsagesCommand()
            .runAsync(session.project, params.textDocument, params.position)
    }

    override fun documentHighlight(params: DocumentHighlightParams): CompletableFuture<List<DocumentHighlight?>> {
        return DocumentHighlightCommand()
            .runAsync(session.project, params.textDocument, params.position)
    }

    @Suppress("deprecation")
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val uri = params.textDocument.uri
        if (uri.startsWith("output:")) {
            return CompletableFuture.completedFuture(listOf())
        }
        val client =
            AsyncExecutor.builder<List<Either<SymbolInformation, DocumentSymbol>>>()
                .cancellable(true)
                .executorContext(session.project, uri, null)
                .build()

        return client.compute(({ executorContext ->
            documentSymbols().computeDocumentSymbols(
                executorContext
            )
        }))
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        val client =
            AsyncExecutor.builder<List<Either<Command, CodeAction>>>()
        .executorContext(session.project, params.textDocument.uri, params.range.start)
            .build()

        return client.compute { executorContext ->
            codeActions().getCodeActions(params.range, executorContext).stream()
                .map<Either<Command, CodeAction>>(
                    Function<CodeAction?, Either<Command, CodeAction>> { right: CodeAction? ->
                        Either.forRight(
                            right
                        )
                    })
                .toList()
        }
    }


    override fun resolveCodeAction(unresolved: CodeAction): CompletableFuture<CodeAction> {
        val actionData = GsonBuilder().create()
            .fromJson(unresolved.data.toString(), ActionData::class.java)
        val client =
            AsyncExecutor. builder < CodeAction  > ()
                .executorContext(session.project, actionData.uri, actionData.range.start)
                .build()

        return client.compute { executorContext ->
            val edit =
                codeActions().applyCodeAction(actionData, unresolved.title, executorContext)
            unresolved.edit = edit
            unresolved
        }
    }

    private fun documents(): ManagedDocuments {
        return session.project.getService(ManagedDocuments::class.java)
    }

    private fun codeActions(): CodeActionService {
        return session.project.getService(CodeActionService::class.java)
    }

    private fun completions(): CompletionService {
        return session.project.getService(CompletionService::class.java)
    }

    private fun documentSymbols(): DocumentSymbolService {
        return session.project.getService(DocumentSymbolService::class.java)
    }

    private fun signature(): SignatureHelpService {
        return session.project.getService(SignatureHelpService::class.java)
    }

    override fun resolveCompletionItem(unresolved: CompletionItem): CompletableFuture<CompletionItem> {
        return CompletableFutures.computeAsync(
            AppExecutorUtil.getAppExecutorService()
        ) { cancelChecker: CancelChecker? ->
            completions().resolveCompletion(
                unresolved,
                cancelChecker!!
            )
        }
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val client =
            AsyncExecutor.builder<Either<List<CompletionItem>, CompletionList>>()
                .cancellable(true)
                .executorContext(session.project, params.textDocument.uri, params.position)
                .build()

        return client.compute(({ executorContext ->
            Either.forLeft(
                completions().computeCompletions(
                    executorContext
                )
            )
        }))
    }

    override fun signatureHelp(params: SignatureHelpParams): CompletableFuture<SignatureHelp> {
        val client =
            AsyncExecutor. builder < SignatureHelp  > ()
                .cancellable(true)
                .executorContext(session.project, params.textDocument.uri, params.position)
                .build()
        val signature = signature()

        return client.compute(({ executorContext: ExecutorContext ->
            signature.computeSignatureHelp(
                executorContext
            )
        }))
    }


    override fun formatting(params: DocumentFormattingParams): CompletableFuture<List<TextEdit?>> {
        return FormattingCommand(null, params.options)
            .runAsync(session.project, params.textDocument)
    }

    override fun rangeFormatting(params: DocumentRangeFormattingParams): CompletableFuture<List<TextEdit?>> {
        return FormattingCommand(params.range, params.options)
            .runAsync(session.project, params.textDocument)
    }

    override fun onTypeFormatting(params: DocumentOnTypeFormattingParams): CompletableFuture<List<TextEdit?>> {
        return OnTypeFormattingCommand(params.position, params.options, params.ch[0])
            .runAsync(session.project, params.textDocument)
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        return RenameCommand(params.newName)
            .runAsync(session.project, params.textDocument, params.position)
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        return HoverCommand()
            .runAsync(session.project, params.textDocument, params.position)
    }

    override fun classFileContents(params: TextDocumentIdentifier): CompletableFuture<String?>? {
        return ClassFileContentsCommand()
            .runAsync(session.project, params)
    }

    override fun runnables(params: TextDocumentIdentifier): CompletableFuture<List<Runnable?>?>? {
        return RunnablesCommand()
            .runAsync(session.project, params)
    }

    companion object {
        private val LOG = Logger.getInstance(
            MyTextDocumentService::class.java
        )
    }
}
