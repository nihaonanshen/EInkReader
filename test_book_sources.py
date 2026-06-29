import json, re, sys, os, pathlib

def load_json(path):
    # Translate Windows path to POSIX if needed
    path = path.replace('\\', '/')
    # Expand ~ and environment variables
    path = os.path.expanduser(path)
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    # Remove line-number prefixes like "1|"
    lines = content.splitlines()
    cleaned = [re.sub(r'^\d+\|', '', line) for line in lines]
    json_str = ''.join(cleaned)
    # Extract the first [...] array
    start = json_str.find('[')
    end = json_str.rfind(']')
    if start == -1 or end == -1:
        print(f"[ERROR] Could not locate JSON array boundaries in {path}")
        return None
    json_text = json_str[start:end+1]
    try:
        data = json.loads(json_text)
        print(f"[OK] Valid JSON parsed from {path}")
        return data
    except json.JSONDecodeError as e:
        print(f"[ERROR] JSON decode failed in {path}: {e}")
        return None

# List of JSON book source files to test
paths = [
    '/c/Users/xk/.hermes-web-ui/upload/default/9f8d4cf6c49938ac.json',
    '/c/Users/xk/.hermes-web-ui/upload/default/8c137f3297771913.json',
    '/c/Users/xk/.hermes-web-ui/upload/default/53ff4d2ad4d3044d.json',
    '/c/Users/xk/.hermes-web-ui/upload/default/041e593b1078e51f.json',
]

results = {}
for p in paths:
    data = load_json(p)
    results[p] = data

print("[DONE] All files processed.")