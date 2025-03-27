package org.rri.ideals.server.util

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.DiffFragment
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.MiscUtil.offsetToPosition
import org.rri.ideals.server.util.MiscUtil.positionToOffset
import java.util.function.Consumer
import java.util.stream.Collectors

object TextUtil {
    fun toTextRange(doc: Document, range: Range): TextRange {
        return TextRange(
            positionToOffset(doc, range.start),
            positionToOffset(doc, range.end)
        )
    }

    fun differenceAfterAction(
        psiFile: PsiFile,
        action: Consumer<PsiFile>
    ): List<TextEdit> {
        val copy = getCopyByFileText(psiFile)
        action.accept(copy)

        val oldDoc = checkNotNull(getDocument(psiFile))
        val newDoc = checkNotNull(getDocument(copy))
        return textEditFromDocs(oldDoc, newDoc)
    }

    private fun diff(oldText: String, newText: String): List<DiffFragment> {
        var indicator = ProgressManager.getInstance().progressIndicator
        if (indicator == null) {
            indicator = DumbProgressIndicator.INSTANCE
        }

        return ComparisonManager.getInstance().compareChars(
            oldText, newText, ComparisonPolicy.DEFAULT,
            indicator!!
        )
    }

    fun textEditFromDocs(oldDoc: Document, newDoc: Document): List<TextEdit> {
        val changes = diff(oldDoc.text, newDoc.text)
        return changes.stream().map { diffFragment: DiffFragment ->
            val start = offsetToPosition(oldDoc, diffFragment.startOffset1)
            val end = offsetToPosition(oldDoc, diffFragment.endOffset1)
            val text = newDoc.getText(TextRange(diffFragment.startOffset2, diffFragment.endOffset2))
            TextEdit(Range(start, end), text)
        }.collect(Collectors.toList())
    }

    private fun getCopyByFileText(psiFile: PsiFile): PsiFile {
        val manager = PsiDocumentManager.getInstance(psiFile.project)
        val doc = checkNotNull(getDocument(psiFile))
        assert(manager.isCommitted(doc))
        return PsiFileFactory.getInstance(psiFile.project).createFileFromText(
            "copy",
            psiFile.language,
            doc.text,
            true,
            true,
            true,
            psiFile.virtualFile
        )
    }
}
