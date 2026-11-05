"""Auto-label photos: multi-scale teacher boxes, then SAM outlines. Resumable, one JSON per image.

usage: pseudo_label.py NAME IMAGE_DIR [--every N] [--min-holds K]
"""
import argparse
import json
import pathlib
import time

import cv2
import numpy as np

from common import ROOT, Teacher, multiscale_boxes
from sam_masks import BoxSam

ap = argparse.ArgumentParser()
ap.add_argument("name")
ap.add_argument("dir", type=pathlib.Path)
ap.add_argument("--every", type=int, default=1)
ap.add_argument("--min-holds", type=int, default=0)
ap.add_argument("--max-side", type=int, default=2800)
a = ap.parse_args()

out = ROOT / "datasets" / "pseudo" / a.name
out.mkdir(parents=True, exist_ok=True)
files = sorted(p for p in a.dir.iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))[::a.every]
teacher, sam = Teacher(), BoxSam()
t_start = time.time()
for n, f in enumerate(files):
    dest = out / (f.stem + ".json")
    if dest.exists():
        continue
    im = cv2.imread(str(f))
    if im is None:
        continue
    s = min(1.0, a.max_side / max(im.shape[:2]))
    if s < 1:
        im = cv2.resize(im, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    det = multiscale_boxes(teacher, im, conf=0.3)
    if len(det) < a.min_holds:
        dest.write_text(json.dumps(dict(file=str(f), skipped=True, holds=len(det))))
        continue
    res = sam.masks(im, det[:, :4])
    polys = []
    for (poly, q), d in zip(res, det):
        if poly is None or q < 0.45:
            continue
        p = cv2.approxPolyDP(poly.reshape(-1, 1, 2), 1.0, True).reshape(-1, 2)
        if len(p) >= 3:
            polys.append(dict(points=(p / s).round(1).tolist(), quality=round(q, 3), conf=round(float(d[4]), 3)))
    dest.write_text(json.dumps(dict(file=str(f), holds=len(polys), boxes=len(det), polys=polys)))
    print(f"{n + 1}/{len(files)} {f.name} boxes={len(det)} kept={len(polys)} {(time.time() - t_start) / (n + 1):.1f}s/img", flush=True)
