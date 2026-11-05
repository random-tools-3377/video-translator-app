#!/usr/bin/env python3
"""Downloads one file from a Hugging Face model repo and checks it against the size and sha256
Hugging Face publishes for it (the LFS oid). Prints the sha256 so it can be pinned.

  hf_fetch.py REPO FILE DEST
  hf_fetch.py REPO Q4_K_M DEST     # the repo's one top-level *-Q4_K_M.gguf, whatever its name
  (any name with a dot is a file: .gguf, .bin, ...)
"""
import hashlib, json, os, subprocess, sys, urllib.request

repo, path, dest = sys.argv[1:4]
_repo = os.environ.get("GITHUB_REPOSITORY")
UA = "video-translator-ci/1.0" + (f" (https://github.com/{_repo})" if _repo else "")
folder = os.path.dirname(path)
api = f"https://huggingface.co/api/models/{repo}/tree/main" + (f"/{folder}" if folder else "")
with urllib.request.urlopen(urllib.request.Request(api, headers={"User-Agent": UA}), timeout=60) as r:
    tree = json.load(r)
if "." not in os.path.basename(path):
    # A quantisation tag: find the single main model file with it (not mmproj, not "UD-" variants).
    tag = path
    hits = [e["path"] for e in tree if e.get("path", "").endswith(f"-{tag}.gguf")
            and not e["path"].startswith("mmproj") and "UD-" not in e["path"]]
    if len(hits) != 1:
        ggufs = [e["path"] for e in tree if e.get("path", "").endswith(".gguf")]
        sys.exit(f"{repo}: expected one *-{tag}.gguf, found {hits}. The repo has: {ggufs}")
    path = hits[0]
entry = next((e for e in tree if e.get("path") == path), None)
if entry is None or not entry.get("lfs"):
    sys.exit(f"{repo}/{path}: not found, or not an LFS file")
want_sha, want_size = entry["lfs"]["oid"], entry["lfs"]["size"]
os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
subprocess.run(["curl", "-fsSL", "--retry", "5", "--retry-delay", "10", "-A", UA, "-o", dest,
                f"https://huggingface.co/{repo}/resolve/main/{path}"], check=True)
h = hashlib.sha256()
with open(dest, "rb") as f:
    for chunk in iter(lambda: f.read(1 << 24), b""):
        h.update(chunk)
size = os.path.getsize(dest)
if h.hexdigest() != want_sha or size != want_size:
    sys.exit(f"{repo}/{path}: got sha256 {h.hexdigest()} ({size} B), Hugging Face lists {want_sha} ({want_size} B)")
print(f"{repo}/{path}: {size} bytes, sha256 {want_sha} (matches Hugging Face)")
