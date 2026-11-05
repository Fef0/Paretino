"""DIY plywood walls with exact labels: real hold cut-outs on procedural plywood.

Public datasets are commercial gyms, but the app's main target is home plywood walls. Each scene is rendered
at the size the app feeds the detector (a rectified wall, long side about 2000 px).

usage: composite.py OUT_DIR COUNT --cutouts train|test [--seed N]
"""
import argparse
import json
import pathlib

import cv2
import numpy as np

from common import ROOT, load_gold

# Finishes seen on home walls: BGR base colours for birch, pine, poplar, aged wood and painted boards.
FINISHES = [
    ((170, 205, 228), 1.0), ((140, 190, 225), 1.0), ((120, 170, 215), 0.8), ((150, 180, 195), 0.6), ((90, 135, 175), 0.5),
    ((235, 235, 232), 0.25), ((70, 70, 72), 0.2), ((150, 150, 150), 0.2), ((120, 90, 50), 0.1),
]


def fbm(h, w, rng, sx, sy, octaves=4):
    """Stretched fractal noise in 0..1: long streaks when sx >> sy."""
    out = np.zeros((h, w), np.float32)
    amp, total = 1.0, 0.0
    for o in range(octaves):
        gh, gw = max(int(h / sy * 2 ** o), 2), max(int(w / sx * 2 ** o), 2)
        out += amp * cv2.resize(rng.random((gh, gw), dtype=np.float32), (w, h), interpolation=cv2.INTER_CUBIC)
        total += amp
        amp *= 0.55
    out /= total
    return (out - out.min()) / max(out.max() - out.min(), 1e-6)


def sheet(h, w, rng, px_cm):
    """One plywood sheet: veneer grain, cathedral bands and a few knots."""
    sideways = rng.random() < 0.5
    hh, ww = (w, h) if sideways else (h, w)
    streak = fbm(hh, ww, rng, 25 * px_cm, 1.2 * px_cm, 5)
    warp = (fbm(hh, ww, rng, 120 * px_cm, 30 * px_cm, 3) - 0.5) * rng.uniform(6, 26)
    x = np.arange(ww, dtype=np.float32)[None, :]
    rings = 0.5 + 0.5 * np.sin((x / (rng.uniform(3, 9) * px_cm)) + warp)
    g = 0.85 * streak + 0.15 * rings * fbm(hh, ww, rng, 60 * px_cm, 10 * px_cm, 2)
    for _ in range(int(rng.integers(0, 7))):
        cx, cy = rng.uniform(0, ww), rng.uniform(0, hh)
        r = rng.uniform(0.5, 2.5) * px_cm
        yy, xx = np.ogrid[:hh, :ww]
        g -= 0.55 * np.exp(-(((xx - cx) / (r * 0.8)) ** 2 + ((yy - cy) / (r * 1.8)) ** 2)).astype(np.float32)
    return g.T.copy() if sideways else g


def wall(h, w, rng, px_cm):
    weights = np.array([f[1] for f in FINISHES])
    base = np.array(FINISHES[rng.choice(len(FINISHES), p=weights / weights.sum())][0], np.float32)
    base = base * rng.uniform(0.9, 1.08)
    img = np.zeros((h, w, 3), np.float32)
    sw, sh = int(122 * px_cm), int(244 * px_cm)
    if rng.random() < 0.5:
        sw, sh = sh, sw
    ox, oy = int(rng.uniform(0, sw)), int(rng.uniform(0, sh))
    painted = base.std() < 3
    for y in range(-oy, h, sh):
        for x in range(-ox, w, sw):
            y0, y1, x0, x1 = max(y, 0), min(y + sh, h), max(x, 0), min(x + sw, w)
            if y1 <= y0 or x1 <= x0:
                continue
            g = sheet(y1 - y0, x1 - x0, rng, px_cm)
            depth = rng.uniform(0.04, 0.1) if painted else rng.uniform(0.14, 0.34)
            tint = base * rng.uniform(0.975, 1.025, 3) * rng.uniform(0.93, 1.05)
            img[y0:y1, x0:x1] = tint * (1 - depth + 2 * depth * g[..., None])
            cv2.rectangle(img, (x, y), (x + sw, y + sh), tuple(float(v) * 0.45 for v in base), max(1, int(0.12 * px_cm)))
    return img


def tnuts(img, rng, px_cm):
    """Regular or staggered t-nut grid: dark holes that must not be mistaken for holds."""
    h, w = img.shape[:2]
    step = rng.choice([10, 15, 20, 20, 20, 25]) * px_cm
    r = max(1, int(rng.uniform(0.45, 0.7) * px_cm))
    stagger = rng.random() < 0.5
    pts = []
    for j, y in enumerate(np.arange(rng.uniform(0, step), h, step)):
        for x in np.arange(rng.uniform(0, step) if j == 0 else 0, w, step):
            p = (int(x + (step / 2 if stagger and j % 2 else 0)), int(y))
            if rng.random() < 0.97:
                cv2.circle(img, p, r + 1, (60, 62, 66), -1, cv2.LINE_AA)
                cv2.circle(img, p, r, (22, 22, 24), -1, cv2.LINE_AA)
                pts.append(p)
    return pts


def dirt(img, rng, px_cm):
    """Shoe rubber low down, chalk smears, screws and pencil marks."""
    h, w = img.shape[:2]
    layer = np.zeros((h, w), np.float32)
    for _ in range(int(rng.integers(10, 140))):
        x, y = rng.uniform(0, w), h - abs(rng.normal(0, 0.28)) * h
        a = rng.uniform(0, np.pi)
        ln = rng.uniform(1, 9) * px_cm
        cv2.line(layer, (int(x), int(y)), (int(x + ln * np.cos(a)), int(y + ln * np.sin(a))), float(rng.uniform(0.2, 0.9)),
                 max(1, int(rng.uniform(0.15, 0.7) * px_cm)), cv2.LINE_AA)
    layer = cv2.GaussianBlur(layer, (0, 0), 0.25 * px_cm + 0.5)[..., None]
    img[:] = img * (1 - layer) + np.array((42, 42, 46), np.float32) * layer
    for _ in range(int(rng.integers(8, 60))):
        p = (int(rng.uniform(0, w)), int(rng.uniform(0, h)))
        cv2.circle(img, p, max(1, int(0.25 * px_cm)), (120, 125, 130), -1, cv2.LINE_AA)
    chalk = fbm(h, w, rng, 18 * px_cm, 18 * px_cm, 4)
    chalk = np.clip((chalk - rng.uniform(0.62, 0.8)) * 4, 0, 1)[..., None] * rng.uniform(0.05, 0.3)
    img[:] = img * (1 - chalk) + 235 * chalk


class Cutouts:
    """Real holds from hand labels (and optionally pseudo labels). Test scenes use sm and phone holds only."""

    def __init__(self, split, limit=None):
        self.items = []
        for g in load_gold():
            if (split == "train") != (g["set"] == "bh"):
                continue
            im = cv2.imread(str(g["path"]))
            for p, k in zip(g["polys"], g["kinds"]):
                if k == "hold":
                    self._add(im, p)
        if split == "train":
            for f in sorted((ROOT / "datasets/pseudo/bh").glob("*.json")):
                d = json.loads(f.read_text())
                if d.get("skipped"):
                    continue
                im = cv2.imread(d["file"])
                for r in d["polys"]:
                    if r["quality"] > 0.8 and r["conf"] > 0.5:
                        self._add(im, np.array(r["points"], np.float32))
        print("cutouts", len(self.items), flush=True)

    def _add(self, im, poly):
        x, y, w, h = cv2.boundingRect(poly.astype(np.int32))
        if min(w, h) < 24 or x < 0 or y < 0 or x + w > im.shape[1] or y + h > im.shape[0]:
            return
        m = np.zeros((h, w), np.uint8)
        cv2.fillPoly(m, [(poly - (x, y)).astype(np.int32)], 255)
        self.items.append((im[y:y + h, x:x + w].copy(), m))

    def sample(self, rng, size_px):
        crop, m = self.items[rng.integers(len(self.items))]
        s = size_px / max(crop.shape[:2])
        crop = cv2.resize(crop, None, fx=s, fy=s, interpolation=cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC)
        m = cv2.resize(m, (crop.shape[1], crop.shape[0]), interpolation=cv2.INTER_LINEAR)
        # Random rotation, in a canvas large enough to keep the whole hold.
        d = int(np.hypot(*crop.shape[:2])) + 2
        pad = np.zeros((d, d, 3), np.uint8)
        pm = np.zeros((d, d), np.uint8)
        oy, ox = (d - crop.shape[0]) // 2, (d - crop.shape[1]) // 2
        pad[oy:oy + crop.shape[0], ox:ox + crop.shape[1]] = crop
        pm[oy:oy + crop.shape[0], ox:ox + crop.shape[1]] = m
        r = cv2.getRotationMatrix2D((d / 2, d / 2), rng.uniform(0, 360), 1)
        pad = cv2.warpAffine(pad, r, (d, d), flags=cv2.INTER_LINEAR)
        pm = cv2.warpAffine(pm, r, (d, d), flags=cv2.INTER_LINEAR)
        hsv = cv2.cvtColor(pad, cv2.COLOR_BGR2HSV).astype(np.float32)
        # Gym lighting is dim: bring each hold to a plausible brightness and shift its colour freely.
        inside = pm > 127
        if inside.any():
            v = hsv[..., 2][inside]
            hsv[..., 2] *= rng.uniform(95, 205) / max(float(np.median(v)), 8)
        hsv[..., 0] = (hsv[..., 0] + rng.uniform(0, 180)) % 180 if rng.random() < 0.6 else hsv[..., 0]
        hsv[..., 1] *= rng.uniform(0.75, 1.5)
        pad = cv2.cvtColor(np.clip(hsv, 0, 255).astype(np.uint8), cv2.COLOR_HSV2BGR)
        ys, xs = np.nonzero(pm > 127)
        if len(xs) == 0:
            return None
        sl = (slice(ys.min(), ys.max() + 1), slice(xs.min(), xs.max() + 1))
        return pad[sl], pm[sl]


def scene(cut, rng):
    width_cm = rng.uniform(220, 620)
    aspect = rng.uniform(0.55, 1.1)
    w, h = 2000, int(2000 * aspect)
    px = w / width_cm
    img = wall(h, w, rng, px)
    nuts = tnuts(img, rng, px)
    dirt(img, rng, px)
    labels = np.zeros((h, w), np.int32)
    sun = rng.uniform(0, 2 * np.pi)
    shadow_len = rng.uniform(0.3, 1.6) * px
    target = int(rng.integers(30, 260)) if rng.random() < 0.9 else 0
    hold_id = 0
    for _ in range(target * 3):
        if hold_id >= target:
            break
        cm = float(np.clip(rng.lognormal(np.log(10), 0.55), 3.5, 55))
        got = cut.sample(rng, cm * px)
        if got is None:
            continue
        crop, m = got
        ch, cw = m.shape
        if rng.random() < 0.8 and nuts:
            cx, cy = nuts[rng.integers(len(nuts))]
        else:
            cx, cy = rng.uniform(0, w), rng.uniform(0, h)
        x0, y0 = int(cx - cw / 2), int(cy - ch / 2)
        ax0, ay0, ax1, ay1 = max(x0, 0), max(y0, 0), min(x0 + cw, w), min(y0 + ch, h)
        if ax1 - ax0 < 8 or ay1 - ay0 < 8:
            continue
        sub = (slice(ay0 - y0, ay1 - y0), slice(ax0 - x0, ax1 - x0))
        alpha = cv2.GaussianBlur(m[sub], (0, 0), 0.8).astype(np.float32)[..., None] / 255
        hard = m[sub] > 127
        if (labels[ay0:ay1, ax0:ax1][hard] > 0).mean() > 0.12 if hard.any() else True:
            continue
        # Contact shadow first, then the hold over it.
        pad = int(shadow_len * 3) + 2
        sx0, sy0, sx1, sy1 = max(ax0 - pad, 0), max(ay0 - pad, 0), min(ax1 + pad, w), min(ay1 + pad, h)
        layer = np.zeros((sy1 - sy0, sx1 - sx0), np.float32)
        layer[ay0 - sy0:ay1 - sy0, ax0 - sx0:ax1 - sx0] = alpha[..., 0]
        shift = np.float32([[1, 0, np.cos(sun) * shadow_len * 1.6], [0, 1, np.sin(sun) * shadow_len * 1.6]])
        layer = cv2.GaussianBlur(cv2.warpAffine(layer, shift, layer.shape[::-1]), (0, 0), 0.25 * shadow_len + 0.6)
        img[sy0:sy1, sx0:sx1] *= (1 - 0.55 * layer)[..., None]
        img[ay0:ay1, ax0:ax1] = img[ay0:ay1, ax0:ax1] * (1 - alpha) + crop[sub].astype(np.float32) * alpha
        if rng.random() < 0.7:  # the bolt
            cv2.circle(img, (int(cx), int(cy)), max(1, int(0.6 * px)), (28, 28, 30), -1, cv2.LINE_AA)
        hold_id += 1
        labels[ay0:ay1, ax0:ax1][hard] = hold_id
    img += rng.normal(0, 1.5, img.shape).astype(np.float32)
    polys = []
    for i in range(1, hold_id + 1):
        m = (labels == i).astype(np.uint8)
        cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        if cs:
            c = max(cs, key=cv2.contourArea)
            if cv2.contourArea(c) >= 30:
                polys.append(cv2.approxPolyDP(c, 1.0, True).reshape(-1, 2).tolist())
    return np.clip(img, 0, 255).astype(np.uint8), polys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=pathlib.Path)
    ap.add_argument("count", type=int)
    ap.add_argument("--cutouts", choices=["train", "test"], required=True)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()
    a.out.mkdir(parents=True, exist_ok=True)
    cut = Cutouts(a.cutouts)
    for i in range(a.count):
        rng = np.random.default_rng(a.seed * 100000 + i)
        img, polys = scene(cut, rng)
        cv2.imwrite(str(a.out / f"{i:04d}.jpg"), img, [cv2.IMWRITE_JPEG_QUALITY, 93])
        (a.out / f"{i:04d}.json").write_text(json.dumps(polys))
        print(i, len(polys), flush=True)


if __name__ == "__main__":
    main()
