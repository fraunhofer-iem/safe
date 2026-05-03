package de.fraunhofer.iem.safe.sast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStreamReader

class SarifParserTest {

    private fun parse(json: String): List<VulnerabilityInfo> =
        SarifParser.parse(InputStreamReader(json.byteInputStream()))

    @Test
    fun `parses a single result with location`() {
        val findings = parse(
            """
            {
              "version": "2.1.0",
              "runs": [{
                "tool": { "driver": { "name": "Acme", "rules": [
                  { "id": "rule.x", "name": "Rule X" }
                ]}},
                "results": [{
                  "ruleId": "rule.x",
                  "level": "warning",
                  "message": { "text": "Bad thing happened." },
                  "locations": [{
                    "physicalLocation": {
                      "artifactLocation": { "uri": "src/Foo.java" },
                      "region": { "startLine": 10, "endLine": 12, "startColumn": 4, "endColumn": 18 }
                    }
                  }]
                }]
              }]
            }
            """.trimIndent()
        )
        assertEquals(1, findings.size)
        val f = findings[0]
        assertEquals("rule.x", f.inspectionId)
        assertEquals("Rule X", f.inspectionName)
        assertEquals("warning", f.severity)
        assertEquals("Bad thing happened.", f.message)
        assertEquals("src/Foo.java", f.filePath)
        assertEquals(10, f.startLine)
        assertEquals(12, f.endLine)
        assertEquals(4, f.startColumn)
        assertEquals(18, f.endColumn)
    }

    @Test
    fun `extracts CWE id from rule properties tags`() {
        val findings = parse(
            """
            {"version":"2.1.0","runs":[{
              "tool": {"driver": {"rules": [
                {"id":"r","properties":{"tags":["CWE-89: SQL Injection details"]}}
              ]}},
              "results": [{
                "ruleId": "r",
                "message": {"text":"x"},
                "locations": [{"physicalLocation":{"artifactLocation":{"uri":"f"}}}]
              }]
            }]}
            """.trimIndent()
        )
        assertEquals("CWE-89", findings[0].cwe?.id)
        // Curated mapping name wins over the verbose tag text.
        assertEquals("SQL Injection", findings[0].cwe?.name)
    }

    @Test
    fun `falls back to inspection-id mapping when no CWE tag is present`() {
        val findings = parse(
            """
            {"version":"2.1.0","runs":[{
              "tool":{"driver":{"rules":[{"id":"sast.rules.hde-sql-injection-string-concat"}]}},
              "results":[{
                "ruleId":"sast.rules.hde-sql-injection-string-concat",
                "message":{"text":"x"},
                "locations":[{"physicalLocation":{"artifactLocation":{"uri":"f"}}}]
              }]
            }]}
            """.trimIndent()
        )
        // "sql-injection" substring → CWE-89
        assertEquals("CWE-89", findings[0].cwe?.id)
    }

    @Test
    fun `parses codeFlows into taint traces`() {
        val findings = parse(
            """
            {"version":"2.1.0","runs":[{
              "tool":{"driver":{"rules":[{"id":"r"}]}},
              "results":[{
                "ruleId":"r",
                "message":{"text":"m"},
                "locations":[{"physicalLocation":{"artifactLocation":{"uri":"f"}}}],
                "codeFlows":[{
                  "message":{"text":"flow"},
                  "threadFlows":[{
                    "locations":[
                      {"location":{"message":{"text":"Source: 'x'"},"physicalLocation":{"artifactLocation":{"uri":"src/A.java"},"region":{"startLine":1}}}},
                      {"location":{"message":{"text":"Sink"},"physicalLocation":{"artifactLocation":{"uri":"src/A.java"},"region":{"startLine":5}}}}
                    ]
                  }]
                }]
              }]
            }]}
            """.trimIndent()
        )
        assertEquals(1, findings[0].traces.size)
        val trace = findings[0].traces[0]
        assertEquals(2, trace.steps.size)
        assertEquals("Source: 'x'", trace.steps[0].message)
        assertEquals(1, trace.steps[0].startLine)
        assertEquals(5, trace.steps[1].startLine)
    }

    @Test
    fun `produces one VulnerabilityInfo per location`() {
        val findings = parse(
            """
            {"version":"2.1.0","runs":[{
              "tool":{"driver":{"rules":[{"id":"r"}]}},
              "results":[{
                "ruleId":"r",
                "message":{"text":"m"},
                "locations":[
                  {"physicalLocation":{"artifactLocation":{"uri":"a"},"region":{"startLine":1}}},
                  {"physicalLocation":{"artifactLocation":{"uri":"b"},"region":{"startLine":2}}}
                ]
              }]
            }]}
            """.trimIndent()
        )
        assertEquals(2, findings.size)
        assertEquals("a", findings[0].filePath)
        assertEquals("b", findings[1].filePath)
    }

    @Test
    fun `returns empty for empty runs`() {
        assertTrue(parse("""{"version":"2.1.0","runs":[]}""").isEmpty())
    }

    @Test
    fun `handles missing location info gracefully`() {
        val findings = parse(
            """
            {"version":"2.1.0","runs":[{
              "tool":{"driver":{"rules":[{"id":"r"}]}},
              "results":[{
                "ruleId":"r",
                "message":{"text":"m"},
                "locations":[]
              }]
            }]}
            """.trimIndent()
        )
        // Empty `locations` → one VulnerabilityInfo with no filePath/lines (parseResult fallback).
        assertEquals(1, findings.size)
        assertEquals("r", findings[0].inspectionId)
        assertNull(findings[0].filePath)
        assertNull(findings[0].startLine)
    }

    @Test
    fun `does not crash on missing rule entries`() {
        val findings = parse(
            """
            {"version":"2.1.0","runs":[{
              "tool":{"driver":{}},
              "results":[{
                "ruleId":"unknown-rule",
                "message":{"text":"m"},
                "locations":[{"physicalLocation":{"artifactLocation":{"uri":"f"},"region":{"startLine":1}}}]
              }]
            }]}
            """.trimIndent()
        )
        assertEquals(1, findings.size)
        assertNotNull(findings[0])
        assertNull(findings[0].cwe)
    }
}
