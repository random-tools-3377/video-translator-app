#!/usr/bin/env python3
"""Runs vt-transcribe (the app's Whisper settings) on the public test clips and checks the result:
the detected language, that the output is English (Latin script), that some of the words a right
translation must contain are there, that no line was invented over music (quiet_until), and that
no line repeats the one before. Prints the English so a person can judge it.

  check-translation.py VT_TRANSCRIBE MODEL CLIPS_TSV CLIP_DIR THREADS [RUNNER ...]

RUNNER, if given, prefixes the command (for example: env QEMU_CPU=max, with QEMU registered for arm64
binaries through binfmt as CI does; or qemu-aarch64-static -cpu max).
Environment:
  VAD_MODEL=path     the speech detector model; the app always uses it, so CI passes it too
  EXTRA_ARGS="..."   more vt-transcribe options (for comparisons, e.g. --beam 5)
  CLIPS_ONLY=a,b     only these clips
  REQUIRE_FEATURES="DOTPROD FP16_VA"  fail unless whisper.cpp reports these CPU features on
  REPORT_ONLY=1      print, never fail (for before/after comparisons)
  LLM_TOOL=path LLM_MODEL=path   the app with a language model: Whisper writes down the spoken
                     language (--no-translate), then vt-translate turns non-English lines into
                     English with that model, keeping each line's time; the English is checked
"""
import csv, os, re, shlex, subprocess, sys, time

tool, model, tsv, clip_dir, threads = sys.argv[1:6]
runner = sys.argv[6:]
with open(tsv, newline="", encoding="utf-8") as f:
    rows = [r for r in csv.DictReader((l for l in f if not l.startswith("#")), delimiter="\t")]
rows = [r for r in rows if r["language"] != "-"]
only = [n for n in os.environ.get("CLIPS_ONLY", "").split(",") if n]
if only:
    rows = [r for r in rows if r["name"] in only]
if not rows:
    sys.exit("no clips selected")
required = os.environ.get("REQUIRE_FEATURES", "").split()
report_only = os.environ.get("REPORT_ONLY") == "1"
extra = shlex.split(os.environ.get("EXTRA_ARGS", ""))
if os.environ.get("VAD_MODEL"):
    extra += ["--vad", os.environ["VAD_MODEL"]]
llm_tool, llm_model = os.environ.get("LLM_TOOL"), os.environ.get("LLM_MODEL")
if llm_tool:
    extra += ["--no-translate"]


def language_model(lang_name, lines):
    """The app's language-model step on Whisper's lines: English lines out, same count."""
    inp = f"LANG {lang_name}\n" + "".join(f"LINE {t}\n" for t in lines)
    t = time.time()
    q = subprocess.run(runner + [llm_tool, llm_model, threads], input=inp, capture_output=True, text=True)
    if q.returncode != 0:
        return None, f"vt-translate exit {q.returncode}: {q.stderr.strip()[-400:]}", ""
    english = {int(i): x for i, x in re.findall(r"^EN (\d+) (.*)$", q.stdout, re.M)}
    info = " ".join(re.findall(r"^(?:STATS|TIME) (.*)$", q.stdout, re.M)) + f" wall={time.time() - t:.0f}s"
    system = (re.search(r"^SYSTEM (.*)$", q.stdout, re.M) or [None, ""])[1]
    if system and not getattr(language_model, "shown", False):
        print(f"language model system info: {system}")
        language_model.shown = True
    if sorted(english) != list(range(len(lines))):
        return None, f"vt-translate gave {len(english)} lines for {len(lines)}", info
    return [english[i] for i in range(len(lines))], None, info

failures = 0
for row in rows:
    name, lang = row["name"], row["language"]
    started = time.time()
    p = subprocess.run(runner + [tool, model, f"{clip_dir}/{name}.f32", threads] + extra,
                       capture_output=True, text=True)
    wall = time.time() - started
    out = p.stdout
    lang_match = re.search(r"^LANG (\S+) ?(\S*)", out, re.M)
    got_lang = lang_match[1] if lang_match else None
    segs = [(int(a), int(b), t.strip()) for a, b, t in re.findall(r"^SEG (\d+) (\d+) (.*)$", out, re.M)]
    spoken, llm_problem, llm_info = None, None, ""
    if llm_tool and got_lang and got_lang != "en" and segs:
        spoken = segs
        english, llm_problem, llm_info = language_model(lang_match[2] or got_lang, [x[2] for x in segs])
        if english is not None:
            segs = [(a, b, e.strip()) for (a, b, _), e in zip(segs, english)]
    text = " ".join(s[2] for s in segs)
    timing = (re.search(r"^TIME (.*)$", out, re.M) or [None, ""])[1]
    system = (re.search(r"^SYSTEM (.*)$", out, re.M) or [None, ""])[1]
    letters = [c for c in text if c.isalpha()]
    latin = sum(1 for c in letters if c.isascii()) / max(1, len(letters))
    words = set(re.findall(r"[a-z]+", text.lower()))
    expect = [w for w in row["expect_words"].split() if w != "-"]
    found = [w for w in expect if any(alt in words for alt in w.split("|"))]
    need = int(row["min_words"])
    quiet = float(row.get("quiet_until") or 0)
    problems = []
    if llm_problem:
        problems.append(llm_problem)
    if llm_tool and got_lang and got_lang != "en" and spoken is None:
        problems.append("Whisper wrote no lines for the language model")
    if p.returncode != 0:
        problems.append(f"exit {p.returncode}: {p.stderr.strip()[-400:]}")
    if got_lang != lang:
        problems.append(f"detected {got_lang}, expected {lang}")
    if latin < 0.9:
        problems.append(f"only {latin:.0%} of letters are Latin: not English")
    if len(found) < need:
        problems.append(f"found {len(found)} of the expected words {expect} (need {need})")
    early = [s for s in segs if s[0] < quiet * 1000]
    if early:
        problems.append(f"{len(early)} line(s) start before {quiet:.0f} s, where there is no speech")
    key = lambda t: re.sub(r"[^0-9a-z\u0080-￿]", "", t.lower())
    repeats = sum(1 for a, b in zip(segs, segs[1:]) if key(a[2]) and key(a[2]) == key(b[2]))
    if repeats:
        problems.append(f"{repeats} line(s) repeat the line before")
    print(f"== {name} ({lang}, {row['seconds']} s: {row['title']})")
    print(f"   detected={got_lang} latin={latin:.0%} expected-words={found} wall={wall:.0f}s {timing}")
    if llm_info:
        print(f"   language model: {llm_info}")
    if required:
        print(f"   system: {system}")
    for i, s in enumerate(segs):
        print(f"   [{s[0]/1000:6.1f} - {s[1]/1000:6.1f}] {s[2]}")
        if spoken:
            print(f"                     ({spoken[i][2]})")
    for feat in required:
        if f"{feat} = 1" not in system:
            problems.append(f"CPU feature {feat} was not used (system info: {system})")
    for pr in problems:
        print(f"   {'note' if report_only else '::error::' + name}: {pr}")
    if problems and not report_only:
        failures += 1
sys.exit(1 if failures else 0)
