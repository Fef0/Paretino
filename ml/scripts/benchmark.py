"""Old vs new: every detector on the same held-out images under the same 18 conditions.

Test data never seen in training: gym `sm` and the phone photos (real, hand labelled) and plywood scenes built
from holds of those sets only.

usage: benchmark.py --yolo model.onnx [--name yolo] [--out report.json] [--conditions a,b]
"""
import argparse
import json
import pathlib
import time
from collections import defaultdict

import cv2
import numpy as np

import opencv_baseline
from common import ROOT, load_gold
from phone_aug import stress_suite

APP_SIDE = 2000  # the app hands detectors a rectified wall of this long side


def test_images():
    items = []
    for g in load_gold():
        if g["set"] == "bh":
            continue
        im = cv2.imread(str(g["path"]))
        s = APP_SIDE / max(im.shape[:2])
        im = cv2.resize(im, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
        holds = [p * s for p, k in zip(g["polys"], g["kinds"]) if k == "hold"]
        volumes = [p * s for p, k in zip(g["polys"], g["kinds"]) if k == "volume"]
        items.append(dict(group="real", name=f"{g['set']}/{g['file']}", img=im, holds=holds, volumes=volumes))
    for f in sorted((ROOT / "datasets/composite_test").glob("*.jpg")):
        polys = [np.array(p, np.float32) for p in json.loads(f.with_suffix(".json").read_text())]
        if polys:
            items.append(dict(group="plywood", name=f.name, img=cv2.imread(str(f)), holds=polys, volumes=[]))
    return items


def frame_outline(shape, step=20):
    """The picture edge as a polygon, so lens warps can report where the real picture ended up."""
    h, w = shape[:2]
    xs, ys = np.arange(0, w, step, dtype=np.float32), np.arange(0, h, step, dtype=np.float32)
    return np.vstack([np.c_[xs, xs * 0], np.c_[ys * 0 + w - 1, ys], np.c_[xs[::-1], xs * 0 + h - 1], np.c_[ys[::-1] * 0, ys[::-1]]])


def valid_area(border, shape):
    """Scoring area: barrel warps push labels out of the frame, pincushion warps fill the edges with mirrored picture.
    Neither can be judged, for any detector."""
    m = np.zeros(shape, np.uint8)
    cv2.fillPoly(m, [np.round(border).astype(np.int32)], 1)
    return m


def inside(poly, valid):
    c = np.asarray(poly).mean(0)
    x, y = int(c[0]), int(c[1])
    return 0 <= x < valid.shape[1] and 0 <= y < valid.shape[0] and bool(valid[y, x])


def raster(poly, box):
    x0, y0, x1, y1 = box
    m = np.zeros((y1 - y0, x1 - x0), np.uint8)
    cv2.fillPoly(m, [np.round(poly - (x0, y0)).astype(np.int32)], 1)
    return m


def pair_iou(a, b):
    ax0, ay0 = a.min(0)
    ax1, ay1 = a.max(0)
    bx0, by0 = b.min(0)
    bx1, by1 = b.max(0)
    if ax1 < bx0 or bx1 < ax0 or ay1 < by0 or by1 < ay0:
        return 0.0
    box = (int(min(ax0, bx0)) - 1, int(min(ay0, by0)) - 1, int(max(ax1, bx1)) + 2, int(max(ay1, by1)) + 2)
    ma, mb = raster(a, box), raster(b, box)
    return (ma & mb).sum() / max((ma | mb).sum(), 1)


def score(pred, gt, volumes, shape):
    """One-to-one greedy matching on mask IoU. Scored inside the wall only, as the app crops to the wall: for real
    photos that is the hull of the labelled holds. Detections sitting on an unlabelled volume are not counted."""
    hull = None
    if volumes is not None:
        hull = np.zeros(shape, np.uint8)
        cv2.fillConvexPoly(hull, cv2.convexHull(np.vstack(gt + volumes).astype(np.int32)), 1)
        hull = cv2.dilate(hull, np.ones((25, 25), np.uint8))
        vol = np.zeros(shape, np.uint8)
        for v in volumes:
            cv2.fillPoly(vol, [v.astype(np.int32)], 1)
        kept = []
        for p in pred:
            c = np.clip(p.mean(0).astype(int), 0, (shape[1] - 1, shape[0] - 1))
            if hull[c[1], c[0]] and not vol[c[1], c[0]]:
                kept.append(p)
            elif hull[c[1], c[0]] and any(pair_iou(p, g) > 0.3 for g in gt):
                kept.append(p)  # a hold bolted on a volume
        pred = kept
    pairs = []
    for i, g in enumerate(gt):
        for j, p in enumerate(pred):
            v = pair_iou(g, p)
            if v >= 0.1:
                pairs.append((v, i, j))
    pairs.sort(reverse=True)
    gi, pj, ious = set(), set(), []
    for v, i, j in pairs:
        if i not in gi and j not in pj:
            gi.add(i)
            pj.add(j)
            ious.append(v)
    ious = np.array(ious)
    return dict(gt=len(gt), pred=len(pred), tp50=int((ious >= 0.5).sum()), tp25=int((ious >= 0.25).sum()),
                iou_sum=float(ious[ious >= 0.5].sum()))


def summarize(rows):
    gt, pred = sum(r["gt"] for r in rows), sum(r["pred"] for r in rows)
    tp, tp25, iou = sum(r["tp50"] for r in rows), sum(r["tp25"] for r in rows), sum(r["iou_sum"] for r in rows)
    rec, prec = tp / max(gt, 1), tp / max(pred, 1)
    return dict(recall=rec, precision=prec, f1=2 * rec * prec / max(rec + prec, 1e-9), iou=iou / max(tp, 1), found=tp25 / max(gt, 1))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--yolo", default=None)
    ap.add_argument("--name", default="yolo")
    ap.add_argument("--out", default=str(ROOT / "runs" / "benchmark.json"))
    ap.add_argument("--conditions", default=None)
    ap.add_argument("--skip-opencv", action="store_true")
    ap.add_argument("--conf", type=float, default=0.25)
    a = ap.parse_args()
    detectors = {}
    if not a.skip_opencv:
        detectors["opencv"] = lambda im: opencv_baseline.detect(im)
    if a.yolo:
        from yolo_infer import TiledYolo
        y = TiledYolo(a.yolo, conf=a.conf)
        detectors[a.name] = y.detect
    suite = stress_suite()
    names = a.conditions.split(",") if a.conditions else list(suite)
    items = test_images()
    print(len(items), "images", sum(len(i["holds"]) for i in items), "holds", flush=True)
    path = pathlib.Path(a.out)
    results = json.loads(path.read_text()) if path.exists() else {}
    for cond in names:
        rows = defaultdict(list)
        times = defaultdict(list)
        for n, it in enumerate(items):
            rng = np.random.default_rng(1000 + n)
            border = frame_outline(it["img"].shape)
            img, polys = suite[cond](it["img"], it["holds"] + it["volumes"] + [border], rng)
            valid = valid_area(polys[-1], img.shape[:2])
            polys = polys[:-1]
            holds = [p for p in polys[:len(it["holds"])] if inside(p, valid)]
            volumes = polys[len(it["holds"]):]
            for name, det in detectors.items():
                t = time.time()
                pred = [np.asarray(p, np.float32) for p in det(img)]
                times[name].append(time.time() - t)
                pred = [p for p in pred if inside(p, valid)]
                r = score(pred, holds, volumes if it["group"] == "real" else None, img.shape[:2])
                rows[(name, it["group"])].append(r)
        for (name, group), rs in sorted(rows.items()):
            s = summarize(rs)
            s["sec"] = float(np.mean(times[name]))
            results.setdefault(name, {}).setdefault(group, {})[cond] = s
            print(f"{cond:26s} {group:8s} {name:10s} recall={s['recall']:.3f} prec={s['precision']:.3f} F1={s['f1']:.3f} iou={s['iou']:.3f} found={s['found']:.3f}", flush=True)
        path.write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()
