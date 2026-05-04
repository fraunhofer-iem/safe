import json
import requests
import sys

def test_explain_endpoint():
    url = "http://127.0.0.1:8800/explain"

    # Updated payload mapping to the new ExplainerRequest BaseModel
    payload = {
        "rootpath": "/home/schiggy/IdeaProjects/fraunhofer/safe",
        "filepath": "agentic-explainer/fastapi_main.py",
        "message": "Potential SQL Injection vulnerability detected in user input processing.",
        "cwe": "CWE-89",
        "rule_id": "java-sqli-01",
        "severity": "CRITICAL",
        "startLine": 42,
        "snippet": "user_input = request.payload.get('user_id')\ndb.execute(f'SELECT * FROM users WHERE id = {user_input}')",
        # Updated to "traces" to match the Kotlin data-flow format if applicable,
        # or leave as taint_flow depending on what your model expects right now
        "taint_flow": [
            {
                "description": "User input flow",
                "steps": [
                    {
                        "filePath": "agentic-explainer/fastapi_main.py",
                        "startLine": 41,
                        "message": "User input read from request payload"
                    },
                    {
                        "filePath": "agentic-explainer/fastapi_main.py",
                        "startLine": 42,
                        "message": "Unsanitized input used in SQL query"
                    }
                ]
            }
        ]
    }

    print("🚀 Sending request to SAFE Agent...")
    print("-" * 50)

    try:
        # stream=True is critical for reading the response as it arrives
        with requests.post(url, json=payload, stream=True, headers={"Accept": "text/event-stream"}) as response:
            response.raise_for_status()

            # To store the accumulated JSON string just in case you want to parse it at the end
            accumulated_json = ""

            # Read the stream line by line
            for line in response.iter_lines():
                if line:
                    decoded_line = line.decode("utf-8")

                    # SSE format specifies data starts with "data: "
                    if decoded_line.startswith("data: "):
                        json_str = decoded_line[6:] # Strip "data: "

                        try:
                            event = json.loads(json_str)
                            event_type = event.get("type")

                            # Handle different event types
                            if event_type == "tool_start":
                                tool_name = event.get("tool")
                                tool_input = event.get("input", "")
                                print(f"\n\n[🔧 TOOL CALLED] {tool_name}")
                                print(f"   Input: {tool_input}\n")

                            elif event_type == "token":
                                content = event.get("content", "")
                                # Unescape newlines that were safely packed for JSON
                                content = content.replace("\\n", "\n")

                                accumulated_json += content

                                # Print to console without adding a newline, and flush immediately
                                sys.stdout.write(content)
                                sys.stdout.flush()

                            elif event_type == "done":
                                print("\n\n" + "-" * 50)
                                print("✅ Agent finished processing.")

                                # Optional: If you want to see the final assembled JSON parsed natively:
                                # print("\n[Parsed Final Response Object]")
                                # try:
                                #     final_obj = json.loads(accumulated_json)
                                #     print(json.dumps(final_obj, indent=2))
                                # except Exception as parse_e:
                                #     print("Could not parse final accumulated JSON:", parse_e)

                            elif event_type == "error":
                                print(f"\n\n❌ ERROR: {event.get('message')}")

                        except json.JSONDecodeError:
                            print(f"\n[Warning] Failed to parse JSON from stream: {json_str}")

    except requests.exceptions.RequestException as e:
        print(f"Connection failed: {e}")

if __name__ == "__main__":
    test_explain_endpoint()