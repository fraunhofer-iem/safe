package de.fraunhofer.iem.safe.sast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CweResolutionTest {

    @Test
    fun `cweFromTagString picks curated short name from mapping when id matches`() {
        val cwe = QodanaNodeExtractor.cweFromTagString("CWE-89: Improper Neutralization of Special Elements")
        assertEquals("CWE-89", cwe.id)
        assertEquals("SQL Injection", cwe.name)
        assertEquals("Improper Neutralization of Special Elements", cwe.description)
    }

    @Test
    fun `cweFromTagString uses tag description as name when not in mapping`() {
        val cwe = QodanaNodeExtractor.cweFromTagString("CWE-12345: Some Custom Description")
        assertEquals("CWE-12345", cwe.id)
        assertEquals("Some Custom Description", cwe.name)
    }

    @Test
    fun `cweFromTagString returns plain CWE for bare id`() {
        val cwe = QodanaNodeExtractor.cweFromTagString("CWE-89")
        assertEquals("CWE-89", cwe.id)
        assertEquals("SQL Injection", cwe.name)
        assertNull(cwe.description)
    }

    @Test
    fun `cweFromTagString preserves unknown text when no CWE id is present`() {
        val cwe = QodanaNodeExtractor.cweFromTagString("not-a-cwe")
        assertEquals("not-a-cwe", cwe.id)
        assertNull(cwe.name)
    }

    @Test
    fun `inspectionIdToCwe substring-matches across CWE_MAPPING keys`() {
        assertEquals("CWE-89", QodanaNodeExtractor.inspectionIdToCwe("java.spring.security.spring-sqli-deepsemgrep")?.id)
        assertEquals("CWE-89", QodanaNodeExtractor.inspectionIdToCwe("sast.rules.hde-sql-injection-string-concat")?.id)
        assertEquals("CWE-78", QodanaNodeExtractor.inspectionIdToCwe("python.lang.os.tainted-os-command-stdlib")?.id)
        assertEquals("CWE-614", QodanaNodeExtractor.inspectionIdToCwe("java.lang.security.audit.cookie-missing-secure-flag")?.id)
        assertEquals("CWE-269", QodanaNodeExtractor.inspectionIdToCwe("dockerfile.security.missing-user-entrypoint")?.id)
    }

    @Test
    fun `inspectionIdToCwe returns null for ids that match no key`() {
        assertNull(QodanaNodeExtractor.inspectionIdToCwe("totally-unrelated-rule"))
        assertNull(QodanaNodeExtractor.inspectionIdToCwe(null))
    }
}
