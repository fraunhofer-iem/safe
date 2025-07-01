package de.fraunhofer.iem.fixmysast.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

/***
 * Loads the SRMs from the SRM-Catalog json file.
 ***/

object SrmLoader {
    val srmCatalogMethods: Map<String, SRM> by lazy {
        val stream = javaClass.getResourceAsStream(Constants.SRM_CATALOG_PATH)
            ?: throw IllegalStateException("srm-catalog.json not found in resources")
        val reader = InputStreamReader(stream)
        val json = Gson().fromJson<Map<String, Any>>(reader, object : TypeToken<Map<String, Any>>() {}.type)

        @Suppress("UNCHECKED_CAST")
        val methodList = (json["methods"] as? List<Map<String, Any>>).orEmpty()

        methodList.mapNotNull { entry ->
            val signature = entry["signature"] as? String ?: return@mapNotNull null
            val srm = entry["srm"] as? List<String> ?: emptyList()
            val cwe = entry["cwe"] as? List<String> ?: emptyList()
            signature to SRM(signature, srm, cwe)
        }.toMap()
    }
}