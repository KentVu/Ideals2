package org.rri.ideals.server.signature

import com.intellij.codeInsight.hint.ParameterInfoControllerBase
import com.intellij.codeInsight.hint.ParameterInfoListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.LinkedBlockingQueue

class MyParameterInfoListener : ParameterInfoListener {
    @JvmField
    val queue: LinkedBlockingQueue<ParameterInfoControllerBase.Model> = LinkedBlockingQueue(1)
    override fun hintUpdated(result: ParameterInfoControllerBase.Model) {
        LOG.info("parameter info set")
        assert(queue.isEmpty())
        queue.add(result)
    }


    override fun hintHidden(project: Project) {
        LOG.info("parameter info delete")
        queue.poll()
    }

    companion object {
        private val LOG = Logger.getInstance(
            MyParameterInfoListener::class.java
        )
    }
}
