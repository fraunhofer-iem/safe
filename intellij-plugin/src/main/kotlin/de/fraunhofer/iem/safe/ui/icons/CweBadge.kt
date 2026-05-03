package de.fraunhofer.iem.safe.ui.icons

import com.intellij.openapi.application.PathManager
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Renders a small orange rounded-rectangle PNG with the CWE number in white, intended
 * for inline use as an `<img>` in HTML rendered by `JEditorPane`. Each badge is cached
 * (per CWE number) on disk under the IDE's temp directory so it's generated at most
 * once per id per session.
 */
object CweBadge {
    private val orange = Color(0xed, 0x84, 0x36)

    private val cacheDir by lazy {
        File(PathManager.getTempPath(), "safe-cwe-badges").apply { mkdirs() }
    }
    private val urlCache = ConcurrentHashMap<String, String>()

    /**
     * Returns a `file://` URL for the badge of [cweId], or `null` when the id isn't a
     * `CWE-N` form. When [shortName] is supplied, it is rendered on the badge after the
     * id (e.g. "CWE-89 SQL Injection").
     */
    fun urlFor(cweId: String?, shortName: String? = null): String? {
        if (cweId.isNullOrBlank()) return null
        val numeric = cweId.removePrefix("CWE-").removePrefix("cwe-").trim()
            .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: return null
        val name = shortName?.takeIf { it.isNotBlank() }
        val cacheKey = if (name == null) numeric else "$numeric-${name.hashCode()}"
        urlCache[cacheKey]?.let { return it }
        val url = render(numeric, name) ?: return null
        urlCache[cacheKey] = url
        return url
    }

    private fun render(numeric: String, shortName: String?): String? = try {
        val text = if (shortName != null) "CWE-$numeric $shortName" else "CWE-$numeric"
        val font = Font(Font.SANS_SERIF, Font.BOLD, 11)

        // Measure the text first so the badge width hugs it with consistent padding.
        val measure = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        measure.font = font
        val fm = measure.fontMetrics
        val textWidth = fm.stringWidth(text)
        measure.dispose()

        val padX = 7
        val padY = 2
        val pillHeight = (fm.ascent + fm.descent + 2 * padY).coerceAtLeast(14)
        // Asymmetric transparent margin: more above the pill than below so the visible
        // content sits in the lower portion of the image canvas. JEditorPane's
        // `vertical-align: middle` then lands the pill on the text's visual mid-line
        // (CSS "middle" otherwise centers it slightly above the text).
        val topMargin = 6
        val bottomMargin = 0
        val height = pillHeight + topMargin + bottomMargin
        val width = (textWidth + 2 * padX).coerceAtLeast(pillHeight)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = orange
            // Fully rounded ends — arc = pillHeight makes a pill shape.
            g.fillRoundRect(0, topMargin, width, pillHeight, pillHeight, pillHeight)
            g.color = Color.WHITE
            g.font = font
            val textX = (width - textWidth) / 2
            val textY = topMargin + (pillHeight + fm.ascent - fm.descent) / 2
            g.drawString(text, textX, textY)
        } finally {
            g.dispose()
        }
        val safeName = shortName?.let { it.replace(Regex("[^A-Za-z0-9]"), "_") }
        val fileName = if (safeName == null) "cwe-$numeric.png" else "cwe-$numeric-$safeName.png"
        val file = File(cacheDir, fileName)
        ImageIO.write(image, "PNG", file)
        file.toURI().toString()
    } catch (ex: Exception) {
        null
    }
}
