package org.rri.ideals.server.completions

import com.intellij.codeInsight.completion.CompletionProcess
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.util.AbstractProgressIndicatorExBase
import com.intellij.openapi.util.Disposer
import com.intellij.util.ObjectUtils
import java.util.function.Supplier

/* todo
   Find an alternative way to find Indicator from project for completion
   This process is needed for creation Completion Parameters and insertDummyIdentifier call.
*/
class VoidCompletionProcess : AbstractProgressIndicatorExBase(), Disposable,
    CompletionProcess {
    override fun isAutopopupCompletion(): Boolean {
        return false
    }

    // todo check that we don't need this lock
    private val myLock = ObjectUtils.sentinel("VoidCompletionProcess")

    override fun dispose() {
    }

    fun registerChildDisposable(child: Supplier<Disposable?>) {
        synchronized(myLock) {
            // Idea developer says: "avoid registering stuff on an indicator being disposed concurrently"
            checkCanceled()
            Disposer.register(this, child.get()!!)
        }
    }
}
