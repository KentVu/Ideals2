package org.rri.ideals.server.completions.util

@JvmRecord
data class MergeEditsResult(
    val mainEdit: TextEditWithOffsets,
    val additionalEdits: List<TextEditWithOffsets>
)
