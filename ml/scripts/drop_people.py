"""Removes auto-labels that sit on people (helmets, heads and shirts look like holds to the teacher). Idempotent.

usage: drop_people.py NAME
"""
import argparse
import json

import cv2
import numpy as np
from ultralytics import YOLO

from common import ROOT

ap = argparse.ArgumentParser()
ap.add_argument("name")
a = ap.parse_args()
model = YOLO(str(ROOT / "weights/yolo11n-seg.pt"))
dropped = images = 0
for f in sorted((ROOT / "datasets/pseudo" / a.name).glob("*.json")):
    d = json.loads(f.read_text())
    if d.get("skipped"):
        continue
    im = cv2.imread(d["file"])
    if im is None:
        continue
    s = min(1.0, 2800 / max(im.shape[:2]))  # polygons are stored at the labelling scale
    h, w = int(round(im.shape[0] * s)), int(round(im.shape[1] * s))
    r = model.predict(im, classes=[0], conf=0.3, imgsz=1280, device="cpu", verbose=False, retina_masks=True)[0]
    if r.masks is None:
        continue
    person = cv2.resize(r.masks.data.amax(0).numpy().astype(np.uint8), (w, h), interpolation=cv2.INTER_NEAREST)
    person = cv2.dilate(person, np.ones((9, 9), np.uint8))
    keep = []
    for p in d["polys"]:
        m = np.zeros((h, w), np.uint8)
        cv2.fillPoly(m, [np.round(np.array(p["points"])).astype(np.int32)], 1)
        if (m & person).sum() < 0.5 * max(m.sum(), 1):
            keep.append(p)
    if len(keep) != len(d["polys"]):
        dropped += len(d["polys"]) - len(keep)
        images += 1
        d["polys"], d["holds"] = keep, len(keep)
        f.write_text(json.dumps(d))
print("dropped", dropped, "labels on", images, "images")
