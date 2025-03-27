package org.rri.ideals.server.codeactions

import org.eclipse.lsp4j.Range
import java.util.Objects

class ActionData internal constructor(// used via reflection
    @set:Suppress("unused") var uri: String, var range: Range
) {
    override fun equals(obj: Any?): Boolean {
        if (obj === this) return true
        if (obj == null || obj.javaClass != this.javaClass) return false
        val that = obj as ActionData
        return this.uri == that.uri &&
                this.range == that.range
    }

    override fun hashCode(): Int {
        return Objects.hash(uri, range)
    }

    override fun toString(): String {
        return "ActionData[" +
                "uri=" + uri + ", " +
                "range=" + range + ']'
    }
}
