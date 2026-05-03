package de.fraunhofer.iem.safe.util

import com.intellij.openapi.diagnostic.Logger
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lazy-loaded index of CWE descriptions sourced from the bundled MITRE CWE XML catalog
 * (`/cwec_v4.19.1.xml`). Keys are `CWE-<NN>`; values are the `<Description>` text.
 *
 * The catalog file is ~15 MB; the first call to [descriptions] parses it once. Pre-warm
 * from a background thread to avoid stalling the EDT.
 */
object CweCatalog {

    private const val RESOURCE_PATH = "/cwec_v4.19.1.xml"
    private val logger = Logger.getInstance(CweCatalog::class.java)

    val descriptions: Map<String, String> by lazy { loadDescriptions() }

    fun descriptionFor(cweId: String?): String? {
        if (cweId == null) return null
        return descriptions[cweId.uppercase()]
    }

    private fun loadDescriptions(): Map<String, String> {
        val stream = CweCatalog::class.java.getResourceAsStream(RESOURCE_PATH) ?: run {
            logger.warn("CWE catalog $RESOURCE_PATH not found in resources")
            return emptyMap()
        }
        return try {
            stream.use(::parse)
        } catch (ex: Exception) {
            logger.warn("Failed to parse CWE catalog", ex)
            emptyMap()
        }
    }

    // TODO: replace this DOM-based parse with a SAX walk that only retains <Description>
    //  text per <Weakness ID>. The 15 MB catalog currently builds a full DOM (~60-150 MB peak),
    //  even though we read only two attributes/elements per node. A SAX handler would cut
    //  parse time and peak memory by an order of magnitude.
    private fun parse(stream: InputStream): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The XML uses a default namespace; turning namespace awareness off lets us
            // match elements by their literal tag name ("Weakness", "Description").
            isNamespaceAware = false
            // Defensive XXE hardening — ignored by parsers that don't support these features.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(stream)
        val weaknesses = doc.getElementsByTagName("Weakness")
        val out = HashMap<String, String>((weaknesses.length * 4) / 3 + 16)
        for (i in 0 until weaknesses.length) {
            val w = weaknesses.item(i) as? Element ?: continue
            val id = w.getAttribute("ID").takeIf { it.isNotBlank() } ?: continue
            val children = w.childNodes
            for (j in 0 until children.length) {
                val child = children.item(j) as? Element ?: continue
                if (child.tagName == "Description") {
                    val text = child.textContent?.trim().orEmpty()
                    if (text.isNotEmpty()) out["CWE-$id"] = text
                    break
                }
            }
        }
        return out
    }
}
