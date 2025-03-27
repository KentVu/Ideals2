package org.rri.ideals.server.codeactions

import com.intellij.codeInsight.daemon.impl.HighlightInfo.IntentionActionDescriptor
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass.IntentionsInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.ThreadingAssertions
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.WorkspaceEdit
import org.rri.ideals.server.LspPath
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.util.MiscUtil.distinctByKey
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.MiscUtil.with
import org.rri.ideals.server.util.TextUtil.textEditFromDocs
import java.util.Map
import java.util.stream.Collectors
import java.util.stream.Stream

@Service(Service.Level.PROJECT)
class CodeActionService(private val project: Project) {
    fun getCodeActions(range: Range, executorContext: ExecutorContext): List<CodeAction> {
        ThreadingAssertions.assertBackgroundThread()
        val file = executorContext.psiFile
        val path = LspPath.fromVirtualFile(file.virtualFile)

        val actionInfo = ReadAction.compute<IntentionsInfo, RuntimeException> {
            ShowIntentionsPass.getActionsToShow(
                executorContext.editor,
                file
            )
        }

        val intentionActions = Stream.of(
            actionInfo.intentionsToShow
        )
            .flatMap { obj: List<IntentionActionDescriptor> -> obj.stream() }
            .filter { it: IntentionActionDescriptor -> !ReadAction.compute<Boolean, RuntimeException> { it.action.text == "Inject language or reference" } }
            .map { it: IntentionActionDescriptor ->
                toCodeAction(
                    path,
                    range,
                    it,
                    CodeActionKind.Refactor
                )
            }

        val quickFixes = Stream.of(
            actionInfo.errorFixesToShow,
            actionInfo.inspectionFixesToShow
        )
            .flatMap { obj: List<IntentionActionDescriptor> -> obj.stream() }
            .map { it: IntentionActionDescriptor ->
                toCodeAction(
                    path,
                    range,
                    it,
                    CodeActionKind.QuickFix
                )
            }

        return Stream.concat<CodeAction>(quickFixes, intentionActions)
            .filter(distinctByKey { obj: CodeAction -> obj.title })
            .collect(Collectors.toList<CodeAction>())
    }

    fun applyCodeAction(
        actionData: ActionData,
        title: String,
        executorContext: ExecutorContext
    ): WorkspaceEdit {
        val result = WorkspaceEdit()
        val editor = executorContext.editor
        val psiFile = executorContext.psiFile
        val oldCopy = (psiFile.copy() as PsiFile)

        val actionInfo = ReadAction.compute<IntentionsInfo, RuntimeException> {
            ShowIntentionsPass.getActionsToShow(
                editor,
                psiFile
            )
        }

        val actionFound = Stream.of<List<IntentionActionDescriptor?>>(
            actionInfo.errorFixesToShow,
            actionInfo.inspectionFixesToShow,
            actionInfo.intentionsToShow
        )
            .flatMap<IntentionActionDescriptor?> { obj: List<IntentionActionDescriptor?> -> obj.stream() }
            .map(IntentionActionDescriptor::getAction)
            .filter { it: IntentionAction? -> ReadAction.compute<Boolean, RuntimeException> { it!!.text == title } }
            .findFirst()
            .orElse(null)

        if (actionFound == null) {
            LOG.warn("No action descriptor found: $title")
        } else {
            ApplicationManager.getApplication().invokeAndWait {
                CommandProcessor.getInstance().executeCommand(
                    project,
                    {
                        if (actionFound.startInWriteAction()) {
                            WriteAction.run<RuntimeException> {
                                actionFound.invoke(
                                    project,
                                    editor,
                                    psiFile
                                )
                            }
                        } else {
                            actionFound.invoke(project, editor, psiFile)
                        }
                    }, title, null
                )
            }

            val oldDoc = ReadAction.compute<Document?, RuntimeException> {
                getDocument(
                    oldCopy
                )
            }
            val newDoc = editor.document

            val edits = textEditFromDocs(
                oldDoc!!, newDoc
            )

            WriteCommandAction.runWriteCommandAction(project) {
                newDoc.setText(oldDoc.text)
                PsiDocumentManager.getInstance(project).commitDocument(newDoc)
            }

            if (!edits.isEmpty()) {
                result.changes = Map.of(actionData.uri, edits)
            }
        }

        return result
    }

    private fun toCodeAction(
        path: LspPath,
        range: Range,
        descriptor: IntentionActionDescriptor,
        kind: String
    ): CodeAction {
        return with(
            CodeAction(ReadAction.compute<String, RuntimeException> { descriptor.action.text })
        ) { ca: CodeAction ->
            ca.kind = kind
            ca.data = ActionData(path.toLspUri(), range)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(
            CodeActionService::class.java
        )
    }
}
