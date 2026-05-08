package de.fraunhofer.iem.safe.blue.ui.icons

import com.intellij.openapi.util.IconLoader
import de.fraunhofer.iem.safe.blue.PluginBundle
import de.fraunhofer.iem.safe.blue.ui.srm.SrmFinder
import javax.swing.Icon

/***
 * Retrieves gutter icons.
 ***/

object IconUtils {
    fun getIcon(cat: String) : Icon {
        return when {
            cat in PluginBundle.getMessage("safe.categories.CWE").split(",") -> PluginIcons.CWE
            cat.equals(PluginBundle.getMessage("safe.categories.SOURCE"), ignoreCase = true) -> PluginIcons.SOURCE
            cat.equals(PluginBundle.getMessage("safe.categories.SINK"), ignoreCase = true) -> PluginIcons.SINK
            cat.equals(PluginBundle.getMessage("safe.categories.PROPAGATOR"), ignoreCase = true) -> PluginIcons.PROPAGATOR
            cat.equals(PluginBundle.getMessage("safe.categories.SANITIZER"), ignoreCase = true) -> PluginIcons.SANITIZER
            cat.equals(PluginBundle.getMessage("safe.categories.AUTHENTICATION_UNSAFE"), ignoreCase = true) -> PluginIcons.AUTHENTICATION_UNSAFE
            cat.equals(PluginBundle.getMessage("safe.categories.AUTHENTICATION_SAFE"), ignoreCase = true) -> PluginIcons.AUTHENTICATION_SAFE
            cat.equals(PluginBundle.getMessage("safe.categories.AUTHENTICATION_NOCHANGE"), ignoreCase = true) -> PluginIcons.AUTHENTICATION_NOCHANGE
            else -> PluginIcons.DEFAULT
        }
    }
    fun getSRMGutterIcon(methodSignature: String): Icon {
        val categories = SrmFinder.getSrmAndCweCategory(methodSignature)
        if (categories.size ==1){
            return getIcon(categories.first())
        } else {
            val catList = categories.map { it.take(3).lowercase() }.distinct().sortedDescending()

            val joinedCat = catList.joinToString("_")
            val path = PluginBundle.lazy("safe.configuration.ICON_PATH").get().format(joinedCat)

            return try {
                IconLoader.findIcon(path, IconUtils::class.java) ?: PluginIcons.DEFAULT
            } catch (e: Exception) {
                PluginIcons.DEFAULT
            }
        }
    }

}