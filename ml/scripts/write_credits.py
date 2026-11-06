"""Writes DATA_CREDITS.md: every Wikimedia Commons photo used for training, with author and licence.

A photo counts as used under the same rule as build_dataset.py: not skipped and at least 30 auto-labelled holds.

usage: write_credits.py   (run from ml/scripts)
"""
import collections
import html
import json
import pathlib
import re

from common import ROOT


def author(raw):
    text = html.unescape(re.sub(r"<[^>]+>", " ", raw))
    return " ".join(text.split()).replace("|", "/") or "See file page"


def main():
    manifest = json.loads((ROOT / "datasets/commons/manifest.json").read_text())
    by_file = {v["file"]: (k, v) for k, v in manifest.items()}
    used = []
    for f in sorted((ROOT / "datasets/pseudo/commons").glob("*.json")):
        d = json.loads(f.read_text())
        if not d.get("skipped") and d.get("holds", 0) >= 30:
            used.append(by_file[pathlib.Path(d["file"]).name])
    used.sort(key=lambda u: u[0].lower())
    licences = collections.Counter(v["license"] for _, v in used)

    lines = [
        "# Wikimedia Commons credits", "",
        f"The hold detection model was trained on these {len(used)} photos from "
        "[Wikimedia Commons](https://commons.wikimedia.org/). Thank you to every author, and to the Wikimedia community.", "",
        "The photos themselves are not stored in this repository. For training they were resized, cropped into tiles and "
        "altered with simulated lighting and lens effects. Hold outlines were generated automatically.", "",
        "By licence: " + ", ".join(f"{k} ({n})" for k, n in licences.most_common()) + ".", "",
        "| File | Author | Licence |", "|---|---|---|",
    ]
    for title, v in used:
        name = title.removeprefix("File:").replace("|", "/").replace("[", "(").replace("]", ")")
        lines.append(f"| [{name}]({v['source']}) | {author(v['artist'])[:120]} | {v['license']} |")
    (ROOT / "DATA_CREDITS.md").write_text("\n".join(lines) + "\n", encoding="utf8", newline="\n")
    print(len(used), licences.most_common())


if __name__ == "__main__":
    main()
