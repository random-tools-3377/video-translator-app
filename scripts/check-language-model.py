#!/usr/bin/env python3
"""Runs vt-translate (the app's language-model step) on a transcript and checks the English: one
line per input line, Latin script, enough of the expected words, and (for the ARM run) that
llama.cpp used the phone's CPU features. Prints the English so a person can judge it.

  check-language-model.py VT_TRANSCRIBE_INPUT TOOL MODEL THREADS [RUNNER ...]

Environment:
  EXPECT="a|b c d"   words a right translation contains ("a|b": either); MIN_WORDS=n of them
  REQUIRE_FEATURES="NEON DOTPROD"   fail unless llama.cpp reports these CPU features on
"""
import os, re, subprocess, sys, time

inp_path, tool, model, threads = sys.argv[1:5]
runner = sys.argv[5:]
inp = open(inp_path, encoding="utf-8").read()
n = len(re.findall(r"^LINE ", inp, re.M))
expect = os.environ.get("EXPECT", "").split()
need = int(os.environ.get("MIN_WORDS", "0"))
required = os.environ.get("REQUIRE_FEATURES", "").split()

t = time.time()
p = subprocess.run(runner + [tool, model, threads], input=inp, capture_output=True, text=True)
wall = time.time() - t
out = p.stdout
english = [x for _, x in sorted((int(i), x) for i, x in re.findall(r"^EN (\d+) (.*)$", out, re.M))]
system = (re.search(r"^SYSTEM (.*)$", out, re.M) or [None, ""])[1]
info = " ".join(re.findall(r"^(?:STATS|TIME) (.*)$", out, re.M))
text = " ".join(english)
letters = [c for c in text if c.isalpha()]
latin = sum(1 for c in letters if c.isascii()) / max(1, len(letters))
words = set(re.findall(r"[a-z]+", text.lower()))
found = [w for w in expect if any(alt in words for alt in w.split("|"))]

print(f"wall={wall:.0f}s {info}")
print(f"system: {system}")
for line in english:
    print(f"   {line}")
problems = []
if p.returncode != 0:
    problems.append(f"exit {p.returncode}: {p.stderr.strip()[-600:]}")
if len(english) != n:
    problems.append(f"{len(english)} English lines for {n} input lines")
if latin < 0.9:
    problems.append(f"only {latin:.0%} of letters are Latin: not English")
if len(found) < need:
    problems.append(f"found {found} of the expected words {expect} (need {need})")
for feat in required:
    if f"{feat} = 1" not in system:
        problems.append(f"CPU feature {feat} was not used (system info: {system})")
for pr in problems:
    print(f"::error::{pr}")
sys.exit(1 if problems else 0)
