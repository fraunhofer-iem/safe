package de.fraunhofer.iem.safe.util

/**
 * Static glossary of common security/AppSec terms. Used to wrap explanation text with
 * tooltips so hovering a recognised term reveals a short definition.
 */
object Glossary {

    /** Term → short, plain-language definition. Keys are lowercase; matching is case-insensitive on whole words. */
    private val TERMS: Map<String, String> = linkedMapOf(
        "taint" to "Data that originated from an untrusted source (user input, network, etc.) and is treated as potentially malicious until it has been validated or sanitised.",
        "tainted" to "Marked as derived from an untrusted source; needs validation or sanitisation before reaching a sensitive operation.",
        "source" to "The point in the code where untrusted data first enters the program (e.g., HTTP request parameter, file read, database row).",
        "sink" to "A sensitive operation where untrusted data could cause harm (e.g., a SQL query, command execution, HTML output).",
        "propagator" to "An intermediate operation that carries tainted data from a source toward a sink without transforming it into a safe form.",
        "sanitiser" to "Code that converts tainted data into a safe form for a specific sink — for example, escaping or parameter binding.",
        "sanitizer" to "Code that converts tainted data into a safe form for a specific sink — for example, escaping or parameter binding.",
        "deserialization" to "The process of converting a serialised byte stream back into an in-memory object. When the input is untrusted, attacker-supplied byte streams can lead to arbitrary code execution.",
        "deserialisation" to "The process of converting a serialised byte stream back into an in-memory object. When the input is untrusted, attacker-supplied byte streams can lead to arbitrary code execution.",
        "prototype pollution" to "A JavaScript bug where attacker-controlled keys reach Object.prototype, letting them inject properties that affect every object in the runtime.",
        "xss" to "Cross-Site Scripting — injecting JavaScript into a page where another user will execute it.",
        "csrf" to "Cross-Site Request Forgery — tricking a browser that holds a victim's session into issuing an unintended request to a target site.",
        "ssrf" to "Server-Side Request Forgery — coaxing a server into making outbound requests to attacker-chosen targets, often into internal-only services.",
        "xxe" to "XML External Entity — an XML feature that, when parsing untrusted input, can read local files or trigger SSRF.",
        "ldap injection" to "Building an LDAP query by concatenating untrusted input, allowing the attacker to alter filter semantics.",
        "xpath injection" to "Building an XPath query by concatenating untrusted input, allowing the attacker to alter the query's selection.",
        "path traversal" to "Untrusted input reaching a filesystem API that lets it escape a base directory (e.g., via `../` segments).",
        "directory traversal" to "Untrusted input reaching a filesystem API that lets it escape a base directory (e.g., via `../` segments).",
        "open redirect" to "Server-side redirect whose destination is taken from untrusted input, letting an attacker land victims on phishing pages.",
        "prepared statement" to "A pre-compiled SQL template with placeholders for parameters, so user data never becomes part of the query syntax.",
        "parameterised query" to "A SQL query whose values are bound separately from the SQL text, preventing the values from being interpreted as SQL.",
        "parameterized query" to "A SQL query whose values are bound separately from the SQL text, preventing the values from being interpreted as SQL.",
        "session fixation" to "An attacker forces a known session id on a victim's browser before login, then reuses it after the victim authenticates.",
        "secure flag" to "A cookie attribute that tells the browser to only transmit the cookie over HTTPS.",
        "httponly flag" to "A cookie attribute that prevents JavaScript on the page from reading the cookie value.",
        "samesite" to "A cookie attribute that controls whether the cookie is sent on cross-site requests, mitigating CSRF.",
        "constant-time" to "A comparison or operation whose duration does not depend on secret values, preventing timing side-channels that leak the secret.",
        "side channel" to "Information leaked through an indirect observable property — execution time, power use, cache state, etc.",
        "side-channel" to "Information leaked through an indirect observable property — execution time, power use, cache state, etc.",
        "iv" to "Initialisation Vector — a per-message random value mixed into a block cipher's first block. Reusing or omitting it breaks security guarantees of modes like CBC.",
        "cbc" to "Cipher-Block-Chaining mode of operation. Without authentication and a fresh IV per message, ciphertexts are malleable and may be vulnerable to padding-oracle attacks.",
        "hmac" to "Hash-based Message Authentication Code — a keyed hash construction used for integrity and authenticity.",
        "salt" to "A unique per-record random value mixed into a password hash so identical passwords produce different hashes.",
        "pepper" to "A site-wide secret added to passwords before hashing, stored separately from the database.",
        "pbkdf2" to "A password-based key derivation function — slows brute-force attacks by repeating a hash many times.",
        "argon2" to "A memory-hard password hashing function recommended by current cryptographic guidance.",
        "bcrypt" to "A password hashing function with a tunable work factor; widely available and a reasonable choice when Argon2 isn't an option.",
        "cwe" to "Common Weakness Enumeration — MITRE's catalogue of software weakness types, each with a numeric id (e.g., CWE-89).",
        "owasp" to "Open Worldwide Application Security Project — non-profit producing widely cited security guidance, including the OWASP Top 10.",
    )

    /** Returns the definition for [term] (case-insensitive), or `null` if the glossary has no entry. */
    fun definitionOf(term: String): String? = TERMS[term.lowercase()]

    /**
     * Wraps any glossary term in [text] with `<span class="glossary" title="...">`. Swing's
     * HTMLEditorKit doesn't recognise `<abbr>` (it isn't in `HTML.Tag`), so its title gets
     * dropped during parsing; `<span>` is supported and preserves the title for the panel's
     * `getToolTipText` walker to find.
     */
    fun annotate(text: String): String {
        if (text.isEmpty()) return text
        var result = text
        for ((term, definition) in TERMS) {
            // Whole-word, case-insensitive match. Lookbehind/lookahead exclude letters/digits
            // so the regex doesn't match `taint` inside `tainted`, etc.
            val pattern = Regex("(?<![A-Za-z0-9_-])${Regex.escape(term)}(?![A-Za-z0-9_-])", RegexOption.IGNORE_CASE)
            val safeDef = definition.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
            result = pattern.replace(result) { match ->
                """<span class="glossary" title="$safeDef">${match.value}</span>"""
            }
        }
        return result
    }
}
