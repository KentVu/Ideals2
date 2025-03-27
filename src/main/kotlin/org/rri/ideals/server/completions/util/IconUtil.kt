package org.rri.ideals.server.completions.util

import com.intellij.openapi.util.DummyIcon
import com.intellij.ui.PlatformIcons
import com.intellij.ui.icons.CompositeIcon
import javax.swing.Icon

object IconUtil {
    fun compareIcons(elementIcon: Icon, standardIcon: Icon, platformIcon: PlatformIcons): Boolean {
        return compareIcons(elementIcon, standardIcon, platformIcon.toString())
    }

    fun compareIcons(elementIcon: Icon, standardIcon: Icon, iconPath: String): Boolean {
        // in all cases the first icon in CompositeIcons is actually the main icon
        var elementIcon = elementIcon
        while (elementIcon is CompositeIcon) {
            if (elementIcon.iconCount == 0) {
                break
            }

            elementIcon = elementIcon.getIcon(0)!!
        }

        return elementIcon != null &&
                (elementIcon == standardIcon ||
                        ((elementIcon is DummyIcon) && iconPath == elementIcon.originalPath))
    }
}
