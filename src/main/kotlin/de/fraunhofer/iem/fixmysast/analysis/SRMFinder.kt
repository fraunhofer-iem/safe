package de.fraunhofer.iem.fixmysast.analysis

import de.fraunhofer.iem.fixmysast.data.SrmLoader

/***
 * Finds SRMs in the target application.
 ***/

object SRMFinder {

    fun isSRM(signature: String): Boolean {
        val method = SrmLoader.srmCatalogMethods[signature]
        return method != null && (method.srm.isNotEmpty() || method.cwe.isNotEmpty())
    }

    fun getSrmCategory(signature: String): List<String> {
        val method = SrmLoader.srmCatalogMethods[signature]
        return method?.srm ?: emptyList()
    }

    fun getCweCategory(signature: String): List<String> {
        val method = SrmLoader.srmCatalogMethods[signature]
        return method?.cwe ?: emptyList()
    }

    fun getSrmAndCweCategory(signature: String): List<String> {
        val method = SrmLoader.srmCatalogMethods[signature]
        return if (method != null) {
            method.srm + method.cwe
        } else {
            emptyList()
        }
    }
}
