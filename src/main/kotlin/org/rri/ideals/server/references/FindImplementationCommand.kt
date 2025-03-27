package org.rri.ideals.server.references

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.navigation.ImplementationSearcher
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.rri.ideals.server.util.MiscUtil.streamOf
import java.util.function.Supplier
import java.util.stream.Stream

class FindImplementationCommand : FindDefinitionCommandBase() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "Implementation call" }

    override fun findDefinitions(editor: Editor, offset: Int): Stream<PsiElement> {
        val element =
            TargetElementUtil.findTargetElement(editor, TargetElementUtil.getInstance().allAccepted)
        val onRef = ReadAction.compute<Boolean, RuntimeException> {
            TargetElementUtil.getInstance().findTargetElement(
                editor,
                TargetElementUtil.getInstance().definitionSearchFlags
                        and Integer.reverse(TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED or TargetElementUtil.LOOKUP_ITEM_ACCEPTED),
                offset
            ) == null
        }
        val shouldIncludeSelf = ReadAction.compute<Boolean, RuntimeException> {
            element == null || TargetElementUtil.getInstance()
                .includeSelfInGotoImplementation(element)
        }
        val includeSelf = onRef && shouldIncludeSelf
        return streamOf(
            ImplementationSearcher().searchImplementations(
                element,
                editor,
                includeSelf,
                onRef
            )
        )
    }
}
