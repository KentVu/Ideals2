package org.rri.ideals.server.util

import com.intellij.openapi.progress.EmptyProgressIndicatorBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.StandardProgressIndicator
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import kotlin.concurrent.Volatile

class LspProgressIndicator(private val cancelChecker: CancelChecker) :
    EmptyProgressIndicatorBase(), StandardProgressIndicator {
    @Volatile
    private var myIsCanceled = false


    override fun start() {
        super.start()
        myIsCanceled = false
    }

    override fun cancel() {
        myIsCanceled = true
        ProgressManager.canceled(this)
    }

    override fun isCanceled(): Boolean {
        return myIsCanceled || cancelChecker.isCanceled
    }
}
