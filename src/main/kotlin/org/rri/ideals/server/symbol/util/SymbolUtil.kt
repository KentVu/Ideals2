package org.rri.ideals.server.symbol.util

import com.intellij.icons.AllIcons
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.ui.DeferredIcon
import com.intellij.ui.IconManager
import com.intellij.ui.PlatformIcons
import org.eclipse.lsp4j.SymbolKind
import org.rri.ideals.server.completions.util.IconUtil.compareIcons

object SymbolUtil {
    fun getSymbolKind(presentation: ItemPresentation): SymbolKind {
        val parent = Disposer.newDisposable()
        try {
            // allow icon loading
            Registry.get("psi.deferIconLoading").setValue(false, parent)

            var icon = presentation.getIcon(false)

            var kind = SymbolKind.Object
            val iconManager: IconManager = IconManager.getInstance()
            if (icon == null) {
                return SymbolKind.Object
            }
            if (icon is DeferredIcon) {
                icon = icon.baseIcon
            }
            if (compareIcons(icon, AllIcons.Nodes.Method, PlatformIcons.Method) ||
                compareIcons(icon, AllIcons.Nodes.AbstractMethod, PlatformIcons.AbstractMethod)
            ) {
                kind = SymbolKind.Method
            } else if (compareIcons(icon, AllIcons.Nodes.Module, "nodes/Module.svg")
                || compareIcons(icon, AllIcons.Nodes.IdeaModule, PlatformIcons.IdeaModule)
                || compareIcons(icon, AllIcons.Nodes.JavaModule, PlatformIcons.JavaModule)
                || compareIcons(icon, AllIcons.Nodes.ModuleGroup, "nodes/moduleGroup.svg")
            ) {
                kind = SymbolKind.Module
            } else if (compareIcons(icon, AllIcons.Nodes.Function, PlatformIcons.Function)) {
                kind = SymbolKind.Function
            } else if (compareIcons(icon, AllIcons.Nodes.Interface, PlatformIcons.Interface) ||
                compareIcons(
                    icon,
                    iconManager.tooltipOnlyIfComposite(AllIcons.Nodes.Interface),
                    PlatformIcons.Interface
                )
            ) {
                kind = SymbolKind.Interface
            } else if (compareIcons(icon, AllIcons.Nodes.Type, "nodes/type.svg")) {
                kind = SymbolKind.TypeParameter
            } else if (compareIcons(icon, AllIcons.Nodes.Property, PlatformIcons.Property)) {
                kind = SymbolKind.Property
            } else if (compareIcons(icon, AllIcons.FileTypes.Any_type, "fileTypes/any_type.svg")) {
                kind = SymbolKind.File
            } else if (compareIcons(icon, AllIcons.Nodes.Enum, PlatformIcons.Enum)) {
                kind = SymbolKind.Enum
            } else if (compareIcons(icon, AllIcons.Nodes.Variable, PlatformIcons.Variable) ||
                compareIcons(icon, AllIcons.Nodes.Parameter, PlatformIcons.Parameter) ||
                compareIcons(icon, AllIcons.Nodes.NewParameter, "nodes/newParameter.svg")
            ) {
                kind = SymbolKind.Variable
            } else if (compareIcons(icon, AllIcons.Nodes.Constant, "nodes/constant.svg")) {
                kind = SymbolKind.Constant
            } else if (compareIcons(icon, AllIcons.Nodes.Class, PlatformIcons.Class) ||
                compareIcons(
                    icon,
                    iconManager.tooltipOnlyIfComposite(AllIcons.Nodes.Class), PlatformIcons.Class
                ) ||
                compareIcons(icon, AllIcons.Nodes.Class, PlatformIcons.Class) ||
                compareIcons(icon, AllIcons.Nodes.AbstractClass, PlatformIcons.AbstractClass)
            ) {
                kind = SymbolKind.Class
            } else if (compareIcons(icon, AllIcons.Nodes.Field, PlatformIcons.Field)) {
                kind = SymbolKind.Field
            }
            return kind
        } finally {
            Disposer.dispose(parent)
        }
    }
}
