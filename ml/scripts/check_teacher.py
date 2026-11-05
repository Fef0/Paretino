"""How good is the teacher on the hand-labelled images? Box recall and precision at IoU 0.5."""
import time

import cv2
import numpy as np

from common import Teacher, box_iou, load_gold

t = Teacher()
print(t.session.get_providers())
agg = []
for g in load_gold():
    im = cv2.imread(str(g["path"]))
    t0 = time.time()
    det = t(im, conf=0.25)
    dt = time.time() - t0
    gt = np.array([[p[:, 0].min(), p[:, 1].min(), p[:, 0].max(), p[:, 1].max()] for p, k in zip(g["polys"], g["kinds"]) if k == "hold"])
    iou = box_iou(gt, det[:, :4]) if len(det) else np.zeros((len(gt), 1))
    rec = (iou.max(1) >= 0.5).mean()
    prec = (iou.max(0) >= 0.5).mean() if len(det) else 0
    agg.append((len(gt), rec))
    print(f"{g['set']:9s}{g['file']:10s} gt={len(gt):4d} det={len(det):4d} recall={rec:.2f} matched-precision={prec:.2f} {dt:.2f}s", flush=True)
print("weighted recall", sum(n * r for n, r in agg) / sum(n for n, _ in agg))
