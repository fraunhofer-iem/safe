package de.fraunhofer.iem.fixmysast.data

object Constants {
    const val SRM_CATALOG_PATH = "/srm-catalog.json"
    const val SRM_TOOLTIP_TEMPLATE = "The method %s is security critical. \nTag(s): %s."

    val CWE_CAT = listOf("CWE89", "CWE862", "CWE79", "CWE863", "CWE78", "CWE306", "CWE601" )
    const val SRM_CAT_SOURCE = "source"
    const val SRM_CAT_SINK = "sink"
    const val SRM_CAT_SANITIZER = "sanitizer"
    const val SRM_CAT_AUTHENTICATION_UNSAFE = "auth-unsafe-state"
    const val SRM_CAT_AUTHENTICATION_SAFE = "auth-safe-state"
    const val SRM_CAT_AUTHENTICATION_NOCHANGE = "auth-no-change"

    const val ICON_PATH = "/icons/%s.png"

}