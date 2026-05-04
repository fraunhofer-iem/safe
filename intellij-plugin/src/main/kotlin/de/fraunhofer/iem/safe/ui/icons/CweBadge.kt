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
 * for inline use as an `<img>` in HTML rendered by `JEditorPane`. The image is rasterised
 * at [SCALE]× the logical size so HiDPI / Retina displays show crisp text — the HTML
 * snippet sets `width`/`height` to the *logical* size, leaving the extra pixels as
 * detail at native resolution. Each badge is cached on disk so it's generated at most
 * once per id per session.
 */
object CweBadge {
    /** Render at 2× physical pixels per logical pixel. Bumping past 2 is rarely worth the file size. */
    private const val SCALE = 2

    private val orange = Color(0xed, 0x84, 0x36)

    private val cacheDir by lazy {
        File(PathManager.getTempPath(), "safe-cwe-badges").apply { mkdirs() }
    }

    /** Cache the full HTML snippet — width/height attributes don't change once rendered. */
    private val htmlCache = ConcurrentHashMap<String, String>()

    /**
     * Returns an `<img …>` HTML snippet for the CWE badge, or `null` when [cweId] isn't a
     * `CWE-N` form. When [shortName] is supplied, it is rendered on the badge after the
     * id (e.g. "CWE-89 SQL Injection"). The snippet is cached per (id, name).
     */
    fun htmlFor(cweId: String?, shortName: String? = null): String? {
        if (cweId.isNullOrBlank()) return null
        val numeric = cweId.removePrefix("CWE-").removePrefix("cwe-").trim()
            .takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?: return null
        val name = shortName?.takeIf { it.isNotBlank() }
        val cacheKey = if (name == null) numeric else "$numeric-${name.hashCode()}"
        htmlCache[cacheKey]?.let { return it }
        val rendered = render(numeric, name) ?: return null
        val snippet = """<img src="${rendered.url}" width="${rendered.logicalWidth}" height="${rendered.logicalHeight}"/>"""
        htmlCache[cacheKey] = snippet
        return snippet
    }

    private data class Rendered(val url: String, val logicalWidth: Int, val logicalHeight: Int)

    private fun render(numeric: String, shortName: String?): Rendered? = try {
        val text = if (shortName != null) "CWE-$numeric $shortName" else "CWE-$numeric"

        // Logical metrics — what the badge "looks like" in HTML pixels.
        val logicalFontSize = 11
        val logicalPadX = 7
        val logicalPadY = 2
        val logicalTopMargin = 6
        val logicalBottomMargin = 0

        // Scale font + paddings up so the bitmap has 2× pixels in each dimension.
        val font = Font(Font.SANS_SERIF, Font.BOLD, logicalFontSize * SCALE)

        val measure = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics()
        measure.font = font
        val fm = measure.fontMetrics
        val textWidth = fm.stringWidth(text)
        measure.dispose()

        val padX = logicalPadX * SCALE
        val padY = logicalPadY * SCALE
        val topMargin = logicalTopMargin * SCALE
        val bottomMargin = logicalBottomMargin * SCALE
        val pillHeight = (fm.ascent + fm.descent + 2 * padY).coerceAtLeast(14 * SCALE)
        val height = pillHeight + topMargin + bottomMargin
        val width = (textWidth + 2 * padX).coerceAtLeast(pillHeight)

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = orange
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
        val fileName = if (safeName == null) "cwe-$numeric@${SCALE}x.png" else "cwe-$numeric-$safeName@${SCALE}x.png"
        val file = File(cacheDir, fileName)
        ImageIO.write(image, "PNG", file)

        // The HTML snippet sets the logical size — JEditorPane will downscale the 2× bitmap
        // to that logical size, which on HiDPI displays maps back to the original pixel
        // count. Net effect: text renders at native pixel density, no bilinear blur.
        Rendered(
            url = file.toURI().toString(),
            logicalWidth = width / SCALE,
            logicalHeight = height / SCALE,
        )
    } catch (ex: Exception) {
        null
    }
}
