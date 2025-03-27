package org.rri.ideals.server

import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment
import org.rri.ideals.server.extensions.Runnable
import java.util.concurrent.CompletableFuture

@JsonSegment("experimental")
interface ExperimentalProtocolExtensions {
    @JsonRequest
    fun classFileContents(params: TextDocumentIdentifier): CompletableFuture<String?>?

    @JsonRequest
    fun runnables(params: TextDocumentIdentifier): CompletableFuture<List<Runnable?>?>?
}
