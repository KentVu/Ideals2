package org.rri.ideals.server.symbol

import com.intellij.ide.actions.ViewStructureAction
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.symbol.util.SymbolUtil.getSymbolKind
import org.rri.ideals.server.util.LspProgressIndicator
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.MiscUtil.offsetToPosition
import java.util.Optional

@Service(Service.Level.PROJECT)
class DocumentSymbolService(private val project: Project) {
    @Suppress("deprecation")
    fun computeDocumentSymbols(
        executorContext: ExecutorContext
    ): List<Either<SymbolInformation, DocumentSymbol>> {
        LOG.info("document symbol start")
        val psiFile = executorContext.psiFile
        val cancelChecker = checkNotNull(executorContext.cancelToken)
        return ProgressManager.getInstance()
            .runProcess<List<Either<SymbolInformation, DocumentSymbol>>>(
                {
                    val root = Optional.ofNullable(
                        FileEditorManager.getInstance(psiFile.project)
                            .getSelectedEditor(psiFile.virtualFile)
                    )
                        .map { fileEditor: FileEditor ->
                            this.getViewTreeElement(
                                fileEditor
                            )
                        }
                        .orElse(null)
                    if (root == null) {
                        return@runProcess listOf<Either<SymbolInformation, DocumentSymbol>>()
                    }
                    val document = checkNotNull(ReadAction.compute<Document?, RuntimeException> {
                        getDocument(
                            psiFile
                        )
                    })
                    val rootSymbol = processTree(root, psiFile, document)
                        ?: return@runProcess listOf<Either<SymbolInformation, DocumentSymbol>>()
                    rootSymbol.kind = SymbolKind.File
                    java.util.List.of(Either.forRight(rootSymbol))
                }, LspProgressIndicator(cancelChecker)
            )
    }

    private fun getViewTreeElement(fileEditor: FileEditor): StructureViewTreeElement? {
        val builder =
            ReadAction.compute<StructureViewBuilder?, RuntimeException> { fileEditor.structureViewBuilder }
        if (builder == null) {
            return null
        }
        val treeModel: StructureViewModel
        if (builder is TreeBasedStructureViewBuilder) {
            treeModel = builder.createStructureViewModel(EditorUtil.getEditorEx(fileEditor))
        } else {
            val structureView = builder.createStructureView(fileEditor, project)
            treeModel =
                ViewStructureAction.createStructureViewModel(project, fileEditor, structureView)
        }
        return treeModel.root
    }

    private fun processTree(
        root: TreeElement,
        psiFile: PsiFile,
        document: Document
    ): DocumentSymbol? {
        val documentSymbol = ReadAction.compute<DocumentSymbol, RuntimeException> {
            val curSymbol = DocumentSymbol()
            curSymbol.kind = getSymbolKind(root.presentation)
            if (root is StructureViewTreeElement) {
                val maybePsiElement = root.value
                curSymbol.name = root.getPresentation().presentableText
                if (maybePsiElement is PsiElement) {
                    if (maybePsiElement.containingFile.originalFile !== psiFile) {
                        // refers to another file
                        return@compute null
                    }
                    val ideaRange = maybePsiElement.textRange
                    curSymbol.range = Range(
                        offsetToPosition(
                            document,
                            ideaRange.startOffset
                        ),
                        offsetToPosition(
                            document,
                            ideaRange.endOffset
                        )
                    )

                    val ideaPickSelectionRange = TextRange(
                        maybePsiElement.textOffset,
                        maybePsiElement.textOffset
                    )
                    curSymbol.selectionRange = Range(
                        offsetToPosition(
                            document,
                            ideaPickSelectionRange.startOffset
                        ),
                        offsetToPosition(
                            document,
                            ideaPickSelectionRange.endOffset
                        )
                    )
                }
            }
            curSymbol
        }
        if (documentSymbol == null) {
            return null // if refers to another file
        }
        val children = ArrayList<DocumentSymbol>()
        for (child in ReadAction.compute<Array<TreeElement>, RuntimeException> { root.children }) {
            val childSymbol = processTree(child, psiFile, document)
            if (childSymbol != null) { // if not refers to another file
                children.add(childSymbol)
            }
        }
        documentSymbol.children = children
        return documentSymbol
    }

    companion object {
        private val LOG = Logger.getInstance(
            DocumentSymbolService::class.java
        )
    }
}
