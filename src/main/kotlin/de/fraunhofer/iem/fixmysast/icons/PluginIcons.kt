package de.fraunhofer.iem.fixmysast.icons

import com.intellij.openapi.util.IconLoader.getIcon
import de.fraunhofer.iem.fixmysast.data.Constants
import javax.swing.Icon

object PluginIcons {
    @JvmField
    val SOURCE: Icon = getIcon(Constants.ICON_PATH.format("sou"), javaClass)
    @JvmField
    val SANITIZER: Icon = getIcon(Constants.ICON_PATH.format("san"), javaClass)
    @JvmField
    val SINK: Icon = getIcon(Constants.ICON_PATH.format("sin"), javaClass)
    @JvmField
    val AUTHENTICATION_SAFE: Icon = getIcon(Constants.ICON_PATH.format("auth_safe"), javaClass)
    @JvmField
    val AUTHENTICATION_UNSAFE: Icon = getIcon(Constants.ICON_PATH.format("auth_unsafe"), javaClass)
    @JvmField
    val AUTHENTICATION_NOCHANGE: Icon = getIcon(Constants.ICON_PATH.format("auth_no"), javaClass)
    @JvmField
    val CWE: Icon = getIcon(Constants.ICON_PATH.format("cwe"), javaClass)
    
    @JvmField
    val DEFAULT: Icon = getIcon(Constants.ICON_PATH.format("default.png"), javaClass)
}