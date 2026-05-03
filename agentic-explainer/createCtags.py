import json
import subprocess

#
# Universal Ctags have to be installed
#

def build_index(project_root, output_file_path):
    print(f"Indexing {project_root}...")

    # Recursive ctags command
    # -R: Recursive
    # --output-format=json: Machine readable
    # --fields=+ne: Line number + End line number
    # --sort=no: Faster, we don't need alphabetical order
    cmd = [
        "ctags",
        "-R",
        "--output-format=json",
        "--fields=+neS",
        "--sort=no",
        project_root
    ]

    try:
        result = subprocess.run(cmd, capture_output=True, text=True, check=False)

        if result.returncode != 0:
            print(f"Error running ctags: {result.stderr}")
            return

        raw_lines = result.stdout.strip().split('\n')
        print(f"Raw output: {len(raw_lines)} tags found.")

        # We will store a Dictionary for O(1) lookup speed:
        # Structure: { "filename": [ {tag1}, {tag2} ] }
        db = {}

        for line in raw_lines:
            if not line: continue
            try:
                tag = json.loads(line)

                # Clean up the path to be relative or absolute based on your preference
                # ctags usually returns paths relative to where you ran it, or absolute if you gave absolute arg
                file_path = tag.get("path")

                if not file_path: continue

                # Filter out noise (we only want structural elements)
                kind = tag.get("kind")
                if kind not in ['class', 'method', 'function', 'interface', 'constructor']:
                    continue

                if file_path not in db:
                    db[file_path] = []

                # Store only what we need to save space
                entry = {
                    "name": tag.get("name"),
                    "kind": kind,
                    "signature": tag.get("signature", ""),
                    "start": tag.get("line"),
                    "end": tag.get("end")
                }
                db[file_path].append(entry)

            except json.JSONDecodeError:
                continue

        # Save to disk
        with open(output_file_path, "w") as f:
            json.dump(db, f, indent=2)

        print(f"Successfully saved index to {output_file_path}. Indexed {len(db)} files.")

    except FileNotFoundError:
        print("Error: 'ctags' not found. Install 'universal-ctags'.")

# if __name__ == "__main__":
#     project_root = "/home/schiggy/benchmarks/RAG/owasp/testcode" # Adjust this to your actual root
#     output_file_path = "code_structure_db.json"
#     build_index(project_root, output_file_path)
