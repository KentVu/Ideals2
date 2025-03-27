package org.rri.ideals.server.extensions

import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.commands.LspCommand
import java.util.function.Supplier

class ClassFileContentsCommand : LspCommand<String?>() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "experimental/classFileContents call" }

    override val isCancellable: Boolean
        get() = false

    override fun execute(ctx: ExecutorContext): String {
        return ctx.editor.document.text
    }
}
