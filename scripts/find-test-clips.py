#!/usr/bin/env python3
"""Looks for public, freely licensed Russian and Japanese speech recordings on Wikimedia Commons,
for the translation tests. Runs on a GitHub runner (the cloud session cannot reach Commons).

  find-test-clips.py search ["query" ...]   # list candidates with licence, length, size
  find-test-clips.py details "File:A.ogg"   # download, sha256, ffprobe, full licence fields
  find-test-clips.py fingerprint URL ...    # download any file, print sha256 and size; for a
                                            # Hugging Face file, also the sha256 HF publishes
  find-test-clips.py hf-models QUERY ...    # Hugging Face model repos matching QUERY
  find-test-clips.py hf-tree REPO ...       # a repo's files with size and HF's sha256 (no download)

Only metadata and public recordings; no private videos are involved.
"""
import hashlib, json, os, re, shlex, subprocess, sys, tempfile, urllib.parse, urllib.request

API = "https://commons.wikimedia.org/w/api.php"
_repo = os.environ.get("GITHUB_REPOSITORY")
UA = "video-translator-ci/1.0 (" + (f"https://github.com/{_repo}; " if _repo else "") + "test clip search)"
FREE = ("public domain", "pd", "cc0", "cc by", "cc-by")


def api(**params):
    params.update(format="json", formatversion="2")
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def info(titles):
    out = []
    for i in range(0, len(titles), 40):
        d = api(action="query", titles="|".join(titles[i:i + 40]), prop="imageinfo",
                iiprop="url|size|sha1|mime|mediatype|extmetadata",
                iiextmetadatafilter="LicenseShortName|Artist|ImageDescription|UsageTerms|Credit")
        for p in d.get("query", {}).get("pages", []):
            ii = (p.get("imageinfo") or [{}])[0]
            m = ii.get("extmetadata", {})
            g = lambda k: (m.get(k, {}).get("value") or "").replace("\n", " ")[:160]
            out.append(dict(title=p["title"], mime=ii.get("mime"), size=ii.get("size"),
                            duration=ii.get("duration"), url=ii.get("url"), sha1=ii.get("sha1"),
                            licence=g("LicenseShortName"), artist=g("Artist"),
                            desc=g("ImageDescription")))
    return out


def members(cat, kind="file", limit=200):
    titles, cont = [], {}
    while len(titles) < limit:
        d = api(action="query", list="categorymembers", cmtitle=cat, cmtype=kind, cmlimit="200", **cont)
        titles += [m["title"] for m in d.get("query", {}).get("categorymembers", [])]
        if "continue" not in d:
            break
        cont = {"cmcontinue": d["continue"]["cmcontinue"]}
    return titles[:limit]


def search(q, limit=50):
    d = api(action="query", list="search", srnamespace="6", srsearch=q, srlimit=str(limit))
    return [s["title"] for s in d.get("query", {}).get("search", [])]


def show(label, titles):
    print(f"\n### {label}: {len(titles)} titles")
    if not titles:
        return
    for c in info(titles):
        if not (c["mime"] or "").startswith(("audio/", "application/ogg", "video/")):
            continue
        free = any(f in c["licence"].lower() for f in FREE)
        print(f"- {c['title']} | {c['mime']} | {c['size']} B | dur={c['duration']} | "
              f"lic={c['licence']}{'' if free else ' (NOT FREE?)'} | by={c['artist'][:60]} | {c['desc'][:120]}")


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def do_fingerprint(urls):
    for url in urls:
        data = fetch(url)
        print(f"{url}\n  sha256 {hashlib.sha256(data).hexdigest()}  {len(data)} bytes")
        m = re.match(r"https://huggingface.co/([^/]+/[^/]+)/resolve/([^/]+)/(.+)$", url)
        if m:
            repo, rev, path = m.groups()
            tree = json.loads(fetch(f"https://huggingface.co/api/models/{repo}/tree/{rev}"))
            for e in tree:
                if e.get("path") == path:
                    print(f"  Hugging Face lists: {json.dumps(e.get('lfs') or e)}")


def do_hf_models(queries):
    for q in queries:
        url = "https://huggingface.co/api/models?" + urllib.parse.urlencode(
            dict(search=q, sort="downloads", direction="-1", limit="25"))
        print(f"\n### models matching {q!r}")
        for m in json.loads(fetch(url)):
            print(f"- {m.get('id')} | downloads={m.get('downloads')} | modified={m.get('lastModified', '')[:10]}")


def do_hf_tree(repos):
    for repo in repos:
        print(f"\n### {repo}")
        try:
            tree = json.loads(fetch(f"https://huggingface.co/api/models/{repo}/tree/main"))
        except Exception as e:
            print(f"  {e}")
            continue
        for e in tree:
            lfs = e.get("lfs") or {}
            print(f"- {e.get('path')} | {e.get('size')} B | sha256={lfs.get('oid', '-')}")


def do_search(queries=()):
    if queries:
        for q in queries:
            show(f"search {q}", search(q))
        return
    for parent in ["Category:Spoken Wikipedia", "Category:Audio files by language"]:
        try:
            subs = members(parent, kind="subcat", limit=400)
            hits = [s for s in subs if any(k in s.lower() for k in ("russian", "japanese", "русск", "日本"))]
            print(f"\n### subcategories of {parent} matching ru/ja: {hits}")
            for s in hits:
                show(s, members(s, limit=60))
        except Exception as e:
            print(f"{parent}: {e}")
    for q in ['"Spoken Wikipedia" Russian filemime:audio', '"Spoken Wikipedia" Japanese filemime:audio',
              'russian speech filemime:audio', 'japanese speech filemime:audio',
              'Voice of America Russian filemime:audio', 'NHK OR 朗読 filemime:audio',
              'LibriVox Russian filemime:audio', 'LibriVox Japanese filemime:audio']:
        try:
            show(f"search {q}", search(q))
        except Exception as e:
            print(f"search {q}: {e}")


def do_details(titles):
    for c in info(titles):
        print(json.dumps(c, ensure_ascii=False))
        if not c.get("url"):
            continue
        with tempfile.NamedTemporaryFile(delete=False) as f:
            req = urllib.request.Request(c["url"], headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=300) as r:
                data = r.read()
            f.write(data)
        print("  sha256", hashlib.sha256(data).hexdigest(), "sha1", hashlib.sha1(data).hexdigest(), len(data), "bytes")
        probe = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_name,sample_rate,channels",
                                "-of", "compact", f.name], capture_output=True, text=True)
        print("  ffprobe", probe.stdout.strip().replace("\n", " ; "), probe.stderr.strip())
        os.unlink(f.name)


if __name__ == "__main__":
    args = sys.argv[1:] or shlex.split(os.environ.get("ARGS", ""))
    if args and args[0] == "details":
        do_details(args[1:])
    elif args and args[0] == "fingerprint":
        do_fingerprint(args[1:])
    elif args and args[0] == "hf-models":
        do_hf_models(args[1:])
    elif args and args[0] == "hf-tree":
        do_hf_tree(args[1:])
    elif args and args[0] == "search":
        do_search(args[1:])
    else:
        do_search()
