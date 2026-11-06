"""Compares the app's detections (HoldDetectionParityTest) with the Python reference on the same photos.

usage: check_parity.py MODEL.onnx PHOTO_DIR RESULT_DIR
"""
import pathlib
import sys

import cv2
import numpy as np

from benchmark import pair_iou
from yolo_infer import TiledYolo

y = TiledYolo(sys.argv[1], providers=["CPUExecutionProvider"])
for f in sorted(pathlib.Path(sys.argv[3]).glob("*.txt")):
    img = cv2.imread(str(pathlib.Path(sys.argv[2]) / f.stem))
    h, w = img.shape[:2]
    lines = f.read_text().splitlines()
    app = [np.array([p.split(",") for p in l.split()], np.float32) * (w, h) for l in lines[1:] if l.strip()]
    ref = y.detect(img)
    best = [max((pair_iou(a, r) for r in ref), default=0) for a in app]
    print(f"{f.stem:28s} app={len(app):4d} python={len(ref):4d} matched(IoU>.5)={np.mean(np.array(best) > .5):.3f} "
          f"mean IoU={np.mean(best):.3f} {lines[0]}")
