package de.fraunhofer.iem.safe.ui.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** SVG icons used both in the SAFE flows tree and as gutter icons in the editor. */
object TaintFlowIcons {
    private val cls = TaintFlowIcons::class.java
    val SOURCE: Icon = IconLoader.getIcon("/icons/flow_source.svg", cls)
    val SINK: Icon = IconLoader.getIcon("/icons/flow_sink.svg", cls)
    val CALL: Icon = IconLoader.getIcon("/icons/flow_call.svg", cls)
    val PROPAGATOR: Icon = IconLoader.getIcon("/icons/flow_propagator.svg", cls)
    val SANITIZER: Icon = IconLoader.getIcon("/icons/flow_sanitizer.svg", cls)
}
