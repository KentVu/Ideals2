package org.rri.ideals.server.completions.util

import com.intellij.openapi.util.TextRange
import java.util.SortedSet
import java.util.TreeSet

object TextEditRearranger {
    /**
     * VScode doesn't allow to change main TextEdit's range during resolve, but allows to change
     * its text and additional edits. Also, snippets are allowed only in main TextEdit. So solution
     * is to find text edits, that have intersecting ranges with range from text edit to snippets, and
     * merge them, as if they were a single text edit with range from main text edit. It is more
     * understandable in example:
     *
     *
     * Text = 1234567. You have the main TextEdit with range [2, 3]: 1|23|4567. Diff is equal to list
     * of TextEdits = [[1] -> "a", [3, 4] -> "_"]. And you have a caret ! after insert placed at 5.
     * Original text with marked ranges is: [1]|2[3|4]5!67. Text that we want to see after insert =
     * a2_5!67. We want to place text "...5!" into main TextEdit and also main range is intersecting
     * with TextEdit from diff.
     * Solution is to paste into main TextEdit's text "2_5$0" ($0 - is interpreted by lsp as a
     * caret) and delete diff's TextEdit. If we leave it as it is at this stage, we will get:
     * 12_5!4567. As you see, we need to delete text, that was in previous TextEdit from diff, and
     * text, that was between TextEdits and caret. We add this new delete TextEdit to additional
     * TextEdits: [4, 5] -> "". Also we need to add not intersected TextEdit to additional
     * TextEdits. Result text after this operation = a2_5!67, additional text edits = [[4, 5] ->
     * "", [1, 1] -> "a"]]
     * @param diffRangesAsOffsetsList a diff's TextEdits
     * @param replaceElementStartOffset the main TextEdit's range start
     * @param replaceElementEndOffset the main TextEdit's range end
     * @param originalText document's text *before* insert
     * @param snippetBounds snippetBound position *after* insert
     * @return Additional TextEdits and new main TextEdit
     */
    fun findOverlappingTextEditsInRangeFromMainTextEditToSnippetsAndMergeThem(
        diffRangesAsOffsetsList: List<TextEditWithOffsets>,
        replaceElementStartOffset: Int,
        replaceElementEndOffset: Int,
        originalText: String,
        snippetBounds: TextRange
    ): MergeEditsResult {
        val diffRangesAsOffsetsTreeSet = TreeSet(diffRangesAsOffsetsList)
        val additionalEdits = ArrayList<TextEditWithOffsets>()

        val leftSnippetsBound =
            findRangeOfTextEditWithSnippetBound(
                diffRangesAsOffsetsTreeSet,
                snippetBounds.startOffset
            ).startOffset
        val rightSnippetsBound =
            findRangeOfTextEditWithSnippetBound(
                diffRangesAsOffsetsTreeSet,
                snippetBounds.endOffset
            ).endOffset

        val collisionRangeStartOffset = Integer.min(
            leftSnippetsBound,
            replaceElementStartOffset
        )
        val collisionRangeEndOffset = Integer.max(
            rightSnippetsBound,
            replaceElementEndOffset
        )

        val editsToMergeRangesAsOffsets = findIntersectedEdits(
            collisionRangeStartOffset,
            collisionRangeEndOffset,
            diffRangesAsOffsetsTreeSet,
            additionalEdits
        )

        return MergeEditsResult(
            mergeTextEditsToOne(
                editsToMergeRangesAsOffsets,
                replaceElementStartOffset,
                replaceElementEndOffset,
                additionalEdits,
                originalText
            ),
            additionalEdits
        )
    }

    /**
     * Here we are merging intersected edits in range from main TextEdit to snippetBound
     * @param editsToMergeRangesAsOffsets intersected edits
     * @param replaceElementStartOffset main TextEdit's range start
     * @param replaceElementEndOffset main TextEdit's range end
     * @param additionalEdits additional edits list that will achieve new delete TextEdits
     * @param originalText text *before* insert
     * @return Text edit, that has a range = [replaceElementStartOffset, replaceElementEndOffset]
     * and a text, that contains all text that edits will insert as they were a single text edit.
     */
    private fun mergeTextEditsToOne(
        editsToMergeRangesAsOffsets: TreeSet<TextEditWithOffsets>,
        replaceElementStartOffset: Int,
        replaceElementEndOffset: Int,
        additionalEdits: ArrayList<TextEditWithOffsets>,
        originalText: String
    ): TextEditWithOffsets {
        val mergeRangeStartOffset = editsToMergeRangesAsOffsets.first().range.startOffset
        val mergeRangeEndOffset = editsToMergeRangesAsOffsets.last().range.endOffset
        val builder = StringBuilder()
        if (mergeRangeStartOffset > replaceElementStartOffset) {
            builder.append(
                originalText,
                replaceElementStartOffset,
                mergeRangeStartOffset
            )
        } else if (mergeRangeStartOffset != replaceElementStartOffset) {
            additionalEdits.add(
                TextEditWithOffsets(mergeRangeStartOffset, replaceElementStartOffset, "")
            )
        }
        var prevEndOffset = editsToMergeRangesAsOffsets.first().range.startOffset
        for (editToMerge in editsToMergeRangesAsOffsets) {
            builder.append(
                originalText,
                prevEndOffset,
                editToMerge.range.startOffset
            )

            prevEndOffset = editToMerge.range.endOffset

            builder.append(editToMerge.newText)
        }

        if (mergeRangeEndOffset < replaceElementEndOffset) {
            builder.append(
                originalText,
                mergeRangeEndOffset,
                replaceElementEndOffset
            )
        } else if (replaceElementEndOffset != mergeRangeEndOffset) {
            additionalEdits.add(
                TextEditWithOffsets(replaceElementEndOffset, mergeRangeEndOffset, "")
            )
        }
        return TextEditWithOffsets(
            replaceElementStartOffset,
            replaceElementEndOffset,
            builder.toString()
        )
    }

    /**
     * Find intersected TextEdits with range [collisionRangeStartOffset, collisionRangeEndOffset]
     * @param collisionRangeStartOffset min(main TextEdit's start, snippetBound)
     * @param collisionRangeEndOffset max(main TextEdit's end, snippetBound)
     * @param diffRangesAsOffsetsTreeSet sorted diff TextEdits
     * @param uselessEdits aka additional TextEdits, that will achieve diff's TextEdits, that are
     * not intersecting with collision range
     * @return intersected TextEdits
     */
    private fun findIntersectedEdits(
        collisionRangeStartOffset: Int,
        collisionRangeEndOffset: Int,
        diffRangesAsOffsetsTreeSet: TreeSet<TextEditWithOffsets>,
        uselessEdits: MutableList<TextEditWithOffsets>
    ): TreeSet<TextEditWithOffsets> {
        val first = TextEditWithOffsets(
            collisionRangeStartOffset,
            collisionRangeStartOffset, ""
        )
        val last = TextEditWithOffsets(collisionRangeEndOffset, collisionRangeEndOffset, "")
        val floor = diffRangesAsOffsetsTreeSet.floor(first)
        val ceil = diffRangesAsOffsetsTreeSet.ceiling(last)
        val editsToMergeRangesAsOffsets =
            TreeSet(diffRangesAsOffsetsTreeSet.subSet(first, true, last, true))

        if (floor != null) {
            val isLowerBoundInclusive = floor.range.endOffset >= collisionRangeStartOffset
            if (isLowerBoundInclusive) {
                editsToMergeRangesAsOffsets.add(floor)
            }
            uselessEdits.addAll(diffRangesAsOffsetsTreeSet.headSet(floor, !isLowerBoundInclusive))
        }

        if (ceil != null) {
            val isUpperBoundInclusive = ceil.range.startOffset <= collisionRangeEndOffset
            if (isUpperBoundInclusive) {
                editsToMergeRangesAsOffsets.add(ceil)
            }
            uselessEdits.addAll(diffRangesAsOffsetsTreeSet.tailSet(ceil, !isUpperBoundInclusive))
        }
        return editsToMergeRangesAsOffsets
    }

    /**
     * Finds range of edit, inside which the snippet bound is positioned.
     * @param sortedDiffRanges mutable set with text edits sorted by position
     * @param snippetBound absolute snippetBound offset
     * @return the found or created text edit with marked text
     */
    private fun findRangeOfTextEditWithSnippetBound(
        sortedDiffRanges: SortedSet<TextEditWithOffsets>,
        snippetBound: Int
    ): TextRange {
        var sub: Int
        var prevEnd = 0
        var currentRelativeBoundOffset = snippetBound

        var rangeWithSnippetBound: TextRange? = null
        for (editWithOffsets in sortedDiffRanges) {
            sub = (editWithOffsets.range.startOffset - prevEnd)
            if (currentRelativeBoundOffset < sub) { // not found
                val boundOffsetInOriginalDoc = prevEnd + currentRelativeBoundOffset
                rangeWithSnippetBound = TextRange(
                    boundOffsetInOriginalDoc, boundOffsetInOriginalDoc
                )
                break
            }

            currentRelativeBoundOffset -= sub

            sub = editWithOffsets.newText.length
            if (currentRelativeBoundOffset <= sub) {
                rangeWithSnippetBound = editWithOffsets.range
                break
            }

            currentRelativeBoundOffset -= sub
            prevEnd = editWithOffsets.range.endOffset
        }

        if (rangeWithSnippetBound == null) {  // still not found
            val boundOffsetInOriginalDoc = prevEnd + currentRelativeBoundOffset
            rangeWithSnippetBound = TextRange(boundOffsetInOriginalDoc, boundOffsetInOriginalDoc)
        }

        return rangeWithSnippetBound
    }
}
