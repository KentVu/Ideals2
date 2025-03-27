package org.rri.ideals.server.references

import com.intellij.codeInsight.navigation.actions.GotoTypeDeclarationAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.rri.ideals.server.util.MiscUtil.streamOf
import java.util.function.Supplier
import java.util.stream.Stream

class FindTypeDefinitionCommand : FindDefinitionCommandBase() {
    override val messageSupplier: Supplier<String>
        get() = Supplier { "TypeDefinition call" }

    override fun findDefinitions(editor: Editor, offset: Int): Stream<PsiElement> {
        return streamOf(GotoTypeDeclarationAction.findSymbolTypes(editor, offset))
    }
}
