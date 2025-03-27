package org.rri.ideals.server.rename

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.Segment
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.rri.ideals.server.LspPath
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.commands.LspCommand
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.MiscUtil.offsetToPosition
import org.rri.ideals.server.util.MiscUtil.psiElementToLocation
import java.util.Arrays
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Collectors
import java.util.stream.Stream

class RenameCommand(private val newName: String) : LspCommand<WorkspaceEdit>() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "Rename call" }

    override val isCancellable: Boolean
        get() = false

    override fun execute(ctx: ExecutorContext): WorkspaceEdit {
        val file = ctx.psiFile
        val editor = ctx.editor
        val elementRef = Ref<PsiElement?>()
        var elementToRename =
            TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)

        if (elementToRename != null) {
            val processor = RenamePsiElementProcessor.forElement(elementToRename)
            val newElementToRename = processor.substituteElementToRename(elementToRename, editor)
            if (newElementToRename != null) {
                elementToRename = newElementToRename
            }
        }
        elementRef.set(elementToRename)

        if (elementRef.get() == null) {
            return WorkspaceEdit()
        }

        val elemToName = LinkedHashMap<PsiElement?, String>()
        elemToName[elementRef.get()] = newName
        val renamer = RenameProcessor(
            file.project,
            elementRef.get()!!, newName, false, false
        )
        renamer.prepareRenaming(elementRef.get()!!, newName, elemToName)
        elemToName.forEach { (element: PsiElement?, newName: String) ->
            renamer.addElement(
                element!!, newName
            )
        }

        val usageEdits = Arrays.stream(renamer.findUsages())
            .filter { usage: UsageInfo -> !usage.isNonCodeUsage }
            .map { usageInfo: UsageInfo -> Pair(usageInfoToLocation(usageInfo), newName) }

        val targetEdits = Arrays.stream(elemToName.keys.toTypedArray<PsiElement?>())
            .map { elem: PsiElement? ->
                Pair(
                    psiElementToLocation(elem),
                    elemToName[elem]
                )
            }

        val checkSet = HashSet<Location>()
        val textDocumentEdits = Stream.concat(targetEdits, usageEdits)
            .filter { pair: Pair<Location?, String?> ->
                val loc = pair.getFirst()
                loc != null && checkSet.add(loc)
            }
            .collect(
                Collectors.groupingBy(
                    { pair: Pair<Location?, String?> ->
                        pair.getFirst()!!.uri
                    },
                    Collectors.mapping(
                        { pair ->
                            Pair(
                                pair.getFirst()!!.range, pair.getSecond()!!
                            )
                        }, Collectors.toList()
                    )
                )
            )
            .entries.stream()
            .map { this.convertEntry(it) }
            .toList()

        return WorkspaceEdit(textDocumentEdits)
    }

    private fun convertEntry(
        entry: Map.Entry<String, List<Pair<Range, String>>>
    ): Either<TextDocumentEdit, ResourceOperation> {
        return Either.forLeft(
            TextDocumentEdit(
                VersionedTextDocumentIdentifier(entry.key, 1),
                entry.value.stream().map { pair: Pair<Range, String> ->
                    TextEdit(
                        pair.getFirst(),
                        pair.getSecond()
                    )
                }.toList()
            )
        )
    }

    companion object {
        private fun usageInfoToLocation(info: UsageInfo): Location? {
            val psiFile = info.file
            val segment = info.segment
            if (psiFile == null || segment == null) {
                return null
            }
            val uri = LspPath.fromVirtualFile(psiFile.virtualFile).toLspUri()
            val doc = getDocument(psiFile) ?: return null
            return Location(uri, segmentToRange(doc, segment))
        }

        private fun segmentToRange(doc: Document, segment: Segment): Range {
            return Range(
                offsetToPosition(doc, segment.startOffset),
                offsetToPosition(doc, segment.endOffset)
            )
        }
    }
}
