"""Builds the YOLO-seg tile dataset.

Degradations run on the whole picture (lens effects depend on the position in the frame); tiles are then cut at the
scale the app runs at, with polygons clipped exactly.

`--phase eval` keeps gym sm and every phone photo out, so the benchmark measures unseen gym and camera.
`--phase final` adds them for the model that ships.

usage: build_dataset.py OUT --phase eval|final [--seed N]
"""
import argparse
import json
import multiprocessing as mp
import pathlib
import shutil

import cv2
import numpy as np

from common import ROOT, load_gold
from phone_aug import random_phone

TILE = 640
VAL_GOLD = {"0689.jpg", "1037.jpg"}  # bh images used for validation only


def sources(phase):
    """(kind, path, polys or None, variants, tiles per variant, scale range, split)"""
    out = []
    for g in load_gold():
        if g["set"] != "bh" and phase == "eval":
            continue
        polys = [p.tolist() for p, k in zip(g["polys"], g["kinds"]) if k == "hold"]
        if len(polys) < 30:
            continue  # partly annotated: unlabelled holds would teach the model to ignore holds
        split = "val" if g["file"] in VAL_GOLD else "train"
        out.append(("gold", str(g["path"]), polys, 1 if split == "val" else 10, 12 if split == "val" else 8, (0.55, 1.0), split))
    pseudo = {"bh": (2, 5, (0.55, 1.0)), "commons": (2, 5, (0.7, 1.25))}
    if phase == "final":
        pseudo.update({"sm": (2, 5, (0.55, 1.0)), "bh-phone": (2, 5, (0.35, 0.7))})
    gold_names = {str(g["path"].name) for g in load_gold()}
    for name, (variants, tiles, scale) in pseudo.items():
        for f in sorted((ROOT / "datasets/pseudo" / name).glob("*.json")):
            d = json.loads(f.read_text())
            if d.get("skipped") or d.get("holds", 0) < 8:
                continue
            if name != "commons" and pathlib.Path(d["file"]).name in gold_names:
                continue
            polys = [r["points"] for r in d["polys"]]
            out.append(("pseudo", d["file"], polys, variants, tiles, scale, "train"))
    comps = sorted((ROOT / "datasets/composite_train").glob("*.jpg"))
    for i, f in enumerate(comps):
        polys = json.loads(f.with_suffix(".json").read_text())
        split = "val" if i % 40 == 0 else "train"
        out.append(("composite", str(f), polys, 1, 6, (0.7, 1.1), split))
    return out


def clip_polys(polys, x0, y0, tile):
    """Polygons inside a tile, normalized. Holds with less than 35% visible are dropped."""
    res = []
    for p in polys:
        q = p - (x0, y0)
        if q[:, 0].max() < 0 or q[:, 1].max() < 0 or q[:, 0].min() >= tile or q[:, 1].min() >= tile:
            continue
        inside = (q[:, 0].min() >= 0 and q[:, 1].min() >= 0 and q[:, 0].max() < tile and q[:, 1].max() < tile)
        if inside:
            if cv2.contourArea(q.astype(np.float32)) >= 20:
                res.append(q)
            continue
        m = np.zeros((tile, tile), np.uint8)
        cv2.fillPoly(m, [np.round(q).astype(np.int32)], 1)
        full = cv2.contourArea(q.astype(np.float32))
        cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not cs:
            continue
        c = max(cs, key=cv2.contourArea)
        if cv2.contourArea(c) >= max(20, 0.35 * full):
            res.append(c.reshape(-1, 2).astype(np.float32))
    return res


def work(job):
    index, (kind, path, polys, variants, tiles, scale_range, split), out, seed = job
    rng = np.random.default_rng(seed * 1_000_003 + index)
    original = cv2.imread(path)
    if original is None:
        return 0
    polys = [np.array(p, np.float32) for p in polys]
    made = 0
    for v in range(variants):
        s = rng.uniform(*scale_range)
        # Scale is relative to the app's 2000 px wall for full-frame photos.
        s *= 2800 / max(max(original.shape[:2]), 1) if kind != "composite" and max(original.shape[:2]) > 2800 else 1
        img = cv2.resize(original, None, fx=s, fy=s, interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR)
        ps = [p * s for p in polys]
        if split == "train" and rng.random() < 0.88:
            img, ps = random_phone(img, ps, rng)
        h, w = img.shape[:2]
        if h < TILE or w < TILE:
            img = cv2.copyMakeBorder(img, 0, max(TILE - h, 0), 0, max(TILE - w, 0), cv2.BORDER_REFLECT_101)
            h, w = img.shape[:2]
        centers = np.array([p.mean(0) for p in ps]) if ps else None
        for t in range(tiles):
            if centers is not None and rng.random() < 0.85:
                c = centers[rng.integers(len(centers))] + rng.uniform(-TILE / 3, TILE / 3, 2)
            else:
                c = rng.uniform((0, 0), (w, h))
            x0 = int(np.clip(c[0] - TILE / 2, 0, w - TILE))
            y0 = int(np.clip(c[1] - TILE / 2, 0, h - TILE))
            labels = clip_polys(ps, x0, y0, TILE)
            name = f"{kind}_{index:05d}_{v}_{t}"
            cv2.imwrite(str(out / "images" / split / f"{name}.jpg"), img[y0:y0 + TILE, x0:x0 + TILE], [cv2.IMWRITE_JPEG_QUALITY, 92])
            with open(out / "labels" / split / f"{name}.txt", "w") as f:
                for q in labels:
                    q = cv2.approxPolyDP(q.reshape(-1, 1, 2), 0.7, True).reshape(-1, 2) if len(q) > 40 else q
                    if len(q) >= 3:
                        f.write("0 " + " ".join(f"{v_:.5f}" for v_ in np.clip(q / TILE, 0, 1).ravel()) + "\n")
            made += 1
    return made


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=pathlib.Path)
    ap.add_argument("--phase", choices=["eval", "final"], required=True)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--workers", type=int, default=10)
    a = ap.parse_args()
    if a.out.exists():
        shutil.rmtree(a.out)
    for d in ["images/train", "images/val", "labels/train", "labels/val"]:
        (a.out / d).mkdir(parents=True)
    src = sources(a.phase)
    print(len(src), "sources", {k: sum(s[0] == k for s in src) for k in ("gold", "pseudo", "composite")}, flush=True)
    jobs = [(i, s, a.out, a.seed) for i, s in enumerate(src)]
    with mp.Pool(a.workers) as pool:
        total = 0
        for n, made in enumerate(pool.imap_unordered(work, jobs)):
            total += made
            if n % 50 == 0:
                print(n, total, flush=True)
    print("tiles", total)
    (a.out / "data.yaml").write_text(f"path: {a.out.resolve().as_posix()}\ntrain: images/train\nval: images/val\nnames:\n  0: hold\n")


if __name__ == "__main__":
    main()
