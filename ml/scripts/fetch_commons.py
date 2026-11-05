"""Harvest climbing-wall photos from Wikimedia Commons categories, keeping licence and attribution."""
import argparse
import hashlib
import json
import pathlib
import time
import urllib.parse
import urllib.request

UA = {"User-Agent": "ParetinoDataset/0.1 (hold detection research)"}
API = "https://commons.wikimedia.org/w/api.php?"
ROOTS = ["Category:Climbing_walls", "Category:Indoor_climbing", "Category:Indoor_bouldering", "Category:Artificial_climbing_holds",
         "Category:Spray_wall", "Category:Rock_climbing_gyms", "Category:Volume_holds"]
SKIP = ("ice ", "competition", "championship", "cup", "olympic", "games", "speed climbing", "tower", "playground")
FREE = ("cc0", "cc-by", "cc by", "public domain", "pd")


def get(url, timeout=60):
    for attempt in range(4):
        try:
            return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout).read()
        except Exception:
            time.sleep(6 * (attempt + 1))
    return None


def api(**params):
    data = get(API + urllib.parse.urlencode({"action": "query", "format": "json", **params}))
    return json.loads(data) if data else {}


def members(cat):
    cont = {}
    while True:
        d = api(list="categorymembers", cmtitle=cat, cmlimit=500, **cont)
        yield from d.get("query", {}).get("categorymembers", [])
        if "continue" not in d:
            return
        cont = {"cmcontinue": d["continue"]["cmcontinue"]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=pathlib.Path)
    ap.add_argument("--depth", type=int, default=3)
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    seen, files = set(), set()
    frontier = [(c, 0) for c in ROOTS]
    while frontier:
        cat, depth = frontier.pop()
        if cat in seen:
            continue
        seen.add(cat)
        for m in members(cat):
            if m["ns"] == 6 and m["title"].lower().endswith((".jpg", ".jpeg")):
                files.add(m["title"])
            elif m["ns"] == 14 and depth < a.depth and not any(s in m["title"].lower() for s in SKIP):
                frontier.append((m["title"], depth + 1))
    print(len(seen), "categories", len(files), "files", flush=True)

    path = a.out / "manifest.json"
    manifest = json.loads(path.read_text()) if path.exists() else {}
    files = sorted(f for f in files if f not in manifest)
    for i in range(0, len(files), 25):
        d = api(titles="|".join(files[i:i + 25]), prop="imageinfo", iiprop="url|size|extmetadata", iiurlwidth=1920)
        for p in d.get("query", {}).get("pages", {}).values():
            if "imageinfo" not in p:
                continue
            info = p["imageinfo"][0]
            meta = info.get("extmetadata", {})
            lic = meta.get("LicenseShortName", {}).get("value", "")
            if not lic.lower().startswith(FREE) or "nc" in lic.lower().split("-") or min(info["width"], info["height"]) < 700:
                continue
            data = get(info.get("thumburl") or info["url"])
            if not data or data[:3] != b"\xff\xd8\xff":
                continue
            name = hashlib.sha1(p["title"].encode()).hexdigest()[:16] + ".jpg"
            (a.out / name).write_bytes(data)
            manifest[p["title"]] = dict(file=name, license=lic, artist=meta.get("Artist", {}).get("value", "")[:200], source=info.get("descriptionurl"))
            time.sleep(1)
        path.write_text(json.dumps(manifest, indent=0))
        print(i, len(manifest), flush=True)


if __name__ == "__main__":
    main()
