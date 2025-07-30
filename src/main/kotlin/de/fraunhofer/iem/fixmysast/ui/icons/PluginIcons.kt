package de.fraunhofer.iem.fixmysast.ui.icons

import com.intellij.openapi.util.IconLoader
import de.fraunhofer.iem.fixmysast.PluginBundle
import javax.swing.Icon

object PluginIcons {
    @JvmField
    val SOURCE: Icon =
        IconLoader.getIcon(PluginBundle.lazy("fixmysast.configuration.ICON_PATH").get().format("sou"), javaClass)
    @JvmField
    val SANITIZER: Icon =
        IconLoader.getIcon(PluginBundle.lazy("fixmysast.configuration.ICON_PATH").get().format("san"), javaClass)
    @JvmField
    val SINK: Icon =
        IconLoader.getIcon(PluginBundle.lazy("fixmysast.configuration.ICON_PATH").get().format("sin"), javaClass)
    @JvmField
    val AUTHENTICATION_SAFE: Icon =
        IconLoader.getIcon(PluginBundle.lazy("fixmysast.configuration.ICON_PATH").get().format("auth_safe"), javaClass)
    @JvmField
    val AUTHENTICATION_UNSAFE: Icon = IconLoader.getIcon(
        PluginBundle.lazy("fixmysast.configuration.ICON_PATH").get().format("auth_unsafe"),
        javaClass
    )
    @JvmField
    val AUTHENTICATION_NOCHANGE: Icon =
        IconLoader.getIcon(PluginBundle.lazy("fixmysast.configuration.ICON_PATH").get().format("auth_no"), javaClass)
    @JvmField
    val CWE: Icon =
        IconLoader.getIcon(PluginBundle.lazy("fixmysast.configuration.ICON_PATH").get().format("cwe"), javaClass)

    @JvmField
    val DEFAULT: Icon = IconLoader.getIcon(
        PluginBundle.lazy("fixmysast.configuration.ICON_PATH").get().format("default.png"),
        javaClass
    )
}