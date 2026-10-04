"""
Synthetic hand-drawn stroke generator for the S Notes shape detector.

Each sample is an ideal shape path that is then "humanised": rounded corners,
low-frequency wobble, jitter, uneven sampling density, pen-down/pen-up hooks,
closure gaps / overshoot, random start point and direction, rotation, aspect
and size. Negatives (class NONE) are handwriting-like loops, scribbles,
spirals, waves, random curves, figure-8s, hearts and many-cornered zigzags.

Output: one line per stroke: "<label> x0 y0 x1 y1 ..."
Classes follow ShapeRecognizer.Kind order.
"""
import math, random, sys
import numpy as np

NONE, LINE, ARROW, ELLIPSE, TRI, QUAD, PENT, HEX, STAR, ARC, POLY = range(11)

def dense(vertices, closed=False, step=0.004):
    """Polyline -> dense points (unit-ish scale)."""
    pts = []
    vs = list(vertices) + ([vertices[0]] if closed else [])
    for (x0, y0), (x1, y1) in zip(vs[:-1], vs[1:]):
        L = math.hypot(x1 - x0, y1 - y0)
        n = max(2, int(L / step))
        for i in range(n):
            t = i / n
            pts.append((x0 + (x1 - x0) * t, y0 + (y1 - y0) * t))
    pts.append(vs[-1])
    return np.array(pts)

def round_corners(vertices, closed, frac):
    """Replace each corner by a quadratic curve (people round corners)."""
    if frac <= 0.001:
        return list(vertices)
    v = list(vertices)
    n = len(v)
    out = []
    rng = range(n) if closed else range(1, n - 1)
    if not closed:
        out.append(v[0])
    for i in rng:
        p = np.array(v[(i - 1) % n]); c = np.array(v[i]); q = np.array(v[(i + 1) % n])
        r1 = frac * np.linalg.norm(c - p) * random.uniform(0.5, 1.0)
        r2 = frac * np.linalg.norm(q - c) * random.uniform(0.5, 1.0)
        a = c + (p - c) / (np.linalg.norm(p - c) + 1e-9) * r1
        b = c + (q - c) / (np.linalg.norm(q - c) + 1e-9) * r2
        for t in np.linspace(0, 1, 7):
            pt = (1 - t) ** 2 * a + 2 * (1 - t) * t * c + t ** 2 * b
            out.append(tuple(pt))
    if not closed:
        out.append(v[-1])
    return out

def arclen(p):
    d = np.hypot(np.diff(p[:, 0]), np.diff(p[:, 1]))
    return np.concatenate([[0], np.cumsum(d)])

def sample_at(p, s_vals):
    s = arclen(p)
    x = np.interp(s_vals, s, p[:, 0]); y = np.interp(s_vals, s, p[:, 1])
    return np.stack([x, y], 1)

def closed_param_path(p, start_frac, end_extra):
    """Re-start a closed dense path at start_frac of its length and run for
    (1 + end_extra) of the perimeter (gap if negative, overshoot if positive)."""
    s = arclen(p); L = s[-1]
    total = L * (1 + end_extra)
    vals = (start_frac * L + np.linspace(0, total, max(50, len(p)))) % L
    return sample_at(p, vals)

def wobble(p, amp, size):
    if amp <= 0:
        return p
    s = arclen(p); L = s[-1] + 1e-9
    # normals
    d = np.gradient(p, axis=0)
    nrm = np.stack([-d[:, 1], d[:, 0]], 1)
    nrm /= (np.linalg.norm(nrm, axis=1, keepdims=True) + 1e-9)
    off = np.zeros(len(p))
    for _ in range(3):
        f = random.uniform(0.5, 5.0); ph = random.uniform(0, 6.3)
        off += random.uniform(0.2, 1.0) * np.sin(2 * math.pi * f * s / L + ph)
    off *= amp * size / 3
    return p + nrm * off[:, None]

def hook(p, size, at_start):
    """Small pen-landing / lift-off curl."""
    L = random.uniform(0.01, 0.05) * size
    if at_start:
        d = p[0] - p[min(5, len(p) - 1)]
    else:
        d = p[-1] - p[max(-6, -len(p))]
    ang = math.atan2(d[1], d[0]) + random.uniform(-2.2, 2.2)
    n = 5
    end = p[0] if at_start else p[-1]
    seg = [end + np.array([math.cos(ang), math.sin(ang)]) * L * (i / n) for i in range(1, n + 1)]
    seg = np.array(seg)
    return np.concatenate([seg[::-1], p]) if at_start else np.concatenate([p, seg])

def finalize(p, size, closed_like=False, hooks=True):
    """Rotation, scale, uneven sampling, jitter."""
    if hooks and random.random() < 0.3:
        p = hook(p, size, True)
    if hooks and random.random() < 0.3:
        p = hook(p, size, False)
    # uneven sampling: arc-length steps with smooth speed variation
    s = arclen(p); L = s[-1]
    if L <= 0:
        return None
    npts = int(np.clip(L / size * random.uniform(15, 90), 12, 600))
    u = np.linspace(0, 1, npts)
    speed = 1 + 0.6 * np.sin(2 * math.pi * random.uniform(0.5, 4) * u + random.uniform(0, 6))
    speed = np.clip(speed, 0.2, None)
    cs = np.cumsum(speed); cs = (cs - cs[0]) / (cs[-1] - cs[0] + 1e-12)
    q = sample_at(p, cs * L)
    q += np.random.normal(0, random.uniform(0, 0.004) * size, q.shape)
    # rotation: half near axis-aligned, half anything
    a = random.uniform(-0.17, 0.17) if random.random() < 0.5 else random.uniform(0, 2 * math.pi)
    R = np.array([[math.cos(a), -math.sin(a)], [math.sin(a), math.cos(a)]])
    q = q @ R.T
    scale = math.exp(random.uniform(math.log(60), math.log(1600)))
    q *= scale / size
    q += np.array([random.uniform(-3000, 3000), random.uniform(-3000, 3000)])
    if random.random() < 0.5:   # drawing direction
        if closed_like:
            q[:, 0] = -q[:, 0]       # mirror = clockwise vs counter-clockwise
        else:
            q = q[::-1].copy()
    return q

# ------------------------------------------------------------------ classes

def g_line():
    L = 1.0
    bow = random.uniform(-0.03, 0.03)
    t = np.linspace(0, 1, 200)
    p = np.stack([t * L, bow * 4 * t * (1 - t) * L], 1)
    p = wobble(p, random.uniform(0, 0.012), L)
    return finalize(p, L)

def g_arrow():
    L = 1.0
    h = random.uniform(0.12, 0.4) * L
    wa = math.radians(random.uniform(18, 50))
    tail = np.array([0.0, 0.0]); tip = np.array([L, 0.0])
    asym = random.uniform(0.8, 1.2)
    w1 = tip + np.array([-math.cos(wa), math.sin(wa)]) * h
    w2 = tip + np.array([-math.cos(wa), -math.sin(wa)]) * h * asym
    if random.random() < 0.5:
        w1, w2 = w2, w1
    v = random.random()
    if v < 0.55:
        verts = [tail, tip, w1, tip, w2]          # shaft, then out-and-back wings
    elif v < 0.8:
        verts = [w1, tip, w2, tip, tail]          # head first, then shaft
    else:
        verts = [tail, tip, w1, w2, tip]          # triangular head
    verts = round_corners([tuple(x) for x in verts], False, random.uniform(0, 0.05))
    p = dense(verts)
    p = wobble(p, random.uniform(0, 0.01), L)
    return finalize(p, L)

def g_ellipse():
    a = 1.0; b = random.uniform(0.25, 1.0)
    if random.random() < 0.35:
        b = random.uniform(0.85, 1.0)
    t = np.linspace(0, 2 * math.pi, 400, endpoint=False)
    egg = 1 + random.uniform(-0.08, 0.08) * np.cos(t + random.uniform(0, 6))
    p = np.stack([a * np.cos(t) * egg, b * np.sin(t) * egg], 1)
    p = closed_param_path(p, random.random(), random.uniform(-0.08, 0.2))
    # spiral-ish closure drift
    drift = np.linspace(0, random.uniform(-0.06, 0.06), len(p))
    p = p * (1 + drift)[:, None]
    p = wobble(p, random.uniform(0, 0.02), 2)
    return finalize(p, 2, closed_like=True)

def polygon_path(verts, round_frac):
    v = round_corners(verts, True, round_frac)
    p = dense(v, closed=True)
    # start at a corner most of the time
    start = 0.0 if random.random() < 0.6 else random.random()
    p = closed_param_path(p, start, random.uniform(-0.06, 0.16))
    return p

def rand_triangle():
    while True:
        pts = [(random.uniform(0, 1), random.uniform(0, 1)) for _ in range(3)]
        a = np.array(pts)
        angs = []
        for i in range(3):
            u = a[i - 1] - a[i]; w = a[(i + 1) % 3] - a[i]
            angs.append(math.degrees(math.acos(np.clip(u @ w / (np.linalg.norm(u) * np.linalg.norm(w) + 1e-9), -1, 1))))
        if min(angs) > 22 and np.linalg.norm(a.max(0) - a.min(0)) > 0.5:
            return pts

def g_tri():
    r = random.random()
    if r < 0.3:      # equilateral / isosceles upright
        h = random.uniform(0.6, 1.2)
        pts = [(0, 0), (1, 0), (random.uniform(0.3, 0.7), -h)]
    elif r < 0.45:   # right triangle
        pts = [(0, 0), (random.uniform(0.6, 1.2), 0), (0, -random.uniform(0.6, 1.2))]
    else:
        pts = rand_triangle()
    p = polygon_path(pts, random.uniform(0, 0.15))
    p = wobble(p, random.uniform(0, 0.015), 1)
    return finalize(p, 1, closed_like=True)

def g_quad():
    r = random.random()
    w = 1.0; h = random.uniform(0.3, 1.0)
    if r < 0.45:
        pts = [(0, 0), (w, 0), (w, h), (0, h)]                       # rectangle / square
        if random.random() < 0.3:
            pts = [(0, 0), (1, 0), (1, 1), (0, 1)]
    elif r < 0.6:
        pts = [(0.5, 0), (1, h / 2 + 0.25), (0.5, h + 0.5), (0, h / 2 + 0.25)]  # diamond
    elif r < 0.75:
        sk = random.uniform(0.15, 0.4)
        pts = [(sk, 0), (1 + sk, 0), (1, h), (0, h)]                 # parallelogram
    elif r < 0.88:
        i = random.uniform(0.1, 0.35)
        pts = [(i, 0), (1 - i, 0), (1, h), (0, h)]                   # trapezoid
    else:
        while True:  # generic convex quad
            ang = sorted(random.uniform(0, 2 * math.pi) for _ in range(4))
            gaps = np.diff(ang + [ang[0] + 2 * math.pi])
            if gaps.min() > 0.9:
                break
        pts = [(math.cos(a) * random.uniform(0.8, 1.1), math.sin(a) * random.uniform(0.8, 1.1)) for a in ang]
    p = polygon_path(pts, random.uniform(0, 0.15))
    p = wobble(p, random.uniform(0, 0.015), 1)
    return finalize(p, 1, closed_like=True)

def g_ngon(k):
    rot = random.uniform(0, 2 * math.pi)
    pts = []
    for i in range(k):
        a = rot + 2 * math.pi * i / k + random.uniform(-0.12, 0.12)
        r = random.uniform(0.88, 1.12)
        pts.append((math.cos(a) * r, math.sin(a) * r))
    sx = random.uniform(0.8, 1.2)
    pts = [(x * sx, y) for x, y in pts]
    p = polygon_path(pts, random.uniform(0, 0.12))
    p = wobble(p, random.uniform(0, 0.012), 2)
    return finalize(p, 2, closed_like=True)

def g_star():
    rot = random.uniform(0, 2 * math.pi)
    tips = []
    for i in range(5):
        a = rot + 2 * math.pi * i / 5 + random.uniform(-0.08, 0.08)
        r = random.uniform(0.9, 1.1)
        tips.append((math.cos(a) * r, math.sin(a) * r))
    order = [0, 2, 4, 1, 3]
    verts = [tips[i] for i in order]
    p = polygon_path(verts, random.uniform(0, 0.06))
    p = wobble(p, random.uniform(0, 0.012), 2)
    return finalize(p, 2, closed_like=True)

def g_arc():
    sw = math.radians(random.uniform(40, 290))
    a0 = random.uniform(0, 2 * math.pi)
    t = np.linspace(a0, a0 + sw, 300)
    ecc = random.uniform(0.9, 1.1)
    p = np.stack([np.cos(t), ecc * np.sin(t)], 1)
    p = wobble(p, random.uniform(0, 0.012), 2)
    return finalize(p, 2)

def g_poly():
    k = random.randint(2, 4)
    pts = [(0.0, 0.0)]
    ang = random.uniform(0, 2 * math.pi)
    for i in range(k):
        L = random.uniform(0.35, 1.0)
        x, y = pts[-1]
        pts.append((x + math.cos(ang) * L, y + math.sin(ang) * L))
        ang += math.radians(random.uniform(35, 150)) * random.choice([-1, 1])
    a = np.array(pts)
    size = np.linalg.norm(a.max(0) - a.min(0))
    if np.linalg.norm(a[-1] - a[0]) < 0.3 * size:
        return None   # would read as a closed polygon
    v = round_corners(pts, False, random.uniform(0, 0.08))
    p = dense(v)
    p = wobble(p, random.uniform(0, 0.01), size)
    return finalize(p, size)

# --------------------------------------------------------------- negatives

def g_none():
    r = random.random()
    if r < 0.22:   # cursive handwriting-like loops
        n = random.randint(2, 7)
        t = np.linspace(0, n * 2 * math.pi, 120 * n)
        rr = random.uniform(0.3, 0.9)
        adv = random.uniform(0.6, 1.6)
        p = np.stack([adv * t / (2 * math.pi) + rr * np.cos(t + math.pi / 2) * random.uniform(0.5, 1),
                      rr * np.sin(t + math.pi / 2) * random.uniform(0.8, 1.6)], 1)
        p += np.cumsum(np.random.normal(0, 0.01, p.shape), 0)
        size = np.ptp(p, 0).max()
    elif r < 0.38:  # random smooth curve through control points
        k = random.randint(4, 9)
        c = np.random.uniform(0, 1, (k, 2))
        t = np.linspace(0, k - 1, 80 * k)
        x = np.interp(t, np.arange(k), c[:, 0]); y = np.interp(t, np.arange(k), c[:, 1])
        p = np.stack([x, y], 1)
        for _ in range(4):   # smooth
            p[1:-1] = (p[:-2] + p[1:-1] + p[2:]) / 3
        size = np.ptp(p, 0).max() + 1e-6
    elif r < 0.52:  # scribble / zigzag with many corners
        k = random.randint(7, 16)
        pts = [(0, 0)]
        for i in range(k):
            x, y = pts[-1]
            pts.append((x + random.uniform(0.05, 0.3), y + random.uniform(-0.6, 0.6)))
        p = dense(pts)
        size = np.ptp(p, 0).max()
    elif r < 0.62:  # spiral
        turns = random.uniform(1.6, 4)
        t = np.linspace(0, turns * 2 * math.pi, 500)
        rad = 0.15 + t / (turns * 2 * math.pi)
        p = np.stack([rad * np.cos(t), rad * np.sin(t)], 1)
        size = 2.2
    elif r < 0.72:  # wave
        t = np.linspace(0, 1, 400)
        cyc = random.uniform(1.2, 5)
        p = np.stack([t * 2, random.uniform(0.1, 0.4) * np.sin(2 * math.pi * cyc * t)], 1)
        size = 2
    elif r < 0.79:  # figure 8 / infinity
        t = np.linspace(0, 2 * math.pi, 400)
        p = np.stack([np.sin(t), np.sin(t) * np.cos(t)], 1)
        if random.random() < 0.5:
            p = p[:, ::-1].copy()
        size = 2
    elif r < 0.85:  # heart
        t = np.linspace(0, 2 * math.pi, 400)
        p = np.stack([16 * np.sin(t) ** 3,
                      -(13 * np.cos(t) - 5 * np.cos(2 * t) - 2 * np.cos(3 * t) - np.cos(4 * t))], 1) / 16
        p = closed_param_path(p, random.random(), random.uniform(-0.05, 0.1))
        size = 2
    elif r < 0.92:  # many-sided closed polygon / blob with dents (not a target shape)
        k = random.randint(7, 11)
        rot = random.uniform(0, 6.3)
        pts = []
        for i in range(k):
            a = rot + 2 * math.pi * i / k
            rad = random.uniform(0.4, 1.0) if random.random() < 0.5 else 1.0
            pts.append((math.cos(a) * rad, math.sin(a) * rad))
        p = polygon_path(pts, random.uniform(0, 0.05))
        size = 2
    else:  # letters: e / l / s-like strokes
        t = np.linspace(0, 1, 300)
        kind = random.random()
        if kind < 0.33:   # "e"
            a = np.linspace(0, 1.75 * 2 * math.pi, 300)
            p = np.stack([np.cos(a) + 0.0 * a, np.sin(a)], 1)
            p[:60, 1] = 0; p[:60, 0] = np.linspace(-1, 1, 60)
        elif kind < 0.66:  # "s"
            p = np.stack([np.sin(2 * math.pi * t) * 0.6 * (1 - 2 * (t > 0.5)), -t * 2], 1)
            p = np.stack([0.5 * np.sin(2 * math.pi * t), -2 * t], 1)
            p[:, 0] *= np.where(t < 0.5, 1, -1)
        else:              # "l" loop
            a = np.linspace(-math.pi / 2, 1.5 * math.pi, 300)
            p = np.stack([0.3 * np.cos(a) + t * 0.6, 1.2 * np.sin(a) - t * 0.2], 1)
        size = np.ptp(p, 0).max() + 1e-6
    p = wobble(p, random.uniform(0, 0.01), size)
    return finalize(p, size)

GEN = {
    NONE: g_none, LINE: g_line, ARROW: g_arrow, ELLIPSE: g_ellipse, TRI: g_tri, QUAD: g_quad,
    PENT: lambda: g_ngon(5), HEX: lambda: g_ngon(6), STAR: g_star, ARC: g_arc, POLY: g_poly,
}
WEIGHT = {NONE: 2.0}

def main(n_per_class, seed, out):
    random.seed(seed); np.random.seed(seed)
    with open(out, "w") as f:
        for label, g in GEN.items():
            want = int(n_per_class * WEIGHT.get(label, 1.0))
            got = 0
            while got < want:
                p = g()
                if p is None or len(p) < 6 or not np.all(np.isfinite(p)):
                    continue
                f.write(str(label) + " " + " ".join(f"{v:.2f}" for v in p.reshape(-1)) + "\n")
                got += 1

if __name__ == "__main__":
    main(int(sys.argv[1]), int(sys.argv[2]), sys.argv[3])
