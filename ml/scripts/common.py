"""Shared helpers: VIA labels, teacher detector, matching metrics."""
import json
import pathlib

import cv2
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
KAGGLE = ROOT / "datasets" / "kaggle"


def load_gold():
    """Hand-labelled Kaggle images: list of dict(set, file, path, polys=[Nx2 float], kinds=[hold|volume])."""
    out = []
    for name in ["bh", "bh-phone", "sm"]:
        meta = json.load(open(KAGGLE / f"{name}-annotation.json", encoding="utf-8"))["_via_img_metadata"]
        for v in meta.values():
            regions = v["regions"] if isinstance(v["regions"], list) else list(v["regions"].values())
            if not regions:
                continue
            polys, kinds = [], []
            # The phone set was annotated on half-size copies of the EXIF-rotated photos.
            scale = 2.0 if name == "bh-phone" else 1.0
            for r in regions:
                s = r["shape_attributes"]
                polys.append(np.array(list(zip(s["all_points_x"], s["all_points_y"])), np.float32) * scale)
                kinds.append(r["region_attributes"].get("hold_type", "hold"))
            out.append(dict(set=name, file=v["filename"], path=KAGGLE / name / v["filename"], polys=polys, kinds=kinds))
    return out


class Teacher:
    """yolov8n-freeclimbs-detect-2 (ONNX, 2560 px). Returns boxes [x0,y0,x1,y1,score] in image pixels."""

    def __init__(self, providers=("DmlExecutionProvider", "CPUExecutionProvider"), size=2560):
        import onnxruntime as ort
        self.session = ort.InferenceSession(str(ROOT / "weights/teacher/holds-detect.onnx"), providers=list(providers))
        self.input = self.session.get_inputs()[0].name
        self.size = size

    def __call__(self, bgr, conf=0.25, iou=0.5):
        h, w = bgr.shape[:2]
        s = self.size / max(h, w)
        nh, nw = round(h * s), round(w * s)
        canvas = np.full((self.size, self.size, 3), 114, np.uint8)
        top, left = (self.size - nh) // 2, (self.size - nw) // 2
        canvas[top:top + nh, left:left + nw] = cv2.resize(bgr, (nw, nh), interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR)
        x = np.ascontiguousarray(canvas[..., ::-1].transpose(2, 0, 1)[None], dtype=np.float32) / 255
        y = self.session.run(None, {self.input: x})[0][0]
        keep = y[4] >= conf
        cx, cy, bw, bh, sc = y[:, keep]
        x0 = (cx - bw / 2 - left) / s
        y0 = (cy - bh / 2 - top) / s
        idx = cv2.dnn.NMSBoxes(np.stack([x0, y0, bw / s, bh / s], 1).tolist(), sc.tolist(), conf, iou)
        idx = np.array(idx).reshape(-1)
        return np.stack([x0, y0, x0 + bw / s, y0 + bh / s, sc], 1)[idx] if len(idx) else np.zeros((0, 5), np.float32)


def box_iou(a, b):
    """IoU between Nx4 and Mx4 boxes."""
    x0 = np.maximum(a[:, None, 0], b[None, :, 0])
    y0 = np.maximum(a[:, None, 1], b[None, :, 1])
    x1 = np.minimum(a[:, None, 2], b[None, :, 2])
    y1 = np.minimum(a[:, None, 3], b[None, :, 3])
    inter = np.clip(x1 - x0, 0, None) * np.clip(y1 - y0, 0, None)
    area_a = (a[:, 2] - a[:, 0]) * (a[:, 3] - a[:, 1])
    area_b = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
    return inter / np.maximum(area_a[:, None] + area_b[None, :] - inter, 1e-6)


def multiscale_boxes(teacher, bgr, conf=0.3, iou=0.5, grid=2, overlap=0.2):
    """Teacher on the whole image plus overlapping tiles: tiles recover small holds the full pass misses."""
    h, w = bgr.shape[:2]
    boxes = [teacher(bgr, conf=conf)]
    th, tw = int(h / (grid - (grid - 1) * overlap)), int(w / (grid - (grid - 1) * overlap))
    for r in range(grid):
        for c in range(grid):
            y0 = int(r * (h - th) / max(grid - 1, 1))
            x0 = int(c * (w - tw) / max(grid - 1, 1))
            b = teacher(bgr[y0:y0 + th, x0:x0 + tw], conf=conf)
            if not len(b):
                continue
            # Boxes cut by a tile border are unreliable: the full pass or a neighbouring tile has them whole.
            m = np.ones(len(b), bool)
            if x0 > 0:
                m &= b[:, 0] > 3
            if y0 > 0:
                m &= b[:, 1] > 3
            if x0 + tw < w:
                m &= b[:, 2] < tw - 3
            if y0 + th < h:
                m &= b[:, 3] < th - 3
            b = b[m].copy()
            b[:, [0, 2]] += x0
            b[:, [1, 3]] += y0
            boxes.append(b)
    b = np.concatenate(boxes)
    if not len(b):
        return b
    xywh = np.stack([b[:, 0], b[:, 1], b[:, 2] - b[:, 0], b[:, 3] - b[:, 1]], 1)
    idx = np.array(cv2.dnn.NMSBoxes(xywh.tolist(), b[:, 4].tolist(), conf, iou)).reshape(-1)
    return b[idx]
