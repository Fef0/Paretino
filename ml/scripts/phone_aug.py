"""Phone-camera degradations: lighting, shadows, lens aberrations, sensor noise and phone image processing.

Every effect takes (bgr uint8, polys [Nx2 float]) and returns the same, so geometric effects keep labels exact.
`random_phone()` chains them for training; `STRESS` is the fixed severity suite used by the benchmark.
"""
import cv2
import numpy as np


def _grid(h, w):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    return x, y


def _f(img):
    return img.astype(np.float32) / 255


def _u8(img):
    return (np.clip(img, 0, 1) * 255 + 0.5).astype(np.uint8)


# --- lighting ---------------------------------------------------------------------------------

def exposure(img, polys, stops=0.0):
    return _u8((_f(img) ** 2.2 * 2 ** stops) ** (1 / 2.2)), polys


def white_balance(img, polys, kelvin_shift=0.0, tint=0.0):
    """kelvin_shift > 0 warm (tungsten), < 0 cool (LED/shade); tint is the green-magenta axis."""
    gains = np.array([1 - 0.5 * kelvin_shift, 1 + tint, 1 + 0.5 * kelvin_shift], np.float32)
    return _u8(_f(img) * gains), polys


def _band_mask(h, w, rng, softness):
    """Hard-edged window light: a band at a random angle."""
    x, y = _grid(h, w)
    a = rng.uniform(-1.2, 1.2) + np.pi / 2
    proj = (x - rng.uniform(0.15, 0.85) * w) * np.cos(a) + (y - rng.uniform(0.2, 0.8) * h) * np.sin(a)
    m = (np.abs(proj) < rng.uniform(0.08, 0.28) * max(h, w)).astype(np.float32)
    return cv2.GaussianBlur(m, (0, 0), softness)


def sun_stripe(img, polys, strength=1.0, rng=None, softness=5.0):
    """A shaft of warm sunlight over a dimmer room: the case in the user's wall photo."""
    rng = rng or np.random.default_rng(0)
    h, w = img.shape[:2]
    m = _band_mask(h, w, rng, softness * max(h, w) / 1600)[..., None]
    lin = _f(img) ** 2.2
    lit = lin * (1 + strength * 3.5) * np.array([0.82, 0.97, 1.08], np.float32)
    dim = lin * (1 - 0.35 * strength)
    return _u8((dim * (1 - m) + lit * m) ** (1 / 2.2)), polys


def cast_shadows(img, polys, strength=0.6, rng=None, count=3):
    """Soft and hard shadows from beams, people and structure: random polygons darken the image."""
    rng = rng or np.random.default_rng(0)
    h, w = img.shape[:2]
    m = np.zeros((h, w), np.float32)
    for _ in range(count):
        c = rng.uniform(0, 1, 2) * (w, h)
        n = int(rng.integers(3, 8))
        r = rng.uniform(0.12, 0.5, n) * max(h, w)
        a = np.sort(rng.uniform(0, 2 * np.pi, n))
        pts = np.stack([c[0] + r * np.cos(a), c[1] + r * np.sin(a)], 1).astype(np.int32)
        layer = np.zeros((h, w), np.float32)
        cv2.fillPoly(layer, [pts], 1.0)
        m = np.maximum(m, cv2.GaussianBlur(layer, (0, 0), rng.uniform(1, 30) * max(h, w) / 1600) * rng.uniform(0.5, 1))
    lin = _f(img) ** 2.2 * (1 - strength * m[..., None])
    return _u8(lin ** (1 / 2.2)), polys


def spotlight(img, polys, falloff=1.0, rng=None):
    """One strong lamp: bright pool, dark edges, typical for a garage."""
    rng = rng or np.random.default_rng(0)
    h, w = img.shape[:2]
    x, y = _grid(h, w)
    d = ((x - rng.uniform(0.2, 0.8) * w) / w) ** 2 + ((y - rng.uniform(0.0, 0.5) * h) / h) ** 2
    g = np.exp(-d * 3.5 * falloff)[..., None] * 1.6 + 0.08
    return _u8((_f(img) ** 2.2 * g) ** (1 / 2.2)), polys


# --- lens -------------------------------------------------------------------------------------

def _remap_radial(img, polys, k1, k2=0.0):
    """Brown radial model. k1 < 0 barrel (ultra-wide lens), k1 > 0 pincushion (tele)."""
    h, w = img.shape[:2]
    cx, cy = w / 2, h / 2
    norm = np.hypot(cx, cy)
    x, y = _grid(h, w)
    u, v = (x - cx) / norm, (y - cy) / norm
    f = 1 + k1 * (u * u + v * v) + k2 * (u * u + v * v) ** 2
    out = cv2.remap(img, (cx + u * f * norm).astype(np.float32), (cy + v * f * norm).astype(np.float32), cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT_101)

    def inverse(p):
        # The image is warped by the inverse of the sampling map: invert by fixed-point iteration.
        d = (p - (cx, cy)) / norm
        q = d.copy()
        for _ in range(8):
            r2 = (q * q).sum(1, keepdims=True)
            q = d / (1 + k1 * r2 + k2 * r2 * r2)
        return q * norm + (cx, cy)

    return out, [inverse(p.astype(np.float64)).astype(np.float32) for p in polys]


def lens_distortion(img, polys, k1=-0.15, k2=0.0):
    return _remap_radial(img, polys, k1, k2)


def chromatic_aberration(img, polys, amount=0.002):
    """Lateral CA: red and blue scaled slightly differently around the centre (colour fringes at the edges)."""
    h, w = img.shape[:2]
    out = img.copy()
    for ch, s in ((0, 1 + amount), (2, 1 - amount)):
        m = np.array([[s, 0, (1 - s) * w / 2], [0, s, (1 - s) * h / 2]], np.float32)
        out[..., ch] = cv2.warpAffine(img[..., ch], m, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT_101)
    return out, polys


def vignette(img, polys, strength=0.5):
    h, w = img.shape[:2]
    x, y = _grid(h, w)
    r2 = ((x - w / 2) / (w / 2)) ** 2 + ((y - h / 2) / (h / 2)) ** 2
    return _u8(_f(img) * (1 - strength * np.clip(r2 / 2, 0, 1))[..., None]), polys


def corner_softness(img, polys, radius=3.0):
    """Cheap lenses: sharp centre, soft corners."""
    h, w = img.shape[:2]
    x, y = _grid(h, w)
    r = np.clip((((x - w / 2) / (w / 2)) ** 2 + ((y - h / 2) / (h / 2)) ** 2) / 2, 0, 1)[..., None]
    soft = cv2.GaussianBlur(img, (0, 0), radius)
    return (img * (1 - r) + soft * r).astype(np.uint8), polys


def defocus(img, polys, radius=3):
    k = np.zeros((2 * radius + 1,) * 2, np.float32)
    cv2.circle(k, (radius, radius), radius, 1, -1)
    return cv2.filter2D(img, -1, k / k.sum()), polys


def motion_blur(img, polys, length=9, angle=0.0):
    k = np.zeros((length, length), np.float32)
    c = length // 2
    dx, dy = np.cos(angle) * c, np.sin(angle) * c
    cv2.line(k, (int(c - dx), int(c - dy)), (int(c + dx), int(c + dy)), 1, 1)
    return cv2.filter2D(img, -1, k / max(k.sum(), 1)), polys


# --- sensor and phone processing ----------------------------------------------------------------

def sensor_noise(img, polys, iso=0.04, rng=None):
    """Shot noise grows with brightness, read noise is constant, chroma noise is blotchy like real phones."""
    rng = rng or np.random.default_rng(0)
    f = _f(img)
    h, w = img.shape[:2]
    shot = rng.normal(0, 1, f.shape).astype(np.float32) * np.sqrt(np.clip(f, 0.002, 1)) * iso
    read = rng.normal(0, 1, f.shape).astype(np.float32) * iso * 0.3
    blotch = cv2.resize(rng.normal(0, iso * 0.8, (h // 4 + 1, w // 4 + 1, 3)).astype(np.float32), (w, h), interpolation=cv2.INTER_LINEAR)
    return _u8(f + shot + read + blotch), polys


def denoise_smear(img, polys, strength=7):
    """Night-mode noise reduction: fine detail turns to watercolour."""
    return cv2.bilateralFilter(img, 7, strength * 6, 5), polys


def oversharpen(img, polys, amount=1.2, radius=1.6):
    blur = cv2.GaussianBlur(img, (0, 0), radius)
    return cv2.addWeighted(img, 1 + amount, blur, -amount, 0), polys


def hdr_tone(img, polys, strength=0.7):
    """Phone HDR: lifted shadows, compressed highlights via local tone mapping."""
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2Lab).astype(np.float32)
    h, w = img.shape[:2]
    base = cv2.GaussianBlur(lab[..., 0], (0, 0), max(h, w) / 40)
    lab[..., 0] = np.clip(lab[..., 0] + strength * (128 - base) * 0.75, 0, 255)
    return cv2.cvtColor(lab.astype(np.uint8), cv2.COLOR_Lab2BGR), polys


def saturation(img, polys, gain=1.3):
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV).astype(np.float32)
    hsv[..., 1] = np.clip(hsv[..., 1] * gain, 0, 255)
    return cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2BGR), polys


def jpeg(img, polys, quality=40):
    return cv2.imdecode(cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, int(quality)])[1], cv2.IMREAD_COLOR), polys


def residual_perspective(img, polys, amount=0.03, rng=None):
    """Corner handles are never placed perfectly: a small leftover keystone after rectification."""
    rng = rng or np.random.default_rng(0)
    h, w = img.shape[:2]
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    dst = (src + rng.uniform(-amount, amount, (4, 2)) * (w, h)).astype(np.float32)
    m = cv2.getPerspectiveTransform(src, dst)
    out = cv2.warpPerspective(img, m, (w, h), borderMode=cv2.BORDER_REFLECT_101)
    return out, [cv2.perspectiveTransform(p.reshape(-1, 1, 2).astype(np.float32), m).reshape(-1, 2) for p in polys]


def random_phone(img, polys, rng):
    """One random phone, room and moment. Each stage fires independently, so clean images remain in the mix."""
    def chance(p):
        return rng.random() < p

    if chance(0.5):
        k1 = rng.uniform(-0.28, -0.03) if chance(0.75) else rng.uniform(0.02, 0.12)
        img, polys = lens_distortion(img, polys, k1, rng.uniform(-0.05, 0.05))
    if chance(0.35):
        img, polys = residual_perspective(img, polys, rng.uniform(0.005, 0.04), rng)
    if chance(0.3):
        img, polys = sun_stripe(img, polys, rng.uniform(0.3, 1.0), rng, rng.uniform(1, 25))
    if chance(0.35):
        img, polys = cast_shadows(img, polys, rng.uniform(0.3, 0.85), rng, int(rng.integers(1, 5)))
    if chance(0.2):
        img, polys = spotlight(img, polys, rng.uniform(0.4, 2.0), rng)
    if chance(0.6):
        img, polys = exposure(img, polys, rng.uniform(-2.2, 1.3))
    if chance(0.6):
        img, polys = white_balance(img, polys, rng.uniform(-0.35, 0.45), rng.uniform(-0.08, 0.08))
    if chance(0.4):
        img, polys = vignette(img, polys, rng.uniform(0.2, 0.7))
    if chance(0.3):
        img, polys = chromatic_aberration(img, polys, rng.uniform(0.0005, 0.004))
    if chance(0.25):
        img, polys = corner_softness(img, polys, rng.uniform(1, 4))
    if chance(0.12):
        img, polys = defocus(img, polys, int(rng.integers(1, 4)))
    if chance(0.12):
        img, polys = motion_blur(img, polys, int(rng.integers(3, 11)), rng.uniform(0, np.pi))
    if chance(0.35):
        img, polys = sensor_noise(img, polys, rng.uniform(0.01, 0.09), rng)
    if chance(0.2):
        img, polys = denoise_smear(img, polys, rng.uniform(3, 10))
    if chance(0.25):
        img, polys = hdr_tone(img, polys, rng.uniform(0.3, 0.9))
    if chance(0.3):
        img, polys = oversharpen(img, polys, rng.uniform(0.3, 1.5), rng.uniform(1, 2.5))
    if chance(0.25):
        img, polys = saturation(img, polys, rng.uniform(0.7, 1.5))
    if chance(0.5):
        img, polys = jpeg(img, polys, rng.integers(35, 92))
    return img, polys


def stress_suite():
    """Fixed conditions for the benchmark: name -> function(img, polys, rng)."""
    return {
        "clean": lambda i, p, r: (i, p),
        "underexposed -2.5EV": lambda i, p, r: exposure(i, p, -2.5),
        "overexposed +1.5EV": lambda i, p, r: exposure(i, p, 1.5),
        "tungsten warm": lambda i, p, r: white_balance(i, p, 0.5),
        "cool LED": lambda i, p, r: white_balance(i, p, -0.4),
        "sun stripe": lambda i, p, r: sun_stripe(i, p, 1.0, r, 4),
        "cast shadows": lambda i, p, r: cast_shadows(i, p, 0.8, r, 4),
        "spotlight": lambda i, p, r: spotlight(i, p, 1.6, r),
        "low light + noise": lambda i, p, r: sensor_noise(*exposure(i, p, -2), 0.09, r),
        "night mode smear": lambda i, p, r: denoise_smear(*sensor_noise(*exposure(i, p, -1.5), 0.06, r), 9),
        "barrel (ultra-wide)": lambda i, p, r: lens_distortion(i, p, -0.3, 0.03),
        "pincushion (tele)": lambda i, p, r: lens_distortion(i, p, 0.14),
        "chromatic aberration": lambda i, p, r: chromatic_aberration(i, p, 0.005),
        "vignette + soft corners": lambda i, p, r: corner_softness(*vignette(i, p, 0.75), 4),
        "defocus": lambda i, p, r: defocus(i, p, 3),
        "motion blur": lambda i, p, r: motion_blur(i, p, 11, 0.6),
        "phone HDR + sharpen": lambda i, p, r: oversharpen(*hdr_tone(i, p, 0.9), 1.5, 2),
        "heavy JPEG": lambda i, p, r: jpeg(i, p, 25),
    }
