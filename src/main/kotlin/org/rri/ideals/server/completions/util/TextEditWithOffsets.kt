package org.rri.ideals.server.completions.util

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.rri.ideals.server.util.MiscUtil.offsetToPosition
import org.rri.ideals.server.util.MiscUtil.positionToOffset
import java.util.Objects

class TextEditWithOffsets(@JvmField val range: TextRange, @JvmField val newText: String) :
    Comparable<TextEditWithOffsets> {
    constructor(start: Int, end: Int, newText: String) : this(TextRange(start, end), newText)

    constructor(textEdit: TextEdit, document: Document) : this(
        positionToOffset(document, textEdit.range.start),
        positionToOffset(document, textEdit.range.end), textEdit.newText
    )

    fun toTextEdit(document: Document): TextEdit {
        return TextEdit(
            Range(
                offsetToPosition(document, range.startOffset),
                offsetToPosition(document, range.endOffset)
            ),
            newText
        )
    }

    override fun compareTo(otherTextEditWithOffsets: TextEditWithOffsets): Int {
        val res = range.startOffset - otherTextEditWithOffsets.range.startOffset
        if (res == 0) {
            return range.endOffset - otherTextEditWithOffsets.range.endOffset
        }
        return res
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) return true
        if (o == null || javaClass != o.javaClass) return false
        val that = o as TextEditWithOffsets
        return range == that.range && newText == that.newText
    }

    override fun hashCode(): Int {
        return Objects.hash(range, newText)
    }

    override fun toString(): String {
        return "range: $range, newText: $newText"
    }
}
