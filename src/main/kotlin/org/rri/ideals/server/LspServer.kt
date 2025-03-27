package org.rri.ideals.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManagerListener
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.DumbService.DumbModeListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionOptions
import org.eclipse.lsp4j.CompletionItemOptions
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.DocumentOnTypeFormattingOptions
import org.eclipse.lsp4j.FileOperationFilter
import org.eclipse.lsp4j.FileOperationOptions
import org.eclipse.lsp4j.FileOperationPattern
import org.eclipse.lsp4j.FileOperationsServerCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.SaveOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SignatureHelpOptions
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkspaceServerCapabilities
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.rri.ideals.server.ProjectService.Companion.instance
import org.rri.ideals.server.diagnostics.DiagnosticsListener
import org.rri.ideals.server.util.Metrics.run
import org.rri.ideals.server.util.MiscUtil.with
import java.util.List
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class LspServer : LanguageServer, LanguageClientAware,
    LspSession, DumbModeListener {
    private val myTextDocumentService = MyTextDocumentService(this)
    private val myWorkspaceService = MyWorkspaceService(this)

    private val messageBusConnection =
        ApplicationManager.getApplication().messageBus.connect()
    private val disposable =
        Disposer.newDisposable()
    private var client: MyLanguageClient? = null

    private var _project: Project? = null
    override var project: Project
        get() = checkNotNull(_project) { "LSP session is not yet initialized" }
        private set(value) {
            _project = value
        }

    init {
        messageBusConnection.subscribe(ProgressManagerListener.TOPIC, WorkDoneProgressReporter())
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        return CompletableFuture.supplyAsync {
            val workspaceFolders = params.workspaceFolders
            val oldProject = _project
            if (oldProject != null) {
                if (oldProject.isOpen) {
                    LOG.info("Closing old project: $oldProject")
                    instance.closeProject(oldProject)
                }
                _project = null
            }

            if (workspaceFolders == null) {
                return@supplyAsync InitializeResult(ServerCapabilities())
            }

            //   // todo how about multiple folders
            val projectRoot = LspPath.fromLspUri(workspaceFolders[0].uri)

            run(
                { "initialize: $projectRoot" },
                {
                    LOG.info("Opening project: $projectRoot")
                    project = instance.resolveProjectFromRoot(projectRoot)

                    checkNotNull(client)
                    LspContext.createContext(project!!, client!!, params.capabilities)
                    project!!.messageBus.connect().subscribe(
                        DumbService.DUMB_MODE,
                        this
                    )
                    val listener = DiagnosticsListener(project!!)
                    Disposer.register(disposable, listener)
                    LOG.info("LSP was initialized. Project: $project")
                })
            InitializeResult(defaultServerCapabilities())
        }
    }

    private fun defaultCompletionOptions(): CompletionOptions {
        val completionOptions = CompletionOptions(true, listOf(".", "@"))
        completionOptions.resolveProvider = true
        val completionItemOptions = CompletionItemOptions()
        completionItemOptions.labelDetailsSupport = true
        completionOptions.completionItem = completionItemOptions
        return completionOptions
    }

    private fun defaultServerCapabilities(): ServerCapabilities {
        return with(
            ServerCapabilities()
        ) { it: ServerCapabilities ->
            it.setTextDocumentSync(
                with(
                    TextDocumentSyncOptions()
                ) { syncOptions: TextDocumentSyncOptions ->
                    syncOptions.openClose = true
                    syncOptions.change = TextDocumentSyncKind.Incremental
                    syncOptions.setSave(SaveOptions(true))
                })
            it.workspace =
                with(
                    WorkspaceServerCapabilities()
                ) { wsc: WorkspaceServerCapabilities ->
                    wsc.fileOperations =
                        with(
                            FileOperationsServerCapabilities()
                        ) { foc: FileOperationsServerCapabilities ->
                            foc.didRename = FileOperationOptions(
                                List.of(
                                    FileOperationFilter(
                                        FileOperationPattern("**/*"),
                                        "file"
                                    )
                                )
                            )
                        }
                }

            it.setHoverProvider(true)
            it.completionProvider = defaultCompletionOptions()
            it.signatureHelpProvider =
                with(
                    SignatureHelpOptions()
                ) { signatureHelpOptions: SignatureHelpOptions ->
                    signatureHelpOptions.triggerCharacters =
                        listOf("(", "[")
                }
            it.setDefinitionProvider(true)
            it.setTypeDefinitionProvider(true)
            it.setImplementationProvider(true)
            it.setReferencesProvider(true)
            it.setDocumentHighlightProvider(true)
            it.setDocumentSymbolProvider(true)
            it.setWorkspaceSymbolProvider(true)
            //      it.setCodeLensProvider(new CodeLensOptions(false));
            it.setDocumentFormattingProvider(true)
            it.setDocumentRangeFormattingProvider(true)
            it.documentOnTypeFormattingProvider =
                defaultOnTypeFormattingOptions()

            it.setRenameProvider(true)

            //      it.setDocumentLinkProvider(null);
//      it.setExecuteCommandProvider(new ExecuteCommandOptions());
            it.setCodeActionProvider(
                with(
                    CodeActionOptions(List.of(CodeActionKind.QuickFix))
                ) { cao: CodeActionOptions ->
                    cao.resolveProvider =
                        true
                }
            )
            it.experimental = null
        }
    }

    override fun shutdown(): CompletableFuture<Any> {
        return CompletableFuture.supplyAsync {
            stop()
            null
        }
    }

    override fun exit() {
        stop()
    }

    fun stop() {
        messageBusConnection.disconnect()

        if (project != null) {
            Disposer.dispose(disposable)
            val editorManager = FileEditorManager.getInstance(project!!)
            ApplicationManager.getApplication().invokeAndWait {
                for (openFile in editorManager.openFiles) {
                    editorManager.closeFile(openFile!!)
                }
            }
            instance.closeProject(project!!)
            this._project = null
        }
    }

    override fun getTextDocumentService(): TextDocumentService {
        return myTextDocumentService
    }

    override fun getWorkspaceService(): WorkspaceService {
        return myWorkspaceService
    }

    @get:JsonDelegate
    val experimentalProtocolExtensions: ExperimentalProtocolExtensions
        get() = myTextDocumentService

    override fun connect(client: LanguageClient) {
        assert(client is MyLanguageClient)
        this.client = client as MyLanguageClient
    }

    private fun getClient(): MyLanguageClient {
        checkNotNull(client)
        return client as MyLanguageClient
    }

    override fun enteredDumbMode() {
        LOG.info("Entered dumb mode. Notifying client...")
        getClient().notifyIndexStarted()
    }

    override fun exitDumbMode() {
        LOG.info("Exited dumb mode. Refreshing diagnostics...")
        getClient().notifyIndexFinished()
    }

    private inner class WorkDoneProgressReporter : ProgressManagerListener {
        override fun afterTaskStart(task: Task, indicator: ProgressIndicator) {
            if (task.project == null || task.project != project) return

            val client = this@LspServer.client ?: return

            val token = calculateUniqueToken(task)
            try {
                client
                    .createProgress(
                        WorkDoneProgressCreateParams(
                            Either.forLeft(
                                token
                            )
                        )
                    )[500, TimeUnit.MILLISECONDS]
            } catch (e: InterruptedException) {
                LOG.warn(
                    "Could not get confirmation when creating work done progress; will act as if it's created",
                    e
                )
            } catch (e: ExecutionException) {
                LOG.warn(
                    "Could not get confirmation when creating work done progress; will act as if it's created",
                    e
                )
            } catch (e: TimeoutException) {
                LOG.warn(
                    "Could not get confirmation when creating work done progress; will act as if it's created",
                    e
                )
            }

            val progressBegin = WorkDoneProgressBegin()
            progressBegin.title = task.title
            progressBegin.cancellable = false
            progressBegin.percentage = 0
            client.notifyProgress(
                ProgressParams(
                    Either.forLeft(token),
                    Either.forLeft(progressBegin)
                )
            )
        }

        override fun afterTaskFinished(task: Task) {
            if (task.project != null && task.project != project) return

            val client = this@LspServer.client ?: return

            val token = calculateUniqueToken(task)
            client.notifyProgress(
                ProgressParams(
                    Either.forLeft(token), Either.forLeft(
                        WorkDoneProgressEnd()
                    )
                )
            )
        }

        fun calculateUniqueToken(task: Task): String {
            return task.javaClass.name + '@' + System.identityHashCode(task)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(
            LspServer::class.java
        )

        private fun defaultOnTypeFormattingOptions(): DocumentOnTypeFormattingOptions {
            return DocumentOnTypeFormattingOptions(
                ";",
                listOf( // "{", "(", "<",  "\"", "'", "[", todo decide how to handle this cases
                    "}", ")", "]", ">", ":", ",", ".", "@", "#", "?", "=", "!", " ",
                    "|", "&", "$", "^", "%", "*", "/"
                )
            )
        }
    }
}