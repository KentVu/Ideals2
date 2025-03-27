package org.rri.ideals.server.hover

import com.intellij.lang.documentation.impl.documentationTargets
import com.intellij.openapi.application.ReadAction
import com.intellij.platform.backend.documentation.DocumentationData
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.impl.DocumentationRequest
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import io.github.furstenheim.CopyDown
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.rri.ideals.server.commands.ExecutorContext
import org.rri.ideals.server.commands.LspCommand
import java.util.Optional
import java.util.function.Supplier

class HoverCommand : LspCommand<Hover>() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "Hover call" }

    override val isCancellable: Boolean
        get() = false

    override val isRunInEdt: Boolean
        get() = false

    override fun execute(ctx: ExecutorContext): Hover {
        return ReadAction.compute<Hover, RuntimeException> {
            documentationTargets(ctx.psiFile, ctx.editor.caretModel.offset).stream()
                .findFirst()
                .flatMap { target: DocumentationTarget ->
                    val request =
                        DocumentationRequest(target.createPointer(), target.computePresentation())
                    Optional.ofNullable(
                        computeDocumentationBlocking(
                            request.targetPointer
                        )
                    )
                        .map { res: DocumentationData ->
                            val htmlToMarkdownConverter = CopyDown()
                            val markdown = htmlToMarkdownConverter.convert(res.html)
                            Hover(
                                MarkupContent(
                                    MarkupKind.MARKDOWN,
                                    markdown
                                )
                            )
                        }
                }.orElse(null)
        }
    }
}
