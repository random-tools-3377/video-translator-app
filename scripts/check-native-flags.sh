#!/usr/bin/env bash
# Confirms from CMake's compile_commands.json that every native file was compiled at -O3, and
# that the arm64 build targets the S24 Ultra's instruction set. An unoptimised whisper.cpp is
# many times slower, so this is checked for every build type that was built.
#
#   scripts/check-native-flags.sh [app/.cxx]
set -euo pipefail
root="${1:-app/.cxx}"
mapfile -t dbs < <(find "$root" -name compile_commands.json | sort)
[[ ${#dbs[@]} -gt 0 ]] || { echo "::error::no compile_commands.json under $root" >&2; exit 1; }
python3 -I - "${dbs[@]}" <<'PY'
import json, re, shlex, sys
bad = 0
for db in sys.argv[1:]:
    abi = db.split("/")[-2]
    entries = json.load(open(db))
    n = 0
    for e in entries:
        args = e.get("arguments") or shlex.split(e["command"])
        opts = [a for a in args if re.fullmatch(r"-O[0-3sz]|-Og|-Ofast", a)]
        if not opts or opts[-1] != "-O3":
            print(f"::error::{db}: {e['file']} compiled with {opts or 'no -O flag'}")
            bad += 1
        if abi == "arm64-v8a" and "ggml-cpu" in e["file"]:
            if not any(a.startswith("-march=armv8.2-a+dotprod+fp16") for a in args):
                print(f"::error::{db}: {e['file']} lacks -march=armv8.2-a+dotprod+fp16")
                bad += 1
        n += 1
    print(f"{db}: {n} files checked ({abi})")
if bad:
    sys.exit(1)
print("Native code is optimised (-O3) in every build checked.")
PY
