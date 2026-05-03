package de.fraunhofer.iem.safe.sast

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import de.fraunhofer.iem.safe.sast.QodanaNodeExtractor.CWE_MAPPING
import java.awt.Component
import java.awt.Container
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeModel
import kotlin.collections.iterator
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

data class VulnerabilityInfo(
    val inspectionId: String? = null,
    val inspectionName: String? = null,
    val severity: String? = null,
    val message: String? = null,
    val filePath: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val startColumn: Int? = null,
    val endColumn: Int? = null,
    val snippet: String? = null,
    val baselineState: String? = null,
    val cwe: Cwe? = null,
    val traces: List<TaintTrace> = emptyList(),
)

/** A data-flow / taint trace. Multiple traces per finding are possible. */
data class TaintTrace(
    val description: String? = null,
    val steps: List<TaintStep> = emptyList(),
)

/** A single hop in a [TaintTrace]. Maps to Qodana's `SarifTrace.Node` and to a SARIF threadFlow location. */
data class TaintStep(
    val filePath: String? = null,
    val startLine: Int? = null,
    val startColumn: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val message: String? = null,
)

data class Cwe(
    val id: String? = null,
    val name: String? = null,
    /** Verbose, source-of-truth description (e.g., the full text from a Semgrep tag). For tooltips. */
    val description: String? = null,
) {
    /** Best available display label, falls back to id then "Unknown" */
    fun displayName(): String = name ?: id ?: "Unknown"
}

object QodanaNodeExtractor {

    private val CWE_MAPPING = mapOf(
        // SQL Injection
        "tainted-sql"              to Cwe(id = "CWE-89",  name = "SQL Injection"),
        "SqlInjection"             to Cwe(id = "CWE-89",  name = "SQL Injection"),
        // Command Injection
        "tainted-cmd"              to Cwe(id = "CWE-78",  name = "OS Command Injection"),
        "CommandInjection"         to Cwe(id = "CWE-78",  name = "OS Command Injection"),
        // XSS
        "xss"                      to Cwe(id = "CWE-79",  name = "Cross-site Scripting"),
        "no-direct-response-writer" to Cwe(id = "CWE-79", name = "Cross-site Scripting"),
        // Path Traversal
        "path-traversal"           to Cwe(id = "CWE-22",  name = "Path Traversal"),
        "PathTraversal"            to Cwe(id = "CWE-22",  name = "Path Traversal"),
        // Deserialization
        "tainted-deserialization"  to Cwe(id = "CWE-502", name = "Deserialization of Untrusted Data"),
        "InsecureDeserialization"  to Cwe(id = "CWE-502", name = "Deserialization of Untrusted Data"),
        // XXE
        "tainted-xxe"              to Cwe(id = "CWE-611", name = "XML External Entity (XXE)"),
        "XXE"                      to Cwe(id = "CWE-611", name = "XML External Entity (XXE)"),
        // SSRF
        "tainted-ssrf"             to Cwe(id = "CWE-918", name = "Server-Side Request Forgery"),
        "SSRF"                     to Cwe(id = "CWE-918", name = "Server-Side Request Forgery"),
        // Open Redirect
        "tainted-redirect"         to Cwe(id = "CWE-601", name = "Open Redirect"),
        "OpenRedirect"             to Cwe(id = "CWE-601", name = "Open Redirect"),
        // Weak Crypto
        "weak-crypto"              to Cwe(id = "CWE-327", name = "Use of a Broken or Risky Cryptographic Algorithm"),
        "WeakCrypto"               to Cwe(id = "CWE-327", name = "Use of a Broken or Risky Cryptographic Algorithm"),
        // Hardcoded Credentials
        "hardcoded-credentials"    to Cwe(id = "CWE-798", name = "Use of Hard-coded Credentials"),
        "HardcodedCredentials"     to Cwe(id = "CWE-798", name = "Use of Hard-coded Credentials"),
        // Insecure Random
        "insecure-random"          to Cwe(id = "CWE-330", name = "Use of Insufficiently Random Values"),
        "InsecureRandom"           to Cwe(id = "CWE-330", name = "Use of Insufficiently Random Values"),
        // LDAP Injection
        "tainted-ldapi"            to Cwe(id = "CWE-90",  name = "LDAP Injection"),
        "LdapInjection"            to Cwe(id = "CWE-90",  name = "LDAP Injection"),
        // XPath Injection
        "tainted-xpath"            to Cwe(id = "CWE-643", name = "XPath Injection"),
        "XPathInjection"           to Cwe(id = "CWE-643", name = "XPath Injection"),
        // Insecure Cookie
        "tainted-cookie"           to Cwe(id = "CWE-614", name = "Sensitive Cookie Without 'Secure' Attribute"),
        "InsecureCookie"           to Cwe(id = "CWE-614", name = "Sensitive Cookie Without 'Secure' Attribute"),
        // Header Injection
        "tainted-header"           to Cwe(id = "CWE-113", name = "HTTP Response Splitting"),
        "HeaderInjection"          to Cwe(id = "CWE-113", name = "HTTP Response Splitting"),
        // Aliases for Semgrep / project-specific rule IDs that need recovery via inspection-id mapping
        // (e.g., when a cached entry has cwe=null and only the inspection id is available to re-resolve from).
        "sql-injection"                   to Cwe(id = "CWE-89",  name = "SQL Injection"),
        "sqli"                            to Cwe(id = "CWE-89",  name = "SQL Injection"),
        "os-command"                      to Cwe(id = "CWE-78",  name = "OS Command Injection"),
        "hardcoded-keystore"              to Cwe(id = "CWE-798", name = "Use of Hard-coded Credentials"),
        "outdated-tls"                    to Cwe(id = "CWE-327", name = "Use of a Broken or Risky Cryptographic Algorithm"),
        "cbc-padding"                     to Cwe(id = "CWE-327", name = "Use of a Broken or Risky Cryptographic Algorithm"),
        "weak-random"                     to Cwe(id = "CWE-330", name = "Use of Insufficiently Random Values"),
        "h2-console"                      to Cwe(id = "CWE-489", name = "Active Debug Code"),
        "insecure-logging"                to Cwe(id = "CWE-532", name = "Insertion of Sensitive Information into Log File"),
        "non-constant-time"               to Cwe(id = "CWE-208", name = "Observable Timing Discrepancy"),
        "csrf-disabled"                   to Cwe(id = "CWE-352", name = "Cross-Site Request Forgery (CSRF)"),
        "unrestricted-request-mapping"    to Cwe(id = "CWE-352", name = "Cross-Site Request Forgery (CSRF)"),
        "cookie-missing"                  to Cwe(id = "CWE-614", name = "Sensitive Cookie Without 'Secure' Attribute"),
        "cookie-secure-flag"              to Cwe(id = "CWE-614", name = "Sensitive Cookie Without 'Secure' Attribute"),
        "no-static-initialization-vector" to Cwe(id = "CWE-329", name = "Generation of Predictable IV with CBC Mode"),
        "tainted-file-path"               to Cwe(id = "CWE-22",  name = "Path Traversal"),
        "missing-user-entrypoint"         to Cwe(id = "CWE-269", name = "Improper Privilege Management"),
    )

    /**
     * Walks every open tool window's component tree to find Qodana result trees and
     * collects all SARIF problems they contain. Reuses the same reflection-based traversal
     * as [extract] but starts from the tree root instead of a single selected node.
     *
     * Must be called on the EDT (Swing component access).
     */
    fun extractAll(project: Project): List<VulnerabilityInfo> {
        val results = mutableListOf<VulnerabilityInfo>()
        val seen = mutableSetOf<JTree>()
        val toolWindowManager = ToolWindowManager.getInstance(project)
        for (id in toolWindowManager.toolWindowIds) {
            val tw = toolWindowManager.getToolWindow(id) ?: continue
            for (content in tw.contentManager.contents) {
                collectFromTrees(content.component, seen, results)
            }
        }
        return results
    }

    private fun collectFromTrees(
        component: Component?,
        seen: MutableSet<JTree>,
        results: MutableList<VulnerabilityInfo>,
    ) {
        if (component == null) return
        if (component is JTree) {
            if (seen.add(component) && treeHasQodanaShape(component)) {
                walkTreeForProblems(component.model, component.model.root, results)
            }
            return
        }
        if (component is Container) {
            for (child in component.components) {
                collectFromTrees(child, seen, results)
            }
        }
    }

    private fun treeHasQodanaShape(tree: JTree): Boolean {
        val root = tree.model.root ?: return false
        return hasPrimaryDataDescendant(tree.model, root, depthLimit = 4)
    }

    private fun hasPrimaryDataDescendant(model: TreeModel, node: Any, depthLimit: Int): Boolean {
        if (depthLimit <= 0) return false
        if (unwrap(node).getField("primaryData") != null) return true
        val count = model.getChildCount(node)
        for (i in 0 until count) {
            val child = model.getChild(node, i) ?: continue
            if (hasPrimaryDataDescendant(model, child, depthLimit - 1)) return true
        }
        return false
    }

    private fun walkTreeForProblems(model: TreeModel, node: Any?, results: MutableList<VulnerabilityInfo>) {
        if (node == null) return
        val data = unwrap(node)
        val primaryData = data.getField("primaryData")
        if (primaryData != null) {
            val sarifProblem = primaryData.getField("sarifProblem")
            if (sarifProblem != null) {
                results.add(parseSarifProblem(sarifProblem))
                return
            }
            val modelTreeNode = data.getField("modelTreeNode")
            if (modelTreeNode != null) {
                val inspectionName = modelTreeNode.callMethod("getInspectionName") as? String
                val children = modelTreeNode.getField("children")
                val moduleNodes = children?.getField("moduleNodes") as? List<*>
                if (moduleNodes != null) {
                    val before = results.size
                    for (moduleNode in moduleNodes) {
                        val fadChildren = moduleNode?.getField("children")
                            ?.getField("fileAndDirectoryNodeChildren") ?: continue
                        collectProblems(fadChildren, results)
                    }
                    if (inspectionName != null) {
                        for (i in before until results.size) {
                            if (results[i].inspectionName == null) {
                                results[i] = results[i].copy(inspectionName = inspectionName)
                            }
                        }
                    }
                    return
                }
            }
        }
        val count = model.getChildCount(node)
        for (i in 0 until count) {
            walkTreeForProblems(model, model.getChild(node, i), results)
        }
    }

    private fun unwrap(node: Any): Any =
        (node as? DefaultMutableTreeNode)?.userObject ?: node

    fun extract(e: AnActionEvent): List<VulnerabilityInfo> {
        val node = e.getData(PlatformDataKeys.SELECTED_ITEM) ?: return emptyList()
        val primaryData = node.getField("primaryData") ?: return emptyList()

        val sarifProblem = primaryData.getField("sarifProblem")
        if (sarifProblem != null) {
            return listOf(parseSarifProblem(sarifProblem))
        }

        val modelTreeNode = node.getField("modelTreeNode") ?: return emptyList()
        val inspectionName = modelTreeNode.callMethod("getInspectionName") as? String
        val inspectionId = primaryData.getField("inspectionId") as? String

        val children = modelTreeNode.getField("children") ?: return emptyList()
        val moduleNodes = children.getField("moduleNodes") as? List<*> ?: return emptyList()

        val problems = mutableListOf<VulnerabilityInfo>()
        for (moduleNode in moduleNodes) {
            if (moduleNode == null) continue
            val fadChildren = moduleNode.getField("children")
                ?.getField("fileAndDirectoryNodeChildren") ?: continue
            collectProblems(fadChildren, problems)
        }

        if (problems.isNotEmpty()) {
            return problems.map { it.copy(inspectionName = inspectionName ?: it.inspectionName) }
        }

        return listOf(
            VulnerabilityInfo(
                inspectionId = inspectionId,
                inspectionName = inspectionName,
                cwe = inspectionIdToCwe(inspectionId)
            )
        )
    }

    private fun collectProblems(fadChildren: Any, results: MutableList<VulnerabilityInfo>) {
        val fileNodes = fadChildren.getField("fileNodeChildren") as? List<*> ?: emptyList<Any>()
        val dirNodes  = fadChildren.getField("directoryNodeChildren") as? List<*> ?: emptyList<Any>()

        for (fileNode in fileNodes) {
            val problemNodes = fileNode?.getField("children")?.getField("nodes") as? List<*> ?: continue
            for (problemNode in problemNodes) {
                val sarif = problemNode?.getField("primaryData")?.getField("sarifProblem") ?: continue
                results.add(parseSarifProblem(sarif))
            }
        }

        for (dirNode in dirNodes) {
            val innerChildren = dirNode?.getField("children") ?: continue
            collectProblems(innerChildren, results)
        }
    }

    private fun parseSarifProblem(sarif: Any): VulnerabilityInfo {
        val inspectionId = sarif.getField("inspectionId") as? String
        val message      = sarif.getField("message") as? String

        return VulnerabilityInfo(
            inspectionId  = inspectionId,
            severity      = sarif.getField("qodanaSeverity")?.toString(),
            message       = message,
            filePath      = sarif.getField("relativePathToFile") as? String,
            startLine     = sarif.getField("startLine") as? Int,
            endLine       = sarif.getField("endLine") as? Int,
            startColumn   = sarif.getField("startColumn") as? Int,
            endColumn     = sarif.getField("endColumn") as? Int,
            snippet       = sarif.getField("snippetText") as? String,
            baselineState = sarif.getField("baselineState") as? String,
            cwe           = extractCwe(inspectionId, message, sarif),
            traces        = extractTraces(sarif),
        )
    }

    /** Reflects [org.jetbrains.qodana.problem.SarifTrace] / `SarifTrace.Node` off the [sarif] object. */
    private fun extractTraces(sarif: Any): List<TaintTrace> {
        val rawTraces = sarif.getField("traces") as? Collection<*> ?: return emptyList()
        return rawTraces.mapNotNull { trace ->
            if (trace == null) return@mapNotNull null
            val description = trace.getField("description") as? String
            val rawNodes = trace.getField("nodes") as? Collection<*> ?: return@mapNotNull null
            val steps = rawNodes.mapNotNull { node ->
                if (node == null) return@mapNotNull null
                val startLine = node.getField("startLine") as? Int
                val startColumn = node.getField("startColumn") as? Int
                val charLength = node.getField("charLength") as? Int
                TaintStep(
                    filePath = node.getField("relativePathToFile") as? String,
                    startLine = startLine,
                    startColumn = startColumn,
                    endLine = startLine,
                    endColumn = if (startColumn != null && charLength != null) startColumn + charLength else null,
                )
            }
            if (steps.isEmpty()) null else TaintTrace(description, steps)
        }
    }

    /**
     * Returns a [Cwe] object resolved from (in priority order):
     * 1. PropertyBag tags on the SARIF result
     * 2. PropertyBag map entries with a "cwe" key
     * 3. defaultProperties tags
     * 4. Regex match in the message text — id only, no name available
     * 5. Static [CWE_MAPPING] lookup by inspection id

     */
    private fun extractCwe(inspectionId: String?, message: String?, sarif: Any): Cwe? {
        // 1. Check PropertyBag tags on the SARIF result
        val properties = sarif.getField("properties")
        if (properties != null) {
            val cweFromTag = (properties.callMethod("getTags") as? Set<*>)
                ?.firstOrNull { (it as? String)?.startsWith("CWE-") == true } as? String
            if (cweFromTag != null) return cweFromTag.toCwe()

            // Also check PropertyBag map entries for a "cwe" key
            if (properties is Map<*, *>) {
                val cweValue = properties.entries
                    .firstOrNull { it.key?.toString()?.contains("cwe", ignoreCase = true) == true }
                    ?.value?.toString()
                if (cweValue != null) return cweValue.toCwe()
            }
        }

        // 2. Check defaultProperties tags
        val defaultProperties = sarif.getField("defaultProperties")
        if (defaultProperties != null) {
            val cweFromTag = (defaultProperties.callMethod("getTags") as? Set<*>)
                ?.firstOrNull { (it as? String)?.startsWith("CWE-") == true } as? String
            if (cweFromTag != null) return cweFromTag.toCwe()
        }

        // 3. Try to extract a CWE id from the message text (id only — name unknown)
        if (message != null) {
            val match = Regex("CWE-\\d+").find(message)
            if (match != null) return Cwe(id = match.value)
        }

        // 4. Fallback: static mapping (includes human-readable name)
        return inspectionIdToCwe(inspectionId)
    }

    /**
     * Enriches a bare "CWE-NNN" string with a name from [CWE_MAPPING] when available,
     * otherwise returns a [Cwe] with id only.

     */
    private fun String.toCwe(): Cwe {
        val match = Regex("CWE-\\d+").find(this) ?: return Cwe(id = this)
        val id = match.value
        val mappingName = CWE_MAPPING.values.firstOrNull { it.id == id }?.name
        val tagDescription = this.substring(match.range.last + 1).trimStart(':', ' ').trim().takeIf { it.isNotEmpty() }
        // Prefer the short curated name for display; keep the verbose tag text as a tooltip-only description.
        val displayName = mappingName ?: tagDescription
        val description = tagDescription?.takeIf { it != displayName }
        return Cwe(id = id, name = displayName, description = description)
    }

    fun inspectionIdToCwe(inspectionId: String?): Cwe? {
        if (inspectionId == null) return null
        for ((key, cwe) in CWE_MAPPING) {
            if (inspectionId.contains(key, ignoreCase = true)) return cwe
        }
        return null
    }

    fun cweFromTagString(tag: String): Cwe = tag.toCwe()

    private fun Any.getField(name: String): Any? = try {
        this::class.memberProperties
            .firstOrNull { it.name == name }
            ?.apply { isAccessible = true }
            ?.call(this)
    } catch (_: Exception) { null }

    private fun Any.callMethod(name: String): Any? = try {
        this::class.java.methods
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.invoke(this)
    } catch (_: Exception) { null }
}