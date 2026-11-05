"""The OpenCV baseline: Python twin of OpenCvHoldDetector.kt, the detector this work is measured against."""
import cv2
import glob
import os
import sys
from collections import defaultdict

import numpy as np

P = dict(work=1400, norm=False, norm_target=150, bg_div=4, bg_k=0.14, k=6, dom_frac=0.30, near_mult=2.0,
         near_min=12, near_max=30, wL=0.6, t_min=10, t_max=45, noise_mult=2.5, noise_add=6, open_k=3, close_k=5,
         split=True, canny_lo=25, canny_hi=60, seed_frac=0.3, min_area=0.00006, max_area=0.06, min_solidity=0.35,
         min_thick=0.003, min_aspect=0.1, contain=0.6, eps=0.003, grow=2, zones=True, zone_frac=0.01, edge_frac=0, zone_hard=False)


def normalize(img, p):
    """Brightness + white balance so the wall (median colour) lands at a fixed lightness."""
    f = img.astype(np.float32)
    med = np.median(f.reshape(-1, 3), axis=0) + 1
    f *= (med.mean() / med) ** 0.7
    f = np.clip(f, 0, 255)
    m = float(np.median(cv2.cvtColor(f.astype(np.uint8), cv2.COLOR_BGR2GRAY))) + 1
    g = float(np.clip(np.log(p['norm_target'] / 255) / np.log(m / 255), 0.4, 2.5))
    return np.clip(255 * (f / 255) ** g, 0, 255).astype(np.uint8)


def wdist(lab_f, c, wL):
    d = lab_f - np.asarray(c, np.float32)
    return np.sqrt((wL * d[..., 0]) ** 2 + d[..., 1] ** 2 + d[..., 2] ** 2)


def background(lab, p):
    """Plywood / single-material walls: local mean of pixels near the dominant colour.
    Multi-colour walls (no dominant cluster): large median filter."""
    H, W = lab.shape[:2]
    small = cv2.resize(lab, (W // p['bg_div'], H // p['bg_div']), interpolation=cv2.INTER_AREA)
    sf = small.astype(np.float32)
    pts = sf.reshape(-1, 3).copy()
    pts[:, 0] *= p['wL']
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1.0)
    cv2.setRNGSeed(1)
    _, labels, centers = cv2.kmeans(pts, p['k'], None, crit, 2, cv2.KMEANS_PP_CENTERS)
    counts = np.bincount(labels.ravel(), minlength=p['k'])
    dom = int(np.argmax(counts))
    frac = counts[dom] / len(labels)
    ks = max(3, int(max(small.shape[:2]) * p['bg_k']) | 1)
    if frac >= p['dom_frac']:
        c = centers[dom].copy()
        c[0] /= p['wL']
        dd = wdist(sf, c, p['wL'])
        spread = float(np.mean(dd.reshape(-1)[labels.ravel() == dom]))
        near_t = float(np.clip(spread * p['near_mult'], p['near_min'], p['near_max']))
        near = (dd < near_t).astype(np.float32)
        num = cv2.blur(sf * near[..., None], (ks, ks))
        den = cv2.blur(near, (ks, ks))[..., None]
        bgs = np.where(den > 0.02, num / np.maximum(den, 1e-6), c[None, None, :])
        mode = f'dominant {frac:.2f}'
    else:
        bgs = cv2.medianBlur(small, ks).astype(np.float32)
        mode = f'median {frac:.2f}'
    return cv2.resize(bgs, (W, H), interpolation=cv2.INTER_LINEAR), mode


def split_touching(img, lab, mask, p):
    """Separate touching holds along colour edges: markers = mask minus Canny edges, then watershed."""
    edges = np.zeros(mask.shape, np.uint8)
    for ch in cv2.split(lab):
        edges |= cv2.Canny(ch, p['canny_lo'], p['canny_hi'])
    k3 = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    core = cv2.bitwise_and(mask, cv2.bitwise_not(cv2.dilate(edges, k3)))
    core = cv2.erode(core, k3)
    n, cc, stats, _ = cv2.connectedComponentsWithStats(core, connectivity=4)
    min_seed = p['min_area'] * mask.size * p['seed_frac']
    markers = np.zeros(mask.shape, np.int32)
    keep = np.zeros(n, bool)
    keep[1:] = stats[1:, cv2.CC_STAT_AREA] >= min_seed
    ids = np.cumsum(keep) + 1
    markers[keep[cc]] = ids[cc[keep[cc]]]
    # mask blobs that got no seed (small or edge-cut holds) become their own marker
    nm, mcc = cv2.connectedComponents(mask, connectivity=8)
    seeded = np.zeros(nm, bool)
    seeded[mcc[markers > 0]] = True
    extra = (~seeded[mcc]) & (mask > 0)
    markers[extra] = mcc[extra] + ids[-1] + 1
    markers[cv2.dilate(mask, k3, iterations=2) == 0] = 1
    cv2.watershed(img, markers)
    out = ((markers > 1) & (mask > 0)).astype(np.uint8) * 255
    cross = cv2.getStructuringElement(cv2.MORPH_CROSS, (3, 3))
    out[cv2.dilate((markers == -1).astype(np.uint8), cross) > 0] = 0
    return out


def threshold(lab, bg, p):
    d = lab.astype(np.float32) - bg
    dist = np.sqrt((p['wL'] * d[..., 0]) ** 2 + d[..., 1] ** 2 + d[..., 2] ** 2)
    d8 = np.clip(dist * 2, 0, 255).astype(np.uint8)
    otsu, _ = cv2.threshold(d8, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)
    noise = float(np.percentile(d8, 50))
    t = float(np.clip(min(otsu, noise * p['noise_mult'] + p['noise_add'] * 2), p['t_min'] * 2, p['t_max'] * 2))
    _, mask = cv2.threshold(d8, t, 255, cv2.THRESH_BINARY)
    return mask, d8, t


def detect(bgr, p=P, debug=None):
    """Returns list of contours (Nx2 float arrays, original image coordinates)."""
    h, w = bgr.shape[:2]
    s = p['work'] / max(h, w)
    img = cv2.resize(bgr, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA)
    if p['norm']:
        img = normalize(img, p)
    img = cv2.GaussianBlur(img, (5, 5), 0)
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2Lab)
    H, W = lab.shape[:2]
    zone_edge = None
    bg, mode = background(lab, p)
    mask, d8, t = threshold(lab, bg, p)
    if p['zones']:
        # Huge foreground regions are lighting zones (sun stripe, spotlight), not holds:
        # re-estimate the background there with a median filter and threshold again.
        n, cc, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
        big = np.zeros(n, bool)
        big[1:] = stats[1:, cv2.CC_STAT_AREA] > p['zone_frac'] * H * W
        if big.any():
            raw_zone = big[cc].astype(np.uint8)
            zone_edge = cv2.morphologyEx(cv2.morphologyEx(raw_zone * 255, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8)), cv2.MORPH_GRADIENT, np.ones((9, 9), np.uint8))
            zone = cv2.dilate(raw_zone, np.ones((15, 15), np.uint8))
            small = cv2.resize(lab, (W // p['bg_div'], H // p['bg_div']), interpolation=cv2.INTER_AREA)
            ks = max(3, int(max(small.shape[:2]) * p['bg_k']) | 1)
            med = cv2.resize(cv2.medianBlur(small, ks), (W, H), interpolation=cv2.INTER_LINEAR).astype(np.float32)
            if p['zone_hard']:
                # Each side of the light boundary takes its background only from its own pixels.
                zc = cv2.morphologyEx(raw_zone, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
                zs = cv2.resize(zc, (W // p['bg_div'], H // p['bg_div']), interpolation=cv2.INTER_NEAREST).astype(np.float32)
                sf = small.astype(np.float32)
                num = cv2.blur(sf * zs[..., None], (ks, ks)); den = cv2.blur(zs, (ks, ks))[..., None]
                inside = cv2.resize(num / np.maximum(den, 1e-6), (W, H), interpolation=cv2.INTER_LINEAR)
                bg = np.where(zc[..., None] > 0, inside, bg)
            else:
                zf = cv2.GaussianBlur(zone.astype(np.float32), (0, 0), 6)[..., None]
                bg = bg * (1 - zf) + med * zf
            mask, d8, t = threshold(lab, bg, p)
            mode += ' +zones'
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (p['open_k'],) * 2))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (p['close_k'],) * 2))
    if p['split']:
        mask = split_touching(img, lab, mask, p)
    cnts, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    area = H * W
    out = []
    for c in cnts:
        a = cv2.contourArea(c)
        if a < p['min_area'] * area or a > p['max_area'] * area:
            continue
        hull = cv2.contourArea(cv2.convexHull(c))
        if hull <= 0 or a / hull < p['min_solidity']:
            continue
        (_, _), (rw, rh), _ = cv2.minAreaRect(c)
        if a / max(rw, rh, 1) < p['min_thick'] * p['work'] or min(rw, rh) / max(rw, rh, 1) < p['min_aspect']:
            continue
        if p['edge_frac'] and on_edges(c, zone_edge, W, H) > p['edge_frac']:
            continue
        out.append(c)
    if p['grow']:
        out = [grow(c, p['grow']) for c in out]
    out = [cv2.approxPolyDP(c, p['eps'] * p['work'], True).reshape(-1, 2).astype(np.float32) / s
           for c in suppress_contained(out, (H, W), p['contain'])]
    if debug is not None:
        debug.update(mask=mask, t=t / 2, mode=mode)
    return out


def on_edges(c, zone_edge, W, H):
    """Fraction of the outline lying on a lighting-zone boundary or hugging the image border."""
    pts = c.reshape(-1, 2)
    border = (pts[:, 0] <= 2) | (pts[:, 1] <= 2) | (pts[:, 0] >= W - 3) | (pts[:, 1] >= H - 3)
    on = border.copy()
    if zone_edge is not None:
        on |= zone_edge[pts[:, 1], pts[:, 0]] > 0
    return on.mean()


def grow(c, r):
    """Dilate one contour by r px (undoes the boundary eaten by blur, threshold and watershed lines)."""
    x, y, w, h = cv2.boundingRect(c)
    m = np.zeros((h + 2 * r + 2, w + 2 * r + 2), np.uint8)
    cv2.drawContours(m, [c - (x - r - 1, y - r - 1)], -1, 255, -1)
    m = cv2.dilate(m, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1)))
    cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    return max(cs, key=cv2.contourArea) + (x - r - 1, y - r - 1)


def bbox(c):
    x0, y0 = c.min(0)
    x1, y1 = c.max(0)
    return x0, y0, x1, y1


def suppress_contained(cnts, shape, frac):
    """Drop a contour when most of its pixels are already covered by a larger kept one (nested fragments)."""
    cover = np.zeros(shape, np.uint8)
    kept = []
    for c in sorted(cnts, key=lambda c: -cv2.contourArea(c)):
        m = np.zeros(shape, np.uint8)
        cv2.drawContours(m, [c], -1, 1, -1)
        n = int(m.sum())
        if n and int((m & cover).sum()) <= frac * n:
            kept.append(c)
            cover |= m
    return kept


