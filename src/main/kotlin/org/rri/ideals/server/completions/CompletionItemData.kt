package org.rri.ideals.server.completions

import java.util.Objects

internal  // fields are set via reflection
class CompletionItemData(val completionDataVersion: Int, val lookupElementIndex: Int) {
    override fun equals(obj: Any?): Boolean {
        if (obj === this) return true
        if (obj == null || obj.javaClass != this.javaClass) return false
        val that = obj as CompletionItemData
        return this.completionDataVersion == that.completionDataVersion &&
                this.lookupElementIndex == that.lookupElementIndex
    }

    override fun hashCode(): Int {
        return Objects.hash(completionDataVersion, lookupElementIndex)
    }

    override fun toString(): String {
        return "CompletionResolveData[" +
                "completionDataVersion=" + completionDataVersion + ", " +
                "lookupElementIndex=" + lookupElementIndex + ']'
    }
}
