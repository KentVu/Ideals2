package org.rri.ideals.server.diagnostics

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.rri.ideals.server.LspContext
import org.rri.ideals.server.LspPath
import org.rri.ideals.server.MyLanguageClient
import org.rri.ideals.server.util.MiscUtil.getRange
import java.util.Objects
import java.util.Optional

class DiagnosticsListener(private val project: Project) : DaemonListener, Disposable {
    private val bus = project.messageBus.connect()
    private val client: MyLanguageClient

    init {
        this.client = LspContext.getContext(project).client
        bus.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, this)
    }

    override fun daemonFinished(fileEditors: Collection<FileEditor>) {
        fileEditors
            .stream()
            .filter { fileEditor: FileEditor? -> fileEditor is TextEditor }
            .forEach { fileEditor: FileEditor? ->
                val virtualFile = fileEditor!!.file
                val document = (fileEditor as TextEditor).editor.document
                val path = LspPath.fromVirtualFile(virtualFile)
                val diags = DaemonCodeAnalyzerImpl.getHighlights(document, null, project)
                    .stream()
                    .map { highlightInfo: HighlightInfo -> toDiagnostic(highlightInfo, document) }
                    .filter { o: Diagnostic? -> Objects.nonNull(o) }
                    .toList()
                client.publishDiagnostics(PublishDiagnosticsParams(path.toLspUri(), diags))
            }
    }

    override fun dispose() {
        bus.disconnect()
    }

    private fun toDiagnostic(info: HighlightInfo, doc: Document): Diagnostic? {
        if (info.description == null) return null

        val range = getRange(doc, info)
        val severity = Optional.ofNullable(
            severityMap[info.severity]
        )
            .orElse(DiagnosticSeverity.Hint)

        return Diagnostic(range, info.description, severity, "ideals")
    }

    companion object {
        private val severityMap: Map<HighlightSeverity, DiagnosticSeverity> = java.util.Map.of(
            HighlightSeverity.INFORMATION, DiagnosticSeverity.Information,
            HighlightSeverity.WARNING, DiagnosticSeverity.Warning,
            HighlightSeverity.ERROR, DiagnosticSeverity.Error
        )
    }
}
