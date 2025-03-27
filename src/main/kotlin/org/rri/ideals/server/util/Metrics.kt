package org.rri.ideals.server.util

import com.intellij.openapi.diagnostic.Logger
import org.rri.ideals.server.util.MiscUtil.wrap
import java.util.function.Supplier

object Metrics {
    private val LOG = Logger.getInstance(
        Metrics::class.java
    )

    fun run(blockNameSupplier: Supplier<String>, block: Runnable) {
        call(
            blockNameSupplier,
            Supplier<Void?> {
                block.run()
                null
            })
    }

    fun <T> call(blockNameSupplier: Supplier<String>, block: Supplier<T>): T {
        if (!LOG.isDebugEnabled) {
            return block.get()
        }

        var prefix = blockNameSupplier.get()
        LOG.debug("$prefix: started")
        val start = System.nanoTime()
        var thrown: Throwable? = null
        try {
            val result = block.get()
            prefix += ": took "
            return result
        } catch (e: Exception) {
            prefix += ": exceptionally took "
            thrown = e
            throw wrap(e)
        } finally {
            val end = System.nanoTime()
            LOG.debug(prefix + ((end - start) / 1000000) + " ms", thrown)
        }
    }
}
