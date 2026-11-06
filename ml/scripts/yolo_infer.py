"""Tiled YOLO-seg inference on a whole wall, through ONNX Runtime: the reference for TfliteHoldDetector-style ports.

A rectified wall holds a hundred or more small holds, too small for a single 640 px pass, so the wall is cut into
overlapping tiles at near-native resolution and the per-tile masks are merged.
"""
import cv2
import numpy as np


def sigmoid(x):
    return 1 / (1 + np.exp(-np.clip(x, -30, 30)))


class TiledYolo:
    def __init__(self, path, tile=640, overlap=128, conf=0.25, nms=0.5, work=2000, providers=None):
        import onnxruntime as ort
        self.session = ort.InferenceSession(str(path), providers=providers or ["DmlExecutionProvider", "CPUExecutionProvider"])
        self.input = self.session.get_inputs()[0].name
        self.tile, self.overlap, self.conf, self.nms, self.work = tile, overlap, conf, nms, work

    def tiles(self, w, h):
        step = self.tile - self.overlap
        xs = list(range(0, max(w - self.tile, 0) + 1, step))
        ys = list(range(0, max(h - self.tile, 0) + 1, step))
        if xs[-1] + self.tile < w:
            xs.append(w - self.tile)
        if ys[-1] + self.tile < h:
            ys.append(h - self.tile)
        return [(x, y) for y in ys for x in xs]

    def run_tile(self, rgb):
        """Returns list of (score, box xyxy in tile px, mask logit crop 160x160 full-tile as float)."""
        t = self.tile
        x = np.ascontiguousarray(rgb.transpose(2, 0, 1)[None], dtype=np.float32) / 255
        out = self.session.run(None, {self.input: x})
        pred, proto = out[0][0], out[1][0]  # (N, 6+32) end-to-end or (4+1+32, A)
        if pred.shape[0] < pred.shape[1]:  # anchors layout: cx cy w h score coeffs
            pred = pred.T
            keep = pred[:, 4] >= self.conf
            p = pred[keep]
            boxes = np.stack([p[:, 0] - p[:, 2] / 2, p[:, 1] - p[:, 3] / 2, p[:, 0] + p[:, 2] / 2, p[:, 1] + p[:, 3] / 2], 1)
            scores, coeffs = p[:, 4], p[:, 5:]
            idx = cv2.dnn.NMSBoxes(np.c_[boxes[:, :2], boxes[:, 2:] - boxes[:, :2]].tolist(), scores.tolist(), self.conf, 0.6)
            idx = np.array(idx).reshape(-1).astype(int)
            boxes, scores, coeffs = boxes[idx], scores[idx], coeffs[idx]
        else:  # end-to-end layout: x0 y0 x1 y1 score class coeffs
            keep = pred[:, 4] >= self.conf
            p = pred[keep]
            boxes, scores, coeffs = p[:, :4], p[:, 4], p[:, 6:]
        if not len(boxes):
            return []
        c, ph, pw = proto.shape
        masks = sigmoid((coeffs @ proto.reshape(c, -1))).reshape(-1, ph, pw)
        res = []
        for b, s, m in zip(boxes, scores, masks):
            res.append((float(s), b, m))
        return res

    def polygon(self, box, mask160, offset, scale_back):
        """Mask inside its box -> outline, in original image pixels."""
        t = self.tile
        k = t / mask160.shape[0]
        x0, y0, x1, y1 = np.clip(box, 0, t)
        if x1 - x0 < 3 or y1 - y0 < 3:
            return None
        lx0, ly0 = int(x0 / k), int(y0 / k)
        lx1, ly1 = min(int(np.ceil(x1 / k)) + 1, mask160.shape[1]), min(int(np.ceil(y1 / k)) + 1, mask160.shape[0])
        size = (max(int((lx1 - lx0) * k), 2), max(int((ly1 - ly0) * k), 2))
        m = cv2.resize(mask160[ly0:ly1, lx0:lx1], size, interpolation=cv2.INTER_LINEAR) > 0.5
        # Outside the predicted box is not this hold.
        px0, py0 = lx0 * k, ly0 * k
        m[:, :max(int(x0 - px0), 0)] = False
        m[:max(int(y0 - py0), 0), :] = False
        m[:, int(x1 - px0) + 1:] = False
        m[int(y1 - py0) + 1:, :] = False
        cs, _ = cv2.findContours(m.astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if not cs:
            return None
        c = max(cs, key=cv2.contourArea)
        if cv2.contourArea(c) < 12:
            return None
        c = cv2.approxPolyDP(c, 0.8, True).reshape(-1, 2).astype(np.float32)
        return ((c + (px0, py0) + offset) * scale_back).astype(np.float32)

    def detect(self, bgr):
        h0, w0 = bgr.shape[:2]
        s = min(self.work / max(h0, w0), 1.0) if self.work else 1.0
        img = cv2.resize(bgr, None, fx=s, fy=s, interpolation=cv2.INTER_AREA) if s < 1 else bgr
        rgb = np.ascontiguousarray(img[..., ::-1])
        h, w = rgb.shape[:2]
        if h < self.tile or w < self.tile:
            rgb = cv2.copyMakeBorder(rgb, 0, max(self.tile - h, 0), 0, max(self.tile - w, 0), cv2.BORDER_CONSTANT, value=(114, 114, 114))
            h, w = rgb.shape[:2]
        found = []
        margin = 4
        for x, y in self.tiles(w, h):
            for score, box, mask in self.run_tile(rgb[y:y + self.tile, x:x + self.tile]):
                # A hold cut by an inner tile edge is seen whole by the neighbouring tile.
                cut = ((box[0] < margin and x > 0) or (box[1] < margin and y > 0) or
                       (box[2] > self.tile - margin and x + self.tile < w) or (box[3] > self.tile - margin and y + self.tile < h))
                p = self.polygon(box, mask, np.array((x, y), np.float32), 1 / s)
                if p is not None:
                    found.append((score - (0.5 if cut else 0), p))
        return merge(found, self.nms)


def merge(found, nms):
    """Non-maximum suppression across tiles, on outlines; a contained fragment also loses to its parent."""
    found.sort(key=lambda t: -t[0])
    kept, boxes = [], []
    for _, p in found:
        x0, y0 = p.min(0)
        x1, y1 = p.max(0)
        area = max(cv2.contourArea(p), 1)
        dup = False
        for q, (a0, b0, a1, b1, qa) in zip(kept, boxes):
            if x1 < a0 or a1 < x0 or y1 < b0 or b1 < y0:
                continue
            ox0, oy0 = int(min(x0, a0)) - 1, int(min(y0, b0)) - 1
            size = (int(max(y1, b1)) - oy0 + 2, int(max(x1, a1)) - ox0 + 2)
            ma = np.zeros(size, np.uint8)
            mb = np.zeros(size, np.uint8)
            cv2.fillPoly(ma, [np.round(p - (ox0, oy0)).astype(np.int32)], 1)
            cv2.fillPoly(mb, [np.round(q - (ox0, oy0)).astype(np.int32)], 1)
            inter = (ma & mb).sum()
            if inter / max((ma | mb).sum(), 1) > nms or inter / max(min(ma.sum(), mb.sum()), 1) > 0.7:
                dup = True
                break
        if not dup:
            kept.append(p)
            boxes.append((x0, y0, x1, y1, area))
    return kept
