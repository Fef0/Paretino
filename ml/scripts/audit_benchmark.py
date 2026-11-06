"""Checks the benchmark itself: per-photo scores, and whether lens warps leave labelled holds outside the frame.

usage: audit_benchmark.py MODEL.onnx OVERLAY.jpg
"""
import sys

import cv2
import numpy as np

from benchmark import score, summarize, test_images
from phone_aug import stress_suite
from yolo_infer import TiledYolo

y = TiledYolo(sys.argv[1], providers=["CPUExecutionProvider"])
suite, items = stress_suite(), test_images()


def in_frame(p, shape):
    c = p.mean(0)
    return 0 <= c[0] < shape[1] and 0 <= c[1] < shape[0]


print("-- clean, per real photo")
for n, it in enumerate(items):
    if it["group"] != "real":
        continue
    img, polys = suite["clean"](it["img"], it["holds"] + it["volumes"], np.random.default_rng(1000 + n))
    h, v = polys[:len(it["holds"])], polys[len(it["holds"]):]
    s = summarize([score([np.asarray(p, np.float32) for p in y.detect(img)], h, v, img.shape[:2])])
    print(f"{it['name']:24s} holds={len(h):4d} recall={s['recall']:.3f} prec={s['precision']:.3f} F1={s['f1']:.3f} iou={s['iou']:.3f}", flush=True)

for cond in ("barrel (ultra-wide)", "pincushion (tele)"):
    rows = {"real": [[], []], "plywood": [[], []]}
    out = total = 0
    for n, it in enumerate(items):
        img, polys = suite[cond](it["img"], it["holds"] + it["volumes"], np.random.default_rng(1000 + n))
        h, v = polys[:len(it["holds"])], polys[len(it["holds"]):]
        vis = [p for p in h if in_frame(p, img.shape)]
        out += len(h) - len(vis)
        total += len(h)
        pred = [np.asarray(p, np.float32) for p in y.detect(img)]
        vol = v if it["group"] == "real" else None
        rows[it["group"]][0].append(score(pred, h, vol, img.shape[:2]))
        rows[it["group"]][1].append(score(pred, vis, vol, img.shape[:2]))
        if cond.startswith("barrel") and it["group"] == "plywood" and n == len(items) - 1:
            o = img.copy()
            cv2.polylines(o, [np.round(p).astype(np.int32) for p in h], True, (0, 255, 0), 2)
            cv2.polylines(o, [np.round(p).astype(np.int32) for p in pred], True, (0, 0, 255), 2)
            cv2.imwrite(sys.argv[2], cv2.resize(o, None, fx=0.6, fy=0.6), [cv2.IMWRITE_JPEG_QUALITY, 85])
    print(f"-- {cond}: {out}/{total} labelled holds outside the frame")
    for g, (a, b) in rows.items():
        sa, sb = summarize(a), summarize(b)
        print(f"{g:8s} as benchmarked recall={sa['recall']:.3f} prec={sa['precision']:.3f} F1={sa['f1']:.3f} | in-frame only recall={sb['recall']:.3f} prec={sb['precision']:.3f} F1={sb['f1']:.3f}", flush=True)
