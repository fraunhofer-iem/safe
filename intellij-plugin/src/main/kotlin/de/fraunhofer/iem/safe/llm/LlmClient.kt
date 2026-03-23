package de.fraunhofer.iem.safe.llm

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import de.fraunhofer.iem.safe.sast.Issue

/**
 * This class provides the functionality of sending LLM request and receiving the explanation for the SAST issue.
 *
 * @author Alexandra Fomina
 */
object LlmClient {
    private val mapper = jacksonObjectMapper()

    private val explanationCache: MutableMap<String, String> = mutableMapOf()

    /**
     * Sends the prompts to LLM based on the expertise level and parses the response for the explanation of SAST issue
     */
    fun sendRequest(issue: Issue?, project: Project, experienceLevel: String): String? {

        val llmConfig = LlmConfig()

        val client = OpenAIOkHttpClient.builder()
            .baseUrl(llmConfig.apiURL)
            .apiKey(llmConfig.apiKey)
            .build()

        val params = ChatCompletionCreateParams.builder()
            .addSystemMessage(PromptTemplate.getSystemPrompt())
            .addUserMessage(PromptTemplate.buildUserPrompt(issue, experienceLevel, project))
            .model(llmConfig.model)
            .temperature(llmConfig.temperature.toDouble())
            .build()

        val chatCompletion: ChatCompletion = client.chat().completions().create(params)


        explanationCache["Body{messages=[ChatCompletionMessageParam{system=ChatCompletionSystemMessageParam{content=Content{text=You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.\n" +
                "\n" +
                "When providing a response, follow the below guidelines:\n" +
                "- The explanation must not exceed 750 words.\n" +
                "- Provide the explanation as a basic string value.\n" +
                "- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.\n" +
                "- Format the response as a YAML document using the schema below:\n" +
                "\n" +
                "---\n" +
                "overview: \"<overview of the detected vulnerability in 2-3 sentences>\"\n" +
                "explanation: \"<explanation of the vulnerability in the code snippet>\"\n" +
                "example: \"<generic code snippet along with a description of the code snippet>\"\n" +
                "exampleDescription: \"<description of example code snippet>\"\n" +
                "}, role=system, name=, additionalProperties={}}}, ChatCompletionMessageParam{user=ChatCompletionUserMessageParam{content=Content{text=Explain the vulnerability detected in the code snippet to a developer who has Intermediate experience with in software security.\n" +
                "\n" +
                "Code Snippet:\n" +
                "```\n" +
                "@Override\n" +
                "    public void doPost(HttpServletRequest request, HttpServletResponse response)\n" +
                "            throws ServletException, IOException {\n" +
                "        response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "\n" +
                "        javax.servlet.http.Cookie[] theCookies = request.getCookies();\n" +
                "\n" +
                "        String param = \"noCookieValueSupplied\";\n" +
                "        if (theCookies != null) {\n" +
                "            for (javax.servlet.http.Cookie theCookie : theCookies) {\n" +
                "                if (theCookie.getName().equals(\"BenchmarkTest00951\")) {\n" +
                "                    param = java.net.URLDecoder.decode(theCookie.getValue(), \"UTF-8\");\n" +
                "                    break;\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        String bar = new Test().doSomething(request, param);\n" +
                "\n" +
                "        // FILE URIs are tricky because they are different between Mac and Windows because of lack\n" +
                "        // of standardization.\n" +
                "        // Mac requires an extra slash for some reason.\n" +
                "        String startURIslashes = \"\";\n" +
                "        if (System.getProperty(\"os.name\").indexOf(\"Windows\") != -1)\n" +
                "            if (System.getProperty(\"os.name\").indexOf(\"Windows\") != -1) startURIslashes = \"/\";\n" +
                "            else startURIslashes = \"//\";\n" +
                "\n" +
                "        try {\n" +
                "            java.net.URI fileURI =\n" +
                "                    new java.net.URI(\n" +
                "                            \"file:\"\n" +
                "                                    + startURIslashes\n" +
                "                                    + org.owasp.benchmark.helpers.Utils.TESTFILES_DIR\n" +
                "                                            .replace('\\\\', '/')\n" +
                "                                            .replace(' ', '_')\n" +
                "                                    + bar);\n" +
                "            java.io.File fileTarget = new java.io.File(fileURI);\n" +
                "            response.getWriter()\n" +
                "                    .println(\n" +
                "                            \"Access to file: '\"\n" +
                "                                    + org.owasp\n" +
                "                                            .esapi\n" +
                "                                            .ESAPI\n" +
                "                                            .encoder()\n" +
                "                                            .encodeForHTML(fileTarget.toString())\n" +
                "                                    + \"' created.\");\n" +
                "            if (fileTarget.exists()) {\n" +
                "                response.getWriter().println(\" And file already exists.\");\n" +
                "            } else {\n" +
                "                response.getWriter().println(\" But file doesn't exist yet.\");\n" +
                "            }\n" +
                "        } catch (java.net.URISyntaxException e) {\n" +
                "            throw new ServletException(e);\n" +
                "        }\n" +
                "    } // end doPost\n" +
                "```\n" +
                "\n" +
                "Vulnerability detected:\n" +
                "```\n" +
                "httpservlet-path-traversal: Detected a potential path traversal. A malicious actor could control the location of this file, to include going backwards in the directory with '../'. To address this, ensure that user-controlled variables in file paths are sanitized. You may also consider using a utility method such as org.apache.commons.io.FilenameUtils.getName(...) to only retrieve the file name from the path.\n" +
                "```\n" +
                "\n" +
                "Line with vulnerability:\n" +
                "```\n" +
                "            java.io.File fileTarget = new java.io.File(fileURI);\n" +
                "```\n" +
                "\n" +
                "Data-flow trace:\n" +
                "```\n" +
                "SOURCE: theCookie.getValue()\n" +
                "PROPAGATOR: param\n" +
                "PROPAGATOR: bar\n" +
                "PROPAGATOR: fileURI\n" +
                "SINK: fileTarget = new java.io.File(fileURI)\n" +
                "\n" +
                "```\n" +
                "\n" +
                "}, role=user, name=, additionalProperties={}}}], model=gpt-4o-2024-08-06, audio=, frequencyPenalty=, functionCall=, functions=, logitBias=, logprobs=, maxCompletionTokens=, maxTokens=, metadata=, modalities=, n=, parallelToolCalls=, prediction=, presencePenalty=, promptCacheKey=, reasoningEffort=, responseFormat=, safetyIdentifier=, seed=, serviceTier=, stop=, store=, streamOptions=, temperature=0.0, toolChoice=, tools=, topLogprobs=, topP=, user=, verbosity=, webSearchOptions=, additionalProperties={}}\n"] =
            "---\n" +
                    "overview: \"The code snippet is vulnerable to path traversal attacks, where a malicious user can manipulate file paths to access unauthorized directories or files. This vulnerability arises from insufficient validation of user-controlled input used in file path construction.\"\n" +
                    "\n" +
                    "explanation: \"In the provided code snippet, the vulnerability stems from the use of user-controlled input to construct a file path. The input is derived from a cookie value, which is decoded and used to form a URI for accessing files. The key issue is that the input is not properly sanitized or validated, allowing an attacker to potentially include path traversal sequences like '../' to navigate to directories outside the intended scope. This can lead to unauthorized access to sensitive files or system directories, posing a significant security risk. The data-flow trace indicates that the cookie value is propagated through several variables before being used in the construction of the file path, highlighting the importance of validating user input at each stage of data flow.\"\n" +
                    "\n" +
                    "example: |\n" +
                    "  ```\n" +
                    "  import java.io.File;\n" +
                    "  import java.net.URI;\n" +
                    "  import java.net.URISyntaxException;\n" +
                    "\n" +
                    "  public class PathTraversalExample {\n" +
                    "      public void accessFile(String userInput) throws URISyntaxException {\n" +
                    "          // Potentially unsafe user input\n" +
                    "          String filePath = \"/var/www/\" + userInput;\n" +
                    "\n" +
                    "          // Create a file object using the user-controlled path\n" +
                    "          File file = new File(new URI(\"file:\" + filePath));\n" +
                    "\n" +
                    "          // Check if the file exists\n" +
                    "          if (file.exists()) {\n" +
                    "              System.out.println(\"File exists: \" + file.getAbsolutePath());\n" +
                    "          } else {\n" +
                    "              System.out.println(\"File does not exist.\");\n" +
                    "          }\n" +
                    "      }\n" +
                    "  }\n" +
                    "  ```\n" +
                    "\n" +
                    "exampleDescription: \"This example demonstrates a path traversal vulnerability where user input is directly used to construct a file path. The userInput variable is concatenated with a base directory path, and a File object is created using this potentially unsafe path. Without proper validation, an attacker could manipulate userInput to include path traversal sequences, accessing files outside the intended directory. To mitigate this, user input should be sanitized to remove any path traversal characters, and libraries like Apache Commons IO can be used to safely handle file paths.\"\n"

        explanationCache["Body{messages=[ChatCompletionMessageParam{system=ChatCompletionSystemMessageParam{content=Content{text=You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.\n" +
                "\n" +
                "When providing a response, follow the below guidelines:\n" +
                "- The explanation must not exceed 750 words.\n" +
                "- Provide the explanation as a basic string value.\n" +
                "- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.\n" +
                "- Format the response as a YAML document using the schema below:\n" +
                "\n" +
                "---\n" +
                "overview: \"<overview of the detected vulnerability in 2-3 sentences>\"\n" +
                "explanation: \"<explanation of the vulnerability in the code snippet>\"\n" +
                "example: \"<generic code snippet along with a description of the code snippet>\"\n" +
                "exampleDescription: \"<description of example code snippet>\"\n" +
                "}, role=system, name=, additionalProperties={}}}, ChatCompletionMessageParam{user=ChatCompletionUserMessageParam{content=Content{text=Explain the vulnerability detected in the code snippet to a developer who has Intermediate experience with in software security.\n" +
                "\n" +
                "Code Snippet:\n" +
                "```\n" +
                "@Override\n" +
                "    public void doPost(HttpServletRequest request, HttpServletResponse response)\n" +
                "            throws ServletException, IOException {\n" +
                "        response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "\n" +
                "        String param = request.getParameter(\"BenchmarkTest01306\");\n" +
                "        if (param == null) param = \"\";\n" +
                "\n" +
                "        String bar = new Test().doSomething(request, param);\n" +
                "\n" +
                "        String sql = \"SELECT * from USERS where USERNAME=? and PASSWORD='\" + bar + \"'\";\n" +
                "\n" +
                "        try {\n" +
                "            java.sql.Connection connection =\n" +
                "                    org.owasp.benchmark.helpers.DatabaseHelper.getSqlConnection();\n" +
                "            java.sql.PreparedStatement statement =\n" +
                "                    connection.prepareStatement(sql, new String[] {\"Column1\", \"Column2\"});\n" +
                "            statement.setString(1, \"foo\");\n" +
                "            statement.execute();\n" +
                "            org.owasp.benchmark.helpers.DatabaseHelper.printResults(statement, sql, response);\n" +
                "        } catch (java.sql.SQLException e) {\n" +
                "            if (org.owasp.benchmark.helpers.DatabaseHelper.hideSQLErrors) {\n" +
                "                response.getWriter().println(\"Error processing request.\");\n" +
                "                return;\n" +
                "            } else throw new ServletException(e);\n" +
                "        }\n" +
                "    } // end doPost\n" +
                "```\n" +
                "\n" +
                "Vulnerability detected:\n" +
                "```\n" +
                "tainted-sql-from-http-request: Detected input from a HTTPServletRequest going into a SQL sink or statement. This could lead to SQL injection if variables in the SQL statement are not properly sanitized. Use parameterized SQL queries or properly sanitize user input instead.\n" +
                "```\n" +
                "\n" +
                "Line with vulnerability:\n" +
                "```\n" +
                "            java.sql.PreparedStatement statement =\n" +
                "                    connection.prepareStatement(sql, new String[] {\"Column1\", \"Column2\"});\n" +
                "```\n" +
                "\n" +
                "Data-flow trace:\n" +
                "```\n" +
                "SOURCE: request.getParameter(\"BenchmarkTest01306\")\n" +
                "PROPAGATOR: param\n" +
                "PROPAGATOR: bar\n" +
                "PROPAGATOR: sql\n" +
                "SINK: java.sql.PreparedStatement statement =\n" +
                "                    connection.prepareStatement(sql, new String[] {\"Column1\", \"Column2\"});\n" +
                "\n" +
                "```\n" +
                "\n" +
                "}, role=user, name=, additionalProperties={}}}], model=gpt-4o-2024-08-06, audio=, frequencyPenalty=, functionCall=, functions=, logitBias=, logprobs=, maxCompletionTokens=, maxTokens=, metadata=, modalities=, n=, parallelToolCalls=, prediction=, presencePenalty=, promptCacheKey=, reasoningEffort=, responseFormat=, safetyIdentifier=, seed=, serviceTier=, stop=, store=, streamOptions=, temperature=0.0, toolChoice=, tools=, topLogprobs=, topP=, user=, verbosity=, webSearchOptions=, additionalProperties={}}\n"] =
            "---\n" +
                    "overview: \"The code snippet is vulnerable to SQL injection due to unsanitized user input being directly used in a SQL query. This can allow attackers to manipulate the SQL query and potentially access or modify sensitive data in the database.\"\n" +
                    "\n" +
                    "explanation: \"In the provided code snippet, the vulnerability arises from the construction of a SQL query using unsanitized user input. The parameter 'BenchmarkTest01306' is retrieved from the HTTP request and is used to construct the SQL query without proper sanitization or validation. This input is then concatenated directly into the SQL query string, which is executed against the database. The use of concatenation in SQL queries with user input can lead to SQL injection, where an attacker can inject malicious SQL code to manipulate the query execution. This can result in unauthorized access to database information, data leakage, or even data manipulation. The vulnerability is exacerbated by the fact that the input is used in a sensitive part of the query, specifically the password field, which is often targeted by attackers. To mitigate this vulnerability, it is crucial to use parameterized queries, which separate SQL code from data, ensuring that user input is treated as data rather than executable code. This approach prevents SQL injection by ensuring that user input cannot alter the structure of the SQL query.\"\n" +
                    "\n" +
                    "example: \"```java\\nString userInput = request.getParameter(\\\"userInput\\\");\\nString query = \\\"SELECT * FROM users WHERE username=? AND password=?\\\";\\nPreparedStatement statement = connection.prepareStatement(query);\\nstatement.setString(1, username);\\nstatement.setString(2, userInput);\\nResultSet rs = statement.executeQuery();\\n```\"\n" +
                    "\n" +
                    "exampleDescription: \"This example demonstrates the use of parameterized queries to prevent SQL injection. Instead of concatenating user input directly into the SQL query string, placeholders ('?') are used in the query, and the actual values are set using the 'setString' method of the PreparedStatement. This ensures that user input is treated as data, not code, thus preventing SQL injection attacks.\"\n"

        explanationCache["Body{messages=[ChatCompletionMessageParam{system=ChatCompletionSystemMessageParam{content=Content{text=You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.\n" +
                "\n" +
                "When providing a response, follow the below guidelines:\n" +
                "- The explanation must not exceed 750 words.\n" +
                "- Provide the explanation as a basic string value.\n" +
                "- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.\n" +
                "- Format the response as a YAML document using the schema below:\n" +
                "\n" +
                "---\n" +
                "overview: \"<overview of the detected vulnerability in 2-3 sentences>\"\n" +
                "explanation: \"<explanation of the vulnerability in the code snippet>\"\n" +
                "example: \"<generic code snippet along with a description of the code snippet>\"\n" +
                "exampleDescription: \"<description of example code snippet>\"\n" +
                "}, role=system, name=, additionalProperties={}}}, ChatCompletionMessageParam{user=ChatCompletionUserMessageParam{content=Content{text=Explain the vulnerability detected in the code snippet to a developer who has Intermediate experience with in software security.\n" +
                "\n" +
                "Code Snippet:\n" +
                "```\n" +
                "@Override\n" +
                "    public void doPost(HttpServletRequest request, HttpServletResponse response)\n" +
                "            throws ServletException, IOException {\n" +
                "        response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "\n" +
                "        String param = \"\";\n" +
                "        if (request.getHeader(\"BenchmarkTest00138\") != null) {\n" +
                "            param = request.getHeader(\"BenchmarkTest00138\");\n" +
                "        }\n" +
                "\n" +
                "        // URL Decode the header value since req.getHeader() doesn't. Unlike req.getParameter().\n" +
                "        param = java.net.URLDecoder.decode(param, \"UTF-8\");\n" +
                "\n" +
                "        String bar;\n" +
                "\n" +
                "        // Simple ? condition that assigns constant to bar on true condition\n" +
                "        int num = 106;\n" +
                "\n" +
                "        bar = (7 * 18) + num > 200 ? \"This_should_always_happen\" : param;\n" +
                "\n" +
                "        org.owasp.benchmark.helpers.LDAPManager ads = new org.owasp.benchmark.helpers.LDAPManager();\n" +
                "        try {\n" +
                "            response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "            String base = \"ou=users,ou=system\";\n" +
                "            javax.naming.directory.SearchControls sc = new javax.naming.directory.SearchControls();\n" +
                "            sc.setSearchScope(javax.naming.directory.SearchControls.SUBTREE_SCOPE);\n" +
                "            String filter = \"(&(objectclass=person))(|(uid=\" + bar + \")(street={0}))\";\n" +
                "            Object[] filters = new Object[] {\"The streetz 4 Ms bar\"};\n" +
                "\n" +
                "            javax.naming.directory.DirContext ctx = ads.getDirContext();\n" +
                "            javax.naming.directory.InitialDirContext idc =\n" +
                "                    (javax.naming.directory.InitialDirContext) ctx;\n" +
                "            boolean found = false;\n" +
                "            javax.naming.NamingEnumeration<javax.naming.directory.SearchResult> results =\n" +
                "                    idc.search(base, filter, filters, sc);\n" +
                "            while (results.hasMore()) {\n" +
                "                javax.naming.directory.SearchResult sr =\n" +
                "                        (javax.naming.directory.SearchResult) results.next();\n" +
                "                javax.naming.directory.Attributes attrs = sr.getAttributes();\n" +
                "\n" +
                "                javax.naming.directory.Attribute attr = attrs.get(\"uid\");\n" +
                "                javax.naming.directory.Attribute attr2 = attrs.get(\"street\");\n" +
                "                if (attr != null) {\n" +
                "                    response.getWriter()\n" +
                "                            .println(\n" +
                "                                    \"LDAP query results:<br>\"\n" +
                "                                            + \"Record found with name \"\n" +
                "                                            + attr.get()\n" +
                "                                            + \"<br>\"\n" +
                "                                            + \"Address: \"\n" +
                "                                            + attr2.get()\n" +
                "                                            + \"<br>\");\n" +
                "                    // System.out.println(\"record found \" + attr.get());\n" +
                "                    found = true;\n" +
                "                }\n" +
                "            }\n" +
                "            if (!found) {\n" +
                "                response.getWriter()\n" +
                "                        .println(\n" +
                "                                \"LDAP query results: nothing found for query: \"\n" +
                "                                        + org.owasp.esapi.ESAPI.encoder().encodeForHTML(filter));\n" +
                "            }\n" +
                "        } catch (javax.naming.NamingException e) {\n" +
                "            throw new ServletException(e);\n" +
                "        } finally {\n" +
                "            try {\n" +
                "                ads.closeDirContext();\n" +
                "            } catch (Exception e) {\n" +
                "                throw new ServletException(e);\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "```\n" +
                "\n" +
                "Vulnerability detected:\n" +
                "```\n" +
                "tainted-ldapi-from-http-request: Detected input from a HTTPServletRequest going into an LDAP query. This could lead to LDAP injection if the input is not properly sanitized, which could result in attackers modifying objects in the LDAP tree structure. Ensure data passed to an LDAP query is not controllable or properly sanitize the data.\n" +
                "```\n" +
                "\n" +
                "Line with vulnerability:\n" +
                "```\n" +
                "                    idc.search(base, filter, filters, sc);\n" +
                "```\n" +
                "\n" +
                "Data-flow trace:\n" +
                "```\n" +
                "SOURCE: request\n" +
                "PROPAGATOR: param\n" +
                "PROPAGATOR: bar\n" +
                "PROPAGATOR: filter\n" +
                "SINK: idc.search(base, filter, filters, sc)\n" +
                "\n" +
                "```\n" +
                "\n" +
                "}, role=user, name=, additionalProperties={}}}], model=gpt-4o-2024-08-06, audio=, frequencyPenalty=, functionCall=, functions=, logitBias=, logprobs=, maxCompletionTokens=, maxTokens=, metadata=, modalities=, n=, parallelToolCalls=, prediction=, presencePenalty=, promptCacheKey=, reasoningEffort=, responseFormat=, safetyIdentifier=, seed=, serviceTier=, stop=, store=, streamOptions=, temperature=0.0, toolChoice=, tools=, topLogprobs=, topP=, user=, verbosity=, webSearchOptions=, additionalProperties={}}\n"] =
            "---\n" +
                    "overview: \"The code snippet is vulnerable to LDAP injection due to unsanitized input from an HTTP request being used in an LDAP query. This can allow attackers to manipulate LDAP queries and potentially modify or access unauthorized data in the LDAP directory.\"\n" +
                    "explanation: \"The vulnerability arises because the code takes input from an HTTP request header and uses it directly in constructing an LDAP query filter without proper sanitization. The input is first extracted from the request header 'BenchmarkTest00138', URL-decoded, and then conditionally assigned to the variable 'bar'. This variable is then used in the LDAP query filter string. If the input is maliciously crafted, it can alter the intended LDAP query logic, leading to LDAP injection. LDAP injection can allow attackers to execute arbitrary LDAP commands, potentially accessing or modifying sensitive data within the LDAP directory. The data flow trace shows the progression of the tainted data from the HTTP request to the LDAP query, highlighting the lack of validation or sanitization at each step. To mitigate this vulnerability, it is crucial to validate and sanitize all user inputs before using them in LDAP queries. This can be done by using whitelisting techniques, escaping special characters, or using parameterized queries where possible.\"\n" +
                    "example: \"```java\\nString userInput = request.getParameter(\\\"username\\\");\\nString sanitizedInput = org.owasp.esapi.ESAPI.encoder().encodeForLDAP(userInput);\\nString filter = \\\"(&(objectclass=person)(uid=\\\" + sanitizedInput + \\\"))\\\";\\njavax.naming.directory.DirContext ctx = ldapManager.getDirContext();\\njavax.naming.directory.SearchControls sc = new javax.naming.directory.SearchControls();\\nsc.setSearchScope(javax.naming.directory.SearchControls.SUBTREE_SCOPE);\\njavax.naming.NamingEnumeration<javax.naming.directory.SearchResult> results = ctx.search(\\\"ou=users,ou=system\\\", filter, sc);\\n```\"\n" +
                    "exampleDescription: \"This example demonstrates how to sanitize user input before using it in an LDAP query. The input is first retrieved from the HTTP request and then encoded using OWASP's ESAPI library to ensure it is safe for LDAP usage. The sanitized input is then used to construct the LDAP query filter, reducing the risk of LDAP injection by preventing malicious input from altering the query logic.\"\n"

        explanationCache["Body{messages=[ChatCompletionMessageParam{system=ChatCompletionSystemMessageParam{content=Content{text=You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.\n" +
                "\n" +
                "When providing a response, follow the below guidelines:\n" +
                "- The explanation must not exceed 750 words.\n" +
                "- Provide the explanation as a basic string value.\n" +
                "- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.\n" +
                "- Format the response as a YAML document using the schema below:\n" +
                "\n" +
                "---\n" +
                "overview: \"<overview of the detected vulnerability in 2-3 sentences>\"\n" +
                "explanation: \"<explanation of the vulnerability in the code snippet>\"\n" +
                "example: \"<generic code snippet along with a description of the code snippet>\"\n" +
                "exampleDescription: \"<description of example code snippet>\"\n" +
                "}, role=system, name=, additionalProperties={}}}, ChatCompletionMessageParam{user=ChatCompletionUserMessageParam{content=Content{text=Explain the vulnerability detected in the code snippet to a developer who has Intermediate experience with in software security.\n" +
                "\n" +
                "Code Snippet:\n" +
                "```\n" +
                "@Override\n" +
                "    public void doPost(HttpServletRequest request, HttpServletResponse response)\n" +
                "            throws ServletException, IOException {\n" +
                "        response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "\n" +
                "        String param = \"\";\n" +
                "        java.util.Enumeration<String> headers = request.getHeaders(\"Referer\");\n" +
                "\n" +
                "        if (headers != null && headers.hasMoreElements()) {\n" +
                "            param = headers.nextElement(); // just grab first element\n" +
                "        }\n" +
                "\n" +
                "        // URL Decode the header value since req.getHeaders() doesn't. Unlike req.getParameters().\n" +
                "        param = java.net.URLDecoder.decode(param, \"UTF-8\");\n" +
                "\n" +
                "        String bar;\n" +
                "\n" +
                "        // Simple ? condition that assigns param to bar on false condition\n" +
                "        int num = 106;\n" +
                "\n" +
                "        bar = (7 * 42) - num > 200 ? \"This should never happen\" : param;\n" +
                "\n" +
                "        response.setHeader(\"X-XSS-Protection\", \"0\");\n" +
                "        response.getWriter().print(bar);\n" +
                "    }\n" +
                "```\n" +
                "\n" +
                "Vulnerability detected:\n" +
                "```\n" +
                "no-direct-response-writer: Detected a request with potential user-input going into a OutputStream or Writer object. This bypasses any view or template environments, including HTML escaping, which may expose this application to cross-site scripting (XSS) vulnerabilities. Consider using a view technology such as JavaServer Faces (JSFs) which automatically escapes HTML views.\n" +
                "```\n" +
                "\n" +
                "Line with vulnerability:\n" +
                "```\n" +
                "        response.getWriter().print(bar);\n" +
                "```\n" +
                "\n" +
                "Data-flow trace:\n" +
                "```\n" +
                "SOURCE: request.getHeaders(\"Referer\")\n" +
                "PROPAGATOR: headers\n" +
                "PROPAGATOR: param\n" +
                "PROPAGATOR: bar\n" +
                "SINK: response.getWriter().print(bar)\n" +
                "\n" +
                "```\n" +
                "\n" +
                "}, role=user, name=, additionalProperties={}}}], model=gpt-4o-2024-08-06, audio=, frequencyPenalty=, functionCall=, functions=, logitBias=, logprobs=, maxCompletionTokens=, maxTokens=, metadata=, modalities=, n=, parallelToolCalls=, prediction=, presencePenalty=, promptCacheKey=, reasoningEffort=, responseFormat=, safetyIdentifier=, seed=, serviceTier=, stop=, store=, streamOptions=, temperature=0.0, toolChoice=, tools=, topLogprobs=, topP=, user=, verbosity=, webSearchOptions=, additionalProperties={}}\n"] =
            "---\n" +
                    "overview: \"The code snippet is vulnerable to cross-site scripting (XSS) due to direct output of user-controlled data without proper sanitization or escaping. This can lead to execution of malicious scripts in the user's browser.\"\n" +
                    "\n" +
                    "explanation: \"In the provided code snippet, the vulnerability arises from the way user input is handled and outputted. The `Referer` header from the HTTP request is retrieved and stored in the `param` variable. This header can be controlled by the user, meaning it can contain any data, including malicious scripts. The value of `param` is then URL-decoded and assigned to the `bar` variable, which is subsequently printed directly to the HTTP response using `response.getWriter().print(bar)`. This direct output of user-controlled data bypasses any HTML escaping mechanisms, making the application susceptible to XSS attacks. An attacker could craft a request with a malicious `Referer` header that includes JavaScript code, which would be executed in the context of the user's browser when they view the response. This can lead to various security issues, such as session hijacking, defacement, or redirection to malicious sites. To mitigate this vulnerability, it is crucial to sanitize and escape user input before outputting it to the response. Using a view technology like JavaServer Faces (JSF) or employing libraries that automatically handle HTML escaping can help prevent XSS by ensuring that any user input is properly sanitized before being included in the response.\"\n" +
                    "\n" +
                    "example: |\n" +
                    "  ```\n" +
                    "  // Vulnerable code\n" +
                    "  String userInput = request.getParameter(\"input\");\n" +
                    "  response.getWriter().print(userInput);\n" +
                    "\n" +
                    "  // Mitigated code using HTML escaping\n" +
                    "  String userInput = request.getParameter(\"input\");\n" +
                    "  String safeOutput = org.apache.commons.text.StringEscapeUtils.escapeHtml4(userInput);\n" +
                    "  response.getWriter().print(safeOutput);\n" +
                    "  ```\n" +
                    "\n" +
                    "exampleDescription: \"The vulnerable code directly prints user input retrieved from a request parameter to the response, which can lead to XSS if the input contains malicious scripts. The mitigated code uses Apache Commons Text's `StringEscapeUtils.escapeHtml4` method to escape HTML characters in the user input, ensuring that any potentially harmful scripts are neutralized before being outputted to the response.\"\n"


        /**
         *
         *
         * BEGINNER
         */

        explanationCache["Body{messages=[ChatCompletionMessageParam{system=ChatCompletionSystemMessageParam{content=Content{text=You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.\n" +
                "\n" +
                "When providing a response, follow the below guidelines:\n" +
                "- The explanation must not exceed 750 words.\n" +
                "- Provide the explanation as a basic string value.\n" +
                "- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.\n" +
                "- Format the response as a YAML document using the schema below:\n" +
                "\n" +
                "---\n" +
                "overview: \"<overview of the detected vulnerability in 2-3 sentences>\"\n" +
                "explanation: \"<explanation of the vulnerability in the code snippet>\"\n" +
                "example: \"<generic code snippet along with a description of the code snippet>\"\n" +
                "exampleDescription: \"<description of example code snippet>\"\n" +
                "}, role=system, name=, additionalProperties={}}}, ChatCompletionMessageParam{user=ChatCompletionUserMessageParam{content=Content{text=Explain the vulnerability detected in the code snippet to a developer who has Beginner experience with in software security.\n" +
                "\n" +
                "Code Snippet:\n" +
                "```\n" +
                "@Override\n" +
                "    public void doPost(HttpServletRequest request, HttpServletResponse response)\n" +
                "            throws ServletException, IOException {\n" +
                "        response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "\n" +
                "        String param = \"\";\n" +
                "        if (request.getHeader(\"BenchmarkTest00138\") != null) {\n" +
                "            param = request.getHeader(\"BenchmarkTest00138\");\n" +
                "        }\n" +
                "\n" +
                "        // URL Decode the header value since req.getHeader() doesn't. Unlike req.getParameter().\n" +
                "        param = java.net.URLDecoder.decode(param, \"UTF-8\");\n" +
                "\n" +
                "        String bar;\n" +
                "\n" +
                "        // Simple ? condition that assigns constant to bar on true condition\n" +
                "        int num = 106;\n" +
                "\n" +
                "        bar = (7 * 18) + num > 200 ? \"This_should_always_happen\" : param;\n" +
                "\n" +
                "        org.owasp.benchmark.helpers.LDAPManager ads = new org.owasp.benchmark.helpers.LDAPManager();\n" +
                "        try {\n" +
                "            response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "            String base = \"ou=users,ou=system\";\n" +
                "            javax.naming.directory.SearchControls sc = new javax.naming.directory.SearchControls();\n" +
                "            sc.setSearchScope(javax.naming.directory.SearchControls.SUBTREE_SCOPE);\n" +
                "            String filter = \"(&(objectclass=person))(|(uid=\" + bar + \")(street={0}))\";\n" +
                "            Object[] filters = new Object[] {\"The streetz 4 Ms bar\"};\n" +
                "\n" +
                "            javax.naming.directory.DirContext ctx = ads.getDirContext();\n" +
                "            javax.naming.directory.InitialDirContext idc =\n" +
                "                    (javax.naming.directory.InitialDirContext) ctx;\n" +
                "            boolean found = false;\n" +
                "            javax.naming.NamingEnumeration<javax.naming.directory.SearchResult> results =\n" +
                "                    idc.search(base, filter, filters, sc);\n" +
                "            while (results.hasMore()) {\n" +
                "                javax.naming.directory.SearchResult sr =\n" +
                "                        (javax.naming.directory.SearchResult) results.next();\n" +
                "                javax.naming.directory.Attributes attrs = sr.getAttributes();\n" +
                "\n" +
                "                javax.naming.directory.Attribute attr = attrs.get(\"uid\");\n" +
                "                javax.naming.directory.Attribute attr2 = attrs.get(\"street\");\n" +
                "                if (attr != null) {\n" +
                "                    response.getWriter()\n" +
                "                            .println(\n" +
                "                                    \"LDAP query results:<br>\"\n" +
                "                                            + \"Record found with name \"\n" +
                "                                            + attr.get()\n" +
                "                                            + \"<br>\"\n" +
                "                                            + \"Address: \"\n" +
                "                                            + attr2.get()\n" +
                "                                            + \"<br>\");\n" +
                "                    // System.out.println(\"record found \" + attr.get());\n" +
                "                    found = true;\n" +
                "                }\n" +
                "            }\n" +
                "            if (!found) {\n" +
                "                response.getWriter()\n" +
                "                        .println(\n" +
                "                                \"LDAP query results: nothing found for query: \"\n" +
                "                                        + org.owasp.esapi.ESAPI.encoder().encodeForHTML(filter));\n" +
                "            }\n" +
                "        } catch (javax.naming.NamingException e) {\n" +
                "            throw new ServletException(e);\n" +
                "        } finally {\n" +
                "            try {\n" +
                "                ads.closeDirContext();\n" +
                "            } catch (Exception e) {\n" +
                "                throw new ServletException(e);\n" +
                "            }\n" +
                "        }\n" +
                "    }\n" +
                "```\n" +
                "\n" +
                "Vulnerability detected:\n" +
                "```\n" +
                "tainted-ldapi-from-http-request: Detected input from a HTTPServletRequest going into an LDAP query. This could lead to LDAP injection if the input is not properly sanitized, which could result in attackers modifying objects in the LDAP tree structure. Ensure data passed to an LDAP query is not controllable or properly sanitize the data.\n" +
                "```\n" +
                "\n" +
                "Line with vulnerability:\n" +
                "```\n" +
                "                    idc.search(base, filter, filters, sc);\n" +
                "```\n" +
                "\n" +
                "Data-flow trace:\n" +
                "```\n" +
                "SOURCE: request\n" +
                "PROPAGATOR: param\n" +
                "PROPAGATOR: bar\n" +
                "PROPAGATOR: filter\n" +
                "SINK: idc.search(base, filter, filters, sc)\n" +
                "\n" +
                "```\n" +
                "\n" +
                "}, role=user, name=, additionalProperties={}}}], model=gpt-4o-2024-08-06, audio=, frequencyPenalty=, functionCall=, functions=, logitBias=, logprobs=, maxCompletionTokens=, maxTokens=, metadata=, modalities=, n=, parallelToolCalls=, prediction=, presencePenalty=, promptCacheKey=, reasoningEffort=, responseFormat=, safetyIdentifier=, seed=, serviceTier=, stop=, store=, streamOptions=, temperature=0.0, toolChoice=, tools=, topLogprobs=, topP=, user=, verbosity=, webSearchOptions=, additionalProperties={}}\n"] =
            "---\n" +
                    "overview: \"The code snippet contains a vulnerability related to LDAP injection, where unsanitized input from an HTTP request is used in an LDAP query. This can allow attackers to manipulate the LDAP query and potentially access or modify sensitive data in the LDAP directory.\"\n" +
                    "\n" +
                    "explanation: \"The vulnerability arises because the code uses input from an HTTP request header directly in an LDAP query without proper sanitization. The input is retrieved from the request header 'BenchmarkTest00138', URL-decoded, and then used to construct an LDAP filter string. This filter string is used in the LDAP search operation, which can be exploited by an attacker to perform LDAP injection. LDAP injection occurs when an attacker is able to manipulate the LDAP query by injecting malicious input, potentially allowing them to access unauthorized data or modify the LDAP directory structure. In this code, the variable 'bar' is assigned a value based on a conditional operation, but if the condition is false, it directly uses the unsanitized input from the request header. This input is then incorporated into the LDAP filter string, making the application vulnerable to LDAP injection attacks. To mitigate this vulnerability, it is crucial to validate and sanitize all user inputs before using them in LDAP queries. This can be done by using whitelisting techniques, escaping special characters, or using parameterized queries to ensure that the input cannot alter the intended query structure.\"\n" +
                    "\n" +
                    "example: |\n" +
                    "  ```\n" +
                    "  // Example of vulnerable code\n" +
                    "  String userInput = request.getParameter(\"username\");\n" +
                    "  String ldapFilter = \"(&(objectclass=person)(uid=\" + userInput + \"))\";\n" +
                    "  NamingEnumeration<SearchResult> results = ctx.search(base, ldapFilter, sc);\n" +
                    "\n" +
                    "  // Example of mitigated code\n" +
                    "  String userInput = request.getParameter(\"username\");\n" +
                    "  if (!isValidInput(userInput)) {\n" +
                    "      throw new IllegalArgumentException(\"Invalid input\");\n" +
                    "  }\n" +
                    "  String ldapFilter = \"(&(objectclass=person)(uid=\" + escapeLDAPSearchFilter(userInput) + \"))\";\n" +
                    "  NamingEnumeration<SearchResult> results = ctx.search(base, ldapFilter, sc);\n" +
                    "  ```\n" +
                    "\n" +
                    "exampleDescription: \"The first example demonstrates a vulnerable LDAP query where user input is directly used in the filter string, making it susceptible to LDAP injection. The second example shows a mitigated version where the input is validated and sanitized using a custom function 'escapeLDAPSearchFilter' to prevent injection attacks. The 'isValidInput' function checks if the input meets expected criteria, ensuring that only safe and intended data is used in the LDAP query.\"\n"

        explanationCache["Body{messages=[ChatCompletionMessageParam{system=ChatCompletionSystemMessageParam{content=Content{text=You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.\n" +
                "\n" +
                "When providing a response, follow the below guidelines:\n" +
                "- The explanation must not exceed 750 words.\n" +
                "- Provide the explanation as a basic string value.\n" +
                "- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.\n" +
                "- Format the response as a YAML document using the schema below:\n" +
                "\n" +
                "---\n" +
                "overview: \"<overview of the detected vulnerability in 2-3 sentences>\"\n" +
                "explanation: \"<explanation of the vulnerability in the code snippet>\"\n" +
                "example: \"<generic code snippet along with a description of the code snippet>\"\n" +
                "exampleDescription: \"<description of example code snippet>\"\n" +
                "}, role=system, name=, additionalProperties={}}}, ChatCompletionMessageParam{user=ChatCompletionUserMessageParam{content=Content{text=Explain the vulnerability detected in the code snippet to a developer who has Beginner experience with in software security.\n" +
                "\n" +
                "Code Snippet:\n" +
                "```\n" +
                "@Override\n" +
                "    public void doPost(HttpServletRequest request, HttpServletResponse response)\n" +
                "            throws ServletException, IOException {\n" +
                "        response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "\n" +
                "        String param = \"\";\n" +
                "        java.util.Enumeration<String> headers = request.getHeaders(\"Referer\");\n" +
                "\n" +
                "        if (headers != null && headers.hasMoreElements()) {\n" +
                "            param = headers.nextElement(); // just grab first element\n" +
                "        }\n" +
                "\n" +
                "        // URL Decode the header value since req.getHeaders() doesn't. Unlike req.getParameters().\n" +
                "        param = java.net.URLDecoder.decode(param, \"UTF-8\");\n" +
                "\n" +
                "        String bar;\n" +
                "\n" +
                "        // Simple ? condition that assigns param to bar on false condition\n" +
                "        int num = 106;\n" +
                "\n" +
                "        bar = (7 * 42) - num > 200 ? \"This should never happen\" : param;\n" +
                "\n" +
                "        response.setHeader(\"X-XSS-Protection\", \"0\");\n" +
                "        response.getWriter().print(bar);\n" +
                "    }\n" +
                "```\n" +
                "\n" +
                "Vulnerability detected:\n" +
                "```\n" +
                "no-direct-response-writer: Detected a request with potential user-input going into a OutputStream or Writer object. This bypasses any view or template environments, including HTML escaping, which may expose this application to cross-site scripting (XSS) vulnerabilities. Consider using a view technology such as JavaServer Faces (JSFs) which automatically escapes HTML views.\n" +
                "```\n" +
                "\n" +
                "Line with vulnerability:\n" +
                "```\n" +
                "        response.getWriter().print(bar);\n" +
                "```\n" +
                "\n" +
                "Data-flow trace:\n" +
                "```\n" +
                "SOURCE: request.getHeaders(\"Referer\")\n" +
                "PROPAGATOR: headers\n" +
                "PROPAGATOR: param\n" +
                "PROPAGATOR: bar\n" +
                "SINK: response.getWriter().print(bar)\n" +
                "\n" +
                "```\n" +
                "\n" +
                "}, role=user, name=, additionalProperties={}}}], model=gpt-4o-2024-08-06, audio=, frequencyPenalty=, functionCall=, functions=, logitBias=, logprobs=, maxCompletionTokens=, maxTokens=, metadata=, modalities=, n=, parallelToolCalls=, prediction=, presencePenalty=, promptCacheKey=, reasoningEffort=, responseFormat=, safetyIdentifier=, seed=, serviceTier=, stop=, store=, streamOptions=, temperature=0.0, toolChoice=, tools=, topLogprobs=, topP=, user=, verbosity=, webSearchOptions=, additionalProperties={}}\n"] =
            "---\n" +
                    "overview: \"The code snippet is vulnerable to Cross-Site Scripting (XSS) due to direct output of user-controlled data without proper sanitization or escaping. This can allow attackers to inject malicious scripts into web pages viewed by other users.\"\n" +
                    "\n" +
                    "explanation: \"In the provided code snippet, the vulnerability arises from the way user input is handled and outputted. The 'Referer' header from the HTTP request is retrieved and stored in the 'param' variable. This value is then URL-decoded and conditionally assigned to the 'bar' variable, which is subsequently printed directly to the HTTP response using 'response.getWriter().print(bar)'. Since the 'Referer' header can be controlled by the user, it is possible for an attacker to inject malicious JavaScript code into this header. When this unescaped and unsanitized data is printed directly to the response, it can execute in the context of the user's browser, leading to an XSS attack. This type of vulnerability can be exploited to steal session cookies, deface websites, or redirect users to malicious sites. The absence of HTML escaping or sanitization before outputting the data is the root cause of this vulnerability.\"\n" +
                    "\n" +
                    "example: |\n" +
                    "  ```\n" +
                    "  String userInput = request.getParameter(\"input\");\n" +
                    "  response.getWriter().print(userInput);\n" +
                    "  ```\n" +
                    "\n" +
                    "exampleDescription: \"In this example, user input is directly printed to the HTTP response without any sanitization or escaping. If the 'input' parameter contains malicious JavaScript code, it will be executed in the user's browser, leading to an XSS vulnerability. To mitigate this, user input should be properly sanitized and escaped before being included in the response, or a templating engine that automatically handles escaping should be used.\"\n"


        explanationCache["Body{messages=[ChatCompletionMessageParam{system=ChatCompletionSystemMessageParam{content=Content{text=You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.\n" +
                "\n" +
                "When providing a response, follow the below guidelines:\n" +
                "- The explanation must not exceed 750 words.\n" +
                "- Provide the explanation as a basic string value.\n" +
                "- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.\n" +
                "- Format the response as a YAML document using the schema below:\n" +
                "\n" +
                "---\n" +
                "overview: \"<overview of the detected vulnerability in 2-3 sentences>\"\n" +
                "explanation: \"<explanation of the vulnerability in the code snippet>\"\n" +
                "example: \"<generic code snippet along with a description of the code snippet>\"\n" +
                "exampleDescription: \"<description of example code snippet>\"\n" +
                "}, role=system, name=, additionalProperties={}}}, ChatCompletionMessageParam{user=ChatCompletionUserMessageParam{content=Content{text=Explain the vulnerability detected in the code snippet to a developer who has Beginner experience with in software security.\n" +
                "\n" +
                "Code Snippet:\n" +
                "```\n" +
                "@Override\n" +
                "    public void doPost(HttpServletRequest request, HttpServletResponse response)\n" +
                "            throws ServletException, IOException {\n" +
                "        response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "\n" +
                "        String param = request.getParameter(\"BenchmarkTest01306\");\n" +
                "        if (param == null) param = \"\";\n" +
                "\n" +
                "        String bar = new Test().doSomething(request, param);\n" +
                "\n" +
                "        String sql = \"SELECT * from USERS where USERNAME=? and PASSWORD='\" + bar + \"'\";\n" +
                "\n" +
                "        try {\n" +
                "            java.sql.Connection connection =\n" +
                "                    org.owasp.benchmark.helpers.DatabaseHelper.getSqlConnection();\n" +
                "            java.sql.PreparedStatement statement =\n" +
                "                    connection.prepareStatement(sql, new String[] {\"Column1\", \"Column2\"});\n" +
                "            statement.setString(1, \"foo\");\n" +
                "            statement.execute();\n" +
                "            org.owasp.benchmark.helpers.DatabaseHelper.printResults(statement, sql, response);\n" +
                "        } catch (java.sql.SQLException e) {\n" +
                "            if (org.owasp.benchmark.helpers.DatabaseHelper.hideSQLErrors) {\n" +
                "                response.getWriter().println(\"Error processing request.\");\n" +
                "                return;\n" +
                "            } else throw new ServletException(e);\n" +
                "        }\n" +
                "    } // end doPost\n" +
                "```\n" +
                "\n" +
                "Vulnerability detected:\n" +
                "```\n" +
                "tainted-sql-from-http-request: Detected input from a HTTPServletRequest going into a SQL sink or statement. This could lead to SQL injection if variables in the SQL statement are not properly sanitized. Use parameterized SQL queries or properly sanitize user input instead.\n" +
                "```\n" +
                "\n" +
                "Line with vulnerability:\n" +
                "```\n" +
                "            java.sql.PreparedStatement statement =\n" +
                "                    connection.prepareStatement(sql, new String[] {\"Column1\", \"Column2\"});\n" +
                "```\n" +
                "\n" +
                "Data-flow trace:\n" +
                "```\n" +
                "SOURCE: request.getParameter(\"BenchmarkTest01306\")\n" +
                "PROPAGATOR: param\n" +
                "PROPAGATOR: bar\n" +
                "PROPAGATOR: sql\n" +
                "SINK: java.sql.PreparedStatement statement =\n" +
                "                    connection.prepareStatement(sql, new String[] {\"Column1\", \"Column2\"});\n" +
                "\n" +
                "```\n" +
                "\n" +
                "}, role=user, name=, additionalProperties={}}}], model=gpt-4o-2024-08-06, audio=, frequencyPenalty=, functionCall=, functions=, logitBias=, logprobs=, maxCompletionTokens=, maxTokens=, metadata=, modalities=, n=, parallelToolCalls=, prediction=, presencePenalty=, promptCacheKey=, reasoningEffort=, responseFormat=, safetyIdentifier=, seed=, serviceTier=, stop=, store=, streamOptions=, temperature=0.0, toolChoice=, tools=, topLogprobs=, topP=, user=, verbosity=, webSearchOptions=, additionalProperties={}}\n"] =
            "---\n" +
                    "overview: \"The code snippet contains a SQL injection vulnerability due to unsanitized user input being directly used in a SQL query. This can allow attackers to manipulate the SQL query and potentially access or modify sensitive data in the database.\"\n" +
                    "\n" +
                    "explanation: \"In the provided code snippet, user input is retrieved from an HTTP request using `request.getParameter(\"BenchmarkTest01306\")`. This input is then used to construct a SQL query without proper sanitization or validation. The SQL query is constructed as a string that includes the user input directly, which is a common pattern that leads to SQL injection vulnerabilities. SQL injection occurs when an attacker is able to manipulate the SQL query by injecting malicious SQL code through unsanitized input fields. This can lead to unauthorized access to the database, data leakage, or data manipulation. The vulnerability arises because the SQL query uses string concatenation to include user input, which allows attackers to alter the query structure. To mitigate this vulnerability, it is recommended to use parameterized queries, which separate SQL code from data, ensuring that user input is treated as data rather than executable code. This prevents attackers from altering the SQL query structure.\"\n" +
                    "\n" +
                    "example: \"```java\\nString userInput = request.getParameter(\\\"userInput\\\");\\nString sql = \\\"SELECT * FROM users WHERE username = ?\\\";\\nPreparedStatement statement = connection.prepareStatement(sql);\\nstatement.setString(1, userInput);\\nResultSet rs = statement.executeQuery();\\n```\"\n" +
                    "\n" +
                    "exampleDescription: \"This example demonstrates the use of a parameterized query to prevent SQL injection. Instead of concatenating user input directly into the SQL query, a placeholder (`?`) is used in the query string. The `PreparedStatement` object is then used to safely insert the user input into the query, ensuring that it is treated as data rather than executable code. This approach effectively mitigates the risk of SQL injection by preventing attackers from altering the query structure.\"\n"

        explanationCache["Body{messages=[ChatCompletionMessageParam{system=ChatCompletionSystemMessageParam{content=Content{text=You are an assistant with expertise in explaining software security vulnerabilities in code snippets. You will be given a code snippet and the result from a static analysis security testing tool for the code snippet. Your task is to explain the static analysis result to a software developer based on their estimated their software security experience out of 10. Provide information about the underlying cause, consequences and mitigation strategies for the reported vulnerability.\n" +
                "\n" +
                "When providing a response, follow the below guidelines:\n" +
                "- The explanation must not exceed 750 words.\n" +
                "- Provide the explanation as a basic string value.\n" +
                "- Do not add any other unique characters to the block section, i.e.: triple backticks or triple quotes. Do not include scalars.\n" +
                "- Format the response as a YAML document using the schema below:\n" +
                "\n" +
                "---\n" +
                "overview: \"<overview of the detected vulnerability in 2-3 sentences>\"\n" +
                "explanation: \"<explanation of the vulnerability in the code snippet>\"\n" +
                "example: \"<generic code snippet along with a description of the code snippet>\"\n" +
                "exampleDescription: \"<description of example code snippet>\"\n" +
                "}, role=system, name=, additionalProperties={}}}, ChatCompletionMessageParam{user=ChatCompletionUserMessageParam{content=Content{text=Explain the vulnerability detected in the code snippet to a developer who has Beginner experience with in software security.\n" +
                "\n" +
                "Code Snippet:\n" +
                "```\n" +
                "@Override\n" +
                "    public void doPost(HttpServletRequest request, HttpServletResponse response)\n" +
                "            throws ServletException, IOException {\n" +
                "        response.setContentType(\"text/html;charset=UTF-8\");\n" +
                "\n" +
                "        javax.servlet.http.Cookie[] theCookies = request.getCookies();\n" +
                "\n" +
                "        String param = \"noCookieValueSupplied\";\n" +
                "        if (theCookies != null) {\n" +
                "            for (javax.servlet.http.Cookie theCookie : theCookies) {\n" +
                "                if (theCookie.getName().equals(\"BenchmarkTest00951\")) {\n" +
                "                    param = java.net.URLDecoder.decode(theCookie.getValue(), \"UTF-8\");\n" +
                "                    break;\n" +
                "                }\n" +
                "            }\n" +
                "        }\n" +
                "\n" +
                "        String bar = new Test().doSomething(request, param);\n" +
                "\n" +
                "        // FILE URIs are tricky because they are different between Mac and Windows because of lack\n" +
                "        // of standardization.\n" +
                "        // Mac requires an extra slash for some reason.\n" +
                "        String startURIslashes = \"\";\n" +
                "        if (System.getProperty(\"os.name\").indexOf(\"Windows\") != -1)\n" +
                "            if (System.getProperty(\"os.name\").indexOf(\"Windows\") != -1) startURIslashes = \"/\";\n" +
                "            else startURIslashes = \"//\";\n" +
                "\n" +
                "        try {\n" +
                "            java.net.URI fileURI =\n" +
                "                    new java.net.URI(\n" +
                "                            \"file:\"\n" +
                "                                    + startURIslashes\n" +
                "                                    + org.owasp.benchmark.helpers.Utils.TESTFILES_DIR\n" +
                "                                            .replace('\\\\', '/')\n" +
                "                                            .replace(' ', '_')\n" +
                "                                    + bar);\n" +
                "            java.io.File fileTarget = new java.io.File(fileURI);\n" +
                "            response.getWriter()\n" +
                "                    .println(\n" +
                "                            \"Access to file: '\"\n" +
                "                                    + org.owasp\n" +
                "                                            .esapi\n" +
                "                                            .ESAPI\n" +
                "                                            .encoder()\n" +
                "                                            .encodeForHTML(fileTarget.toString())\n" +
                "                                    + \"' created.\");\n" +
                "            if (fileTarget.exists()) {\n" +
                "                response.getWriter().println(\" And file already exists.\");\n" +
                "            } else {\n" +
                "                response.getWriter().println(\" But file doesn't exist yet.\");\n" +
                "            }\n" +
                "        } catch (java.net.URISyntaxException e) {\n" +
                "            throw new ServletException(e);\n" +
                "        }\n" +
                "    } // end doPost\n" +
                "```\n" +
                "\n" +
                "Vulnerability detected:\n" +
                "```\n" +
                "httpservlet-path-traversal: Detected a potential path traversal. A malicious actor could control the location of this file, to include going backwards in the directory with '../'. To address this, ensure that user-controlled variables in file paths are sanitized. You may also consider using a utility method such as org.apache.commons.io.FilenameUtils.getName(...) to only retrieve the file name from the path.\n" +
                "```\n" +
                "\n" +
                "Line with vulnerability:\n" +
                "```\n" +
                "            java.io.File fileTarget = new java.io.File(fileURI);\n" +
                "```\n" +
                "\n" +
                "Data-flow trace:\n" +
                "```\n" +
                "SOURCE: theCookie.getValue()\n" +
                "PROPAGATOR: param\n" +
                "PROPAGATOR: bar\n" +
                "PROPAGATOR: fileURI\n" +
                "SINK: fileTarget = new java.io.File(fileURI)\n" +
                "\n" +
                "```\n" +
                "\n" +
                "}, role=user, name=, additionalProperties={}}}], model=gpt-4o-2024-08-06, audio=, frequencyPenalty=, functionCall=, functions=, logitBias=, logprobs=, maxCompletionTokens=, maxTokens=, metadata=, modalities=, n=, parallelToolCalls=, prediction=, presencePenalty=, promptCacheKey=, reasoningEffort=, responseFormat=, safetyIdentifier=, seed=, serviceTier=, stop=, store=, streamOptions=, temperature=0.0, toolChoice=, tools=, topLogprobs=, topP=, user=, verbosity=, webSearchOptions=, additionalProperties={}}\n"] =
            "---\n" +
                    "overview: \"The code snippet contains a path traversal vulnerability, which allows a malicious user to manipulate file paths and potentially access unauthorized files on the server. This occurs when user input is used to construct file paths without proper validation or sanitization.\"\n" +
                    "\n" +
                    "explanation: \"In the provided code snippet, the vulnerability arises from the use of user-controlled input to construct a file path. The value of a cookie named 'BenchmarkTest00951' is retrieved and decoded, then used to form a URI for accessing a file. This value is not validated or sanitized, meaning a malicious user could supply a value that includes path traversal characters like '../', allowing them to navigate the file system and access files outside the intended directory. The data-flow trace shows how the cookie value propagates through the code, ultimately influencing the file path used in the 'java.io.File' constructor. This can lead to unauthorized access to sensitive files, potentially exposing confidential information or allowing further exploitation of the system.\"\n" +
                    "\n" +
                    "example: \"```java\\nString userInput = request.getParameter(\\\"filePath\\\");\\njava.io.File file = new java.io.File(\\\"/var/www/\\\" + userInput);\\n```\\nIn this example, the 'userInput' parameter is directly appended to a base directory path to create a 'File' object. If 'userInput' contains path traversal sequences like '../', it could lead to accessing files outside the '/var/www/' directory.\"\n" +
                    "\n" +
                    "exampleDescription: \"The example demonstrates a similar path traversal vulnerability where user input is used to construct a file path without validation. This can be exploited by an attacker to access files outside the intended directory, posing a security risk. To mitigate this, user input should be sanitized to remove or neutralize path traversal characters, and only allow access to specific, predefined directories or files.\"\n"


        if (explanationCache.containsKey(params._body().toString())) {

            println(
                "Cache: >>>>>>>>>>>>>>>>>>>>>>>>>>>\n" +
                        //params._body().toString() +
                        "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>"
                        + "\n<<<<<<<<<<<<<<<<<<<<<<<\n" +
                        explanationCache[params._body().toString()] + "\n<<<<<<<<<<<<<<<<<<<<<<<"
            )
            return explanationCache[params._body().toString()]!!
        } else {
            explanationCache[params._body().toString()] =
                chatCompletion.choices().first().message()._content().toString()

            println(
                ">>>>>>>>>>>>>>>>>>>>>>>>>>>\n" +
                      //  params._body().toString() + "\n>>>>>>>>>>>>>>>>>>>>>>>>>>>" +
                        "\n<<<<<<<<<<<<<<<<<<<<<<<\n" +
                        explanationCache[params._body().toString()] + "\n<<<<<<<<<<<<<<<<<<<<<<<"
            )


            return explanationCache[params._body().toString()]!!
        }
    }


    /**
     * Sends the prompts to LLM based on the expertise level and parses the response for the explanation of SAST issue
     */
    fun getExplanation(issue: Issue?, project: Project, experienceLevel: String): String? {

        val requestBody = buildRequestBody(
            PromptTemplate.getSystemPrompt(),
            PromptTemplate.buildUserPrompt(issue, experienceLevel, project),
            "0.0"
        )

        if (explanationCache.containsKey(requestBody)) {
            return explanationCache[requestBody]!!
        } else {
            explanationCache[requestBody] = sendRequest(requestBody).replace("\"", "\\\"")

            return explanationCache[requestBody]!!
        }
    }

    fun updateExplanation(issue: Issue, project: Project): String? {
        val level = PropertiesComponent.getInstance(project).getValue("Fixmysast.expertiseValue")

        val requestBody = buildRequestBody(
            PromptTemplate.getSystemPrompt(),
            PromptTemplate.buildUserPrompt(issue, level, project), "0.0"
        )
        explanationCache[requestBody] = sendRequest(requestBody)

        return explanationCache[requestBody]!!
    }

    fun sendRequest(requestBody: String): String {

        //val future = CompletableFuture<String>()

        val llmConfig = LlmConfig()

        try {
            val response = HttpService.postJson(
                llmConfig,
                requestBody
            )

            return parseResponse(response)!!
            // future.complete(response)
        } catch (e: Exception) {
            e.printStackTrace()
            "LLM error: ${e.message ?: e.toString()}"
            "No response received"
            return "No response received"
        }
    }

    /**
     * Build the complete prompt by combining the system and user prompts
     */
    private fun buildRequestBody(systemPrompt: String, userPrompt: String, temperature: String): String {
        val usrPromptJson = mapper.writeValueAsString(userPrompt)
        val sysPromptJson = mapper.writeValueAsString(systemPrompt)
        return """
      {
        "messages": [
          {"role": "system", "content": $sysPromptJson},
          {"role": "user", "content": $usrPromptJson}
        ],
        "temperature": $temperature
      }
  """.trimIndent()
    }

    /**
     * Parses the given responses by the LLM. The explanation is in the field choices -> message -> content
     */
    private fun parseResponse(response: String): String? {
        val result: Map<String, Any> = mapper.readValue(response)
        val choices = result["choices"] as? List<Map<String, Any>>
        val message = choices?.firstOrNull()?.get("message") as? Map<String, Any>

        println("%%%%%%%%%\n" + message?.get("content") as? String)
        return message?.get("content") as? String
    }
}