"""Quality of teacher+SAM pseudo labels against hand-drawn polygons (mask IoU, per image)."""
import sys
import time

import cv2
import numpy as np

from common import Teacher, box_iou, load_gold, multiscale_boxes
from sam_masks import BoxSam

teacher = Teacher()
sam = BoxSam()
rows = []
for g in load_gold():
    if len(sys.argv) > 1 and g["file"] not in sys.argv[1:]:
        continue
    im = cv2.imread(str(g["path"]))
    t0 = time.time()
    det = multiscale_boxes(teacher, im)
    t1 = time.time()
    res = sam.masks(im, det[:, :4])
    t2 = time.time()
    polys = [p for p, k in zip(g["polys"], g["kinds"]) if k == "hold"]
    gt = np.array([[p[:, 0].min(), p[:, 1].min(), p[:, 0].max(), p[:, 1].max()] for p in polys])
    iou = box_iou(gt, det[:, :4])
    h, w = im.shape[:2]
    miou = []
    for i, p in enumerate(polys):
        j = iou[i].argmax()
        if iou[i, j] < 0.3 or res[j][0] is None:
            miou.append(0.0)
            continue
        a = np.zeros((h, w), np.uint8)
        b = np.zeros((h, w), np.uint8)
        cv2.fillPoly(a, [p.astype(np.int32)], 1)
        cv2.fillPoly(b, [res[j][0].astype(np.int32)], 1)
        miou.append((a & b).sum() / max((a | b).sum(), 1))
    miou = np.array(miou)
    prec = (iou.max(0) >= 0.3).mean()
    hit = miou[miou > 0]
    rows.append((len(polys), (miou >= .5).mean(), hit.mean() if len(hit) else 0, prec))
    print(f"{g['set']:9s}{g['file']:9s} gt={len(polys):4d} det={len(det):4d} recall@maskIoU.5={rows[-1][1]:.2f} "
          f"meanIoU={rows[-1][2]:.2f} prec={prec:.2f} teacher {t1 - t0:.1f}s sam {t2 - t1:.1f}s", flush=True)
a = np.array(rows)
print("weighted recall/meanIoU/prec", (a[:, 0:1] * a[:, 1:]).sum(0) / a[:, 0].sum())
