package org.rri.ideals.server.references

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import java.util.function.Supplier
import java.util.stream.Stream

internal class FindDefinitionCommand : FindDefinitionCommandBase() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "Definition call" }

    protected override fun findDefinitions(editor: Editor, offset: Int): Stream<PsiElement> {
        val reference = TargetElementUtil.findReference(editor, offset)
        val flags = TargetElementUtil.getInstance().definitionSearchFlags
        val targetElement = TargetElementUtil.getInstance().findTargetElement(editor, flags, offset)
        return if (targetElement != null)
            Stream.of(targetElement)
        else
            if (reference != null)
                TargetElementUtil.getInstance().getTargetCandidates(reference).stream()
            else
                Stream.empty()
    }
}
