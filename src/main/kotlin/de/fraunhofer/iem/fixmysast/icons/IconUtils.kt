package de.fraunhofer.iem.fixmysast.icons

import com.intellij.openapi.util.IconLoader
import de.fraunhofer.iem.fixmysast.analysis.SrmFinder
import de.fraunhofer.iem.fixmysast.data.Constants
import javax.swing.Icon

/***
 * Retrieves gutter icons.
 ***/

object IconUtils {
    fun getIcon(cat: String) : Icon {
        return when {
            cat in Constants.CWE_CAT -> PluginIcons.CWE
            cat.equals(Constants.SRM_CAT_SOURCE, ignoreCase = true) -> PluginIcons.SOURCE
            cat.equals(Constants.SRM_CAT_SINK, ignoreCase = true) -> PluginIcons.SINK
            cat.equals(Constants.SRM_CAT_SANITIZER, ignoreCase = true) -> PluginIcons.SANITIZER
            cat.equals(Constants.SRM_CAT_AUTHENTICATION_UNSAFE, ignoreCase = true) -> PluginIcons.AUTHENTICATION_UNSAFE
            cat.equals(Constants.SRM_CAT_AUTHENTICATION_SAFE, ignoreCase = true) -> PluginIcons.AUTHENTICATION_SAFE
            cat.equals(Constants.SRM_CAT_AUTHENTICATION_NOCHANGE, ignoreCase = true) -> PluginIcons.AUTHENTICATION_NOCHANGE
            else -> PluginIcons.DEFAULT
        }
    }
    fun getMethodSRMGutterIcon(methodSignature: String): Icon {
        val categories = SrmFinder.getSrmAndCweCategory(methodSignature)
        if (categories.size ==1){
            return getIcon(categories.first())
        } else {
            val catList = categories.map { it.take(3).lowercase() }.distinct().sortedDescending()

            val joinedCat = catList.joinToString("_")
            val path = Constants.ICON_PATH.format(joinedCat)

            return try {
                IconLoader.findIcon(path, IconUtils::class.java) ?: PluginIcons.DEFAULT
            } catch (e: Exception) {
                PluginIcons.DEFAULT
            }
        }
    }

}