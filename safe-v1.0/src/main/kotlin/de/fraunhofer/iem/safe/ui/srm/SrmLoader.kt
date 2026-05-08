package de.fraunhofer.iem.safe.ui.srm

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import de.fraunhofer.iem.safe.PluginBundle
import java.io.InputStreamReader

/***
 * Loads the SRMs from the SRM-Catalog json file.
 ***/
data class Srm(
    val signature: String,
    val srm: List<String>,
    val cwe: List<String>
)

object SrmLoader {
    val srmCatalogMethods: Map<String, Srm> by lazy {
        val stream = javaClass.getResourceAsStream(PluginBundle.lazy("safe.configuration.SRM_CATALOG_PATH").get())
            ?: throw IllegalStateException("srm-catalog.json not found in resources")
        val reader = InputStreamReader(stream)
        val json = Gson().fromJson<Map<String, Any>>(reader, object : TypeToken<Map<String, Any>>() {}.type)

        @Suppress("UNCHECKED_CAST")
        val methodList = (json["methods"] as? List<Map<String, Any>>).orEmpty()

        methodList.mapNotNull { entry ->
            val signature = entry["signature"] as? String ?: return@mapNotNull null
            val srm = entry["srm"] as? List<String> ?: emptyList()
            val cwe = entry["cwe"] as? List<String> ?: emptyList()
            signature to Srm(signature, srm, cwe)
        }.toMap()
    }
}