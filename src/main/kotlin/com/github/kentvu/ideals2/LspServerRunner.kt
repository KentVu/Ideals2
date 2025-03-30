package com.github.kentvu.ideals2

import com.github.kentvu.ideals2.services.MyProjectService
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.CoroutineScope
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.rri.ideals.server.LspServer
import org.rri.ideals.server.MyLanguageClient
import org.rri.ideals.server.util.MiscUtil.RunnableWithException
import org.rri.ideals.server.util.MiscUtil.asRunnable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.Channels
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Function

class LspServerRunner(private val project: Project, val port: Int, val scope: CoroutineScope) {
    companion object {
        val LOG=thisLogger()
    }

    private var serverSocket: AsynchronousServerSocketChannel? = null
    fun launch(): CompletableFuture<Void> {
        LOG.info("Launch..")
        serverSocket = AsynchronousServerSocketChannel.open()
            .bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        return CompletableFuture.runAsync({
            connectServer(waitForConnection()).join()
        },
            AppExecutorUtil.getAppExecutorService()
        )
    }

    @JvmRecord
    protected data class Connection(val input: InputStream, val output: OutputStream)

    private fun waitForConnection(): Connection
    {
        checkNotNull(serverSocket)
        try {
            val socketChannel = serverSocket!!.accept().get()
            return Connection(
                Channels.newInputStream(socketChannel),
                Channels.newOutputStream(socketChannel)
            )
        } catch (e: Exception) {
            LOG.error("Socket connection error: $e")
            closeServerSocket()
            throw e
        }
    }

    private fun closeServerSocket() {
        when (val ss = serverSocket) {
            null -> {}
            else -> try {
                LOG.info("Close language server socket port " + (ss.localAddress as InetSocketAddress).port)
                ss.close()
            } catch (ioe: IOException) {
                LOG.error("Close ServerSocket exception: $ioe")
            }
        }
    }

    private fun createServerThreads(): ExecutorService {
        return Executors.newCachedThreadPool()
    }

    private fun connectServer(connection: Connection): CompletableFuture<Void> {
        val wrapper =
            Function { consumer: MessageConsumer? -> consumer }
        val languageServer = LspServer(project)
        val launcher = Launcher.createIoLauncher(
            languageServer, MyLanguageClient::class.java,
            connection.input, connection.output, createServerThreads(), wrapper
        )
        val client = launcher.remoteProxy
        languageServer.connect(client)
        LOG.info("Listening for commands.")
        project.service<MyProjectService>().setServerState(ServerState.Started)
        /*return scope.launch {
            launcher.startListening().get()
            languageServer.stop()
        }*/
        return CompletableFuture
            .runAsync(
                asRunnable(object : RunnableWithException {
                    override fun run() {
                        launcher.startListening().get()
                    }
                }),
                AppExecutorUtil.getAppExecutorService()
            )
            .whenComplete { ignored1: Void?, ignored2: Throwable? ->
                languageServer.stop()
                project.service<MyProjectService>().setServerState(ServerState.Stopped)
            }
    }
}
