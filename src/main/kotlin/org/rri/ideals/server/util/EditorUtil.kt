package org.rri.ideals.server.util

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.Position
import org.rri.ideals.server.util.MiscUtil.getDocument
import org.rri.ideals.server.util.MiscUtil.wrap
import java.util.function.Consumer
import java.util.function.Function

object EditorUtil {
    fun createEditor(
        context: Disposable,
        file: PsiFile,
        position: Position
    ): Editor {
        val doc = getDocument(file)
        val editorFactory = EditorFactory.getInstance()

        checkNotNull(doc)
        val created = editorFactory.createEditor(doc, file.project)
        created.caretModel.moveToLogicalPosition(LogicalPosition(position.line, position.character))

        Disposer.register(context) {
            if (!created.isDisposed) {
                editorFactory.releaseEditor(created)
            }
        }

        return created
    }


    fun withEditor(
        context: Disposable,
        file: PsiFile,
        position: Position,
        callback: Consumer<Editor?>
    ) {
        computeWithEditor<Any?>(
            context, file, position
        ) { editor: Editor? ->
            callback.accept(editor)
            null
        }
    }

    fun <T> computeWithEditor(
        context: Disposable,
        file: PsiFile,
        position: Position,
        callback: Function<Editor?, T>
    ): T {
        val editor = createEditor(context, file, position)

        try {
            return callback.apply(editor)
        } catch (e: Exception) {
            throw wrap(e)
        } finally {
            Disposer.dispose(context)
        }
    }

    fun findTargetElement(editor: Editor): PsiElement? {
        return TargetElementUtil.findTargetElement(
            editor,
            TargetElementUtil.getInstance().allAccepted
        )
    }
}
