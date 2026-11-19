"""Polyline → path mulus (garis lurus + kurva Bezier) untuk ikon Depup.

Bentuk ikon dibangun dengan shapely (poligon). Lengkungnya berupa puluhan ruas pendek: tampak patah-patah bila dibuka
di Illustrator/Figma dan memboroskan titik. Modul ini mengenali kembali bentuk aslinya:

  1. ruas lurus        → satu perintah L
  2. busur lingkaran   → kurva kubik (≤ 90° per kurva, pegangan 4/3·tan(θ/4)·r) — rounded rect = 4 garis + 4 kurva,
                         lingkaran = 4 kurva, simetris seperti digambar tangan
  3. sisanya (lengkung bebas) → di-fit Bezier (algoritma Schneider) dengan galat ≤ FIT_ERR

Galat pengenalan TOL dalam satuan grid 24 (0,004 ≈ 1/100 piksel pada 24 dp).
"""
import math

import numpy as np

TOL = 0.004       # simpangan maksimum titik dari garis / lingkaran yang dikenali
FIT_ERR = 0.012   # galat maksimum fit Bezier bebas
MIN_ARC_PTS = 4   # busur minimal 4 titik (3 titik selalu "pas" dengan lingkaran mana pun)


# ------------------------------------------------------------------ geometri dasar
def _dist(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _seg_dist(p, a, b):
    ax, ay = a; bx, by = b; px, py = p
    dx, dy = bx - ax, by - ay
    L2 = dx * dx + dy * dy
    if L2 < 1e-18:
        return _dist(p, a), 0.0
    t = ((px - ax) * dx + (py - ay) * dy) / L2
    tc = min(1.0, max(0.0, t))
    return math.hypot(px - (ax + tc * dx), py - (ay + tc * dy)), t


def clean(ring, eps=1e-4):
    """Buang titik kembar & titik yang (hampir) segaris — dicek terhadap ruas yang BENAR-BENAR tersisa (titik terakhir
    yang disimpan → titik berikutnya), termasuk semua titik yang sudah dibuang sejak itu, supaya galat tidak menumpuk."""
    pts = []
    for p in ring:
        p = (float(p[0]), float(p[1]))
        if not pts or _dist(p, pts[-1]) > 1e-7:
            pts.append(p)
    if len(pts) > 1 and _dist(pts[0], pts[-1]) <= 1e-7:
        pts.pop()
    n = len(pts)
    if n < 4:
        return pts
    # mulai dari titik yang pasti dipertahankan: simpangan terbesar dari tali busur tetangganya (sudut nyata)
    k = max(range(n), key=lambda i: _seg_dist(pts[i], pts[i - 1], pts[(i + 1) % n])[0])
    pts = pts[k:] + pts[:k]
    out = [pts[0]]
    pending = []
    for i in range(1, n):
        a, b, c = out[-1], pts[i], pts[(i + 1) % n]
        ok = True
        for p in pending + [b]:
            d, t = _seg_dist(p, a, c)
            if d >= eps or not (0.0 < t < 1.0):
                ok = False
                break
        if ok:
            pending.append(b)
        else:
            out.append(b)
            pending = []
    return out


def _fit_circle(P):
    """Lingkaran kuadrat-terkecil (Kåsa). Kembalikan (cx, cy, r, simpangan maks) atau None."""
    A = np.asarray(P, dtype=float)
    x, y = A[:, 0], A[:, 1]
    M = np.c_[2 * x, 2 * y, np.ones(len(A))]
    b = x * x + y * y
    try:
        (cx, cy, c), *_ = np.linalg.lstsq(M, b, rcond=None)
    except np.linalg.LinAlgError:
        return None
    r2 = c + cx * cx + cy * cy
    if r2 <= 0:
        return None
    r = math.sqrt(r2)
    dev = float(np.max(np.abs(np.hypot(x - cx, y - cy) - r)))
    return cx, cy, r, dev


def _sweep(P, cx, cy):
    """Sudut total (bertanda) yang ditempuh titik-titik P mengelilingi pusat; None bila arahnya bolak-balik."""
    tot, sgn = 0.0, 0
    prev = math.atan2(P[0][1] - cy, P[0][0] - cx)
    for p in P[1:]:
        a = math.atan2(p[1] - cy, p[0] - cx)
        d = a - prev
        while d > math.pi:
            d -= 2 * math.pi
        while d < -math.pi:
            d += 2 * math.pi
        if abs(d) > 1e-9:
            s = 1 if d > 0 else -1
            if sgn and s != sgn:
                return None
            sgn = s
        if abs(d) > math.radians(60):      # loncatan besar = bukan busur yang dicacah rapat
            return None
        tot += d
        prev = a
    return tot


def _arc_ok(P):
    if len(P) < MIN_ARC_PTS:
        return None
    # busur asli selalu tercacah rata; satu ruas yang jauh lebih panjang = garis lurus yang ikut terseret → tolak
    el = sorted(_dist(P[i], P[i + 1]) for i in range(len(P) - 1))
    if el[-1] > 3.0 * el[len(el) // 2] + 1e-9:
        return None
    f = _fit_circle(P)
    if not f:
        return None
    cx, cy, r, dev = f
    if dev > TOL or r > 400 or r < 0.05:
        return None
    sw = _sweep(P, cx, cy)
    if sw is None or abs(sw) > 2 * math.pi + 1e-6:
        return None
    return cx, cy, r, sw


def _line_ok(P):
    a, b = P[0], P[-1]
    if _dist(a, b) < 1e-9:
        return False
    for p in P[1:-1]:
        d, t = _seg_dist(p, a, b)
        if d > TOL or t <= 0 or t >= 1:
            return False
    return True


# ------------------------------------------------------------------ segmentasi rakus
def _grow(ok, i, n_max):
    """Panjangkan jendela [i, j] selama ok(i, j): naik eksponensial lalu cari biner. Kembalikan j terbesar atau None."""
    lo = None
    step = 1
    j = i + MIN_ARC_PTS - 1
    while j <= n_max and ok(i, j):
        lo = j
        j = i + (j - i) * 2
        step += 1
    if lo is None:
        return None
    hi = min(j, n_max + 1)
    while hi - lo > 1:
        mid = (lo + hi) // 2
        if ok(i, mid):
            lo = mid
        else:
            hi = mid
    return lo


def segment(ring):
    """Pecah cincin tertutup menjadi primitif: ('L', i, j) / ('A', i, j, (cx, cy, r, sweep)). Indeks ke Pc (tertutup)."""
    P = clean(ring)
    n = len(P)
    if n < 3:
        return P + P[:1], []
    # mulai di awal ruas terpanjang → tidak pernah mulai di tengah busur (kecuali lingkaran penuh)
    k = max(range(n), key=lambda i: _dist(P[i], P[(i + 1) % n]))
    P = P[k:] + P[:k]
    Pc = P + [P[0]]
    cache = {}

    def arc_ok(i, j):
        key = (i, j)
        if key not in cache:
            cache[key] = _arc_ok(Pc[i:j + 1])
        return cache[key] is not None

    segs = []
    i = 0
    while i < n:
        jl = i + 1
        while jl + 1 <= n and _line_ok(Pc[i:jl + 2]):
            jl += 1
        ja = _grow(arc_ok, i, n)
        if ja is not None and abs(cache[(i, ja)][3]) < math.radians(4):
            ja = None                      # busur terlalu pendek untuk dibedakan dari garis
        if ja is not None and ja > jl:
            segs.append(("A", i, ja, cache[(i, ja)]))
            i = ja
        else:
            segs.append(("L", i, jl))
            i = jl
    return Pc, segs


# ------------------------------------------------------------------ Bezier: busur & fit bebas (Schneider)
def arc_cubics(p0, p1, cx, cy, r, sweep):
    """Busur dari p0 ke p1 (pusat & sudut tempuh diketahui) → daftar kubik [(c1, c2, end)]."""
    m = max(1, int(math.ceil(abs(sweep) / (math.pi / 2) - 1e-6)))
    a0 = math.atan2(p0[1] - cy, p0[0] - cx)
    th = sweep / m
    k = 4.0 / 3.0 * math.tan(th / 4.0) * r
    out = []
    prev = p0
    for s in range(1, m + 1):
        a_s = a0 + th * (s - 1)
        a_e = a0 + th * s
        end = p1 if s == m else (cx + r * math.cos(a_e), cy + r * math.sin(a_e))
        # titik awal/akhir memakai titik nyata (simpangan ≤ TOL) — arah pegangan tetap tegak lurus jari-jari
        c1 = (prev[0] - k * math.sin(a_s), prev[1] + k * math.cos(a_s))
        c2 = (end[0] + k * math.sin(a_e), end[1] - k * math.cos(a_e))
        out.append((c1, c2, end))
        prev = end
    return out


def _bez(b, t):
    mt = 1 - t
    return (mt ** 3 * b[0][0] + 3 * mt * mt * t * b[1][0] + 3 * mt * t * t * b[2][0] + t ** 3 * b[3][0],
            mt ** 3 * b[0][1] + 3 * mt * mt * t * b[1][1] + 3 * mt * t * t * b[2][1] + t ** 3 * b[3][1])


def _bez_d(b, t):
    mt = 1 - t
    return (3 * mt * mt * (b[1][0] - b[0][0]) + 6 * mt * t * (b[2][0] - b[1][0]) + 3 * t * t * (b[3][0] - b[2][0]),
            3 * mt * mt * (b[1][1] - b[0][1]) + 6 * mt * t * (b[2][1] - b[1][1]) + 3 * t * t * (b[3][1] - b[2][1]))


def _bez_dd(b, t):
    mt = 1 - t
    return (6 * mt * (b[2][0] - 2 * b[1][0] + b[0][0]) + 6 * t * (b[3][0] - 2 * b[2][0] + b[1][0]),
            6 * mt * (b[2][1] - 2 * b[1][1] + b[0][1]) + 6 * t * (b[3][1] - 2 * b[2][1] + b[1][1]))


def _norm(v):
    L = math.hypot(v[0], v[1])
    return (v[0] / L, v[1] / L) if L > 1e-12 else (0.0, 0.0)


def _chord_params(P):
    u = [0.0]
    for i in range(1, len(P)):
        u.append(u[-1] + _dist(P[i], P[i - 1]))
    return [x / u[-1] for x in u] if u[-1] > 0 else u


def _gen_bez(P, u, t1, t2):
    p0, p3 = P[0], P[-1]
    C = [[0.0, 0.0], [0.0, 0.0]]
    X = [0.0, 0.0]
    for p, t in zip(P, u):
        mt = 1 - t
        a1 = (t1[0] * 3 * mt * mt * t, t1[1] * 3 * mt * mt * t)
        a2 = (t2[0] * 3 * mt * t * t, t2[1] * 3 * mt * t * t)
        C[0][0] += a1[0] * a1[0] + a1[1] * a1[1]
        C[0][1] += a1[0] * a2[0] + a1[1] * a2[1]
        C[1][1] += a2[0] * a2[0] + a2[1] * a2[1]
        base = (mt ** 3 + 3 * mt * mt * t, mt ** 3 + 3 * mt * mt * t)
        tmp = (p[0] - (p0[0] * (mt ** 3 + 3 * mt * mt * t) + p3[0] * (3 * mt * t * t + t ** 3)),
               p[1] - (p0[1] * (mt ** 3 + 3 * mt * mt * t) + p3[1] * (3 * mt * t * t + t ** 3)))
        X[0] += a1[0] * tmp[0] + a1[1] * tmp[1]
        X[1] += a2[0] * tmp[0] + a2[1] * tmp[1]
    C[1][0] = C[0][1]
    det = C[0][0] * C[1][1] - C[0][1] * C[1][0]
    seg = _dist(p0, p3)
    if abs(det) > 1e-12:
        al = (X[0] * C[1][1] - X[1] * C[0][1]) / det
        ar = (C[0][0] * X[1] - C[1][0] * X[0]) / det
    else:
        al = ar = 0.0
    eps = 1e-6 * seg
    if al < eps or ar < eps:
        al = ar = seg / 3.0
    return [p0, (p0[0] + t1[0] * al, p0[1] + t1[1] * al), (p3[0] + t2[0] * ar, p3[1] + t2[1] * ar), p3]


def _max_err(P, b, u):
    worst, idx = 0.0, len(P) // 2
    for i, (p, t) in enumerate(zip(P, u)):
        q = _bez(b, t)
        d = math.hypot(q[0] - p[0], q[1] - p[1])
        if d > worst:
            worst, idx = d, i
    return worst, idx


def _reparam(b, P, u):
    out = []
    for p, t in zip(P, u):
        q = _bez(b, t); d1 = _bez_d(b, t); d2 = _bez_dd(b, t)
        num = (q[0] - p[0]) * d1[0] + (q[1] - p[1]) * d1[1]
        den = d1[0] * d1[0] + d1[1] * d1[1] + (q[0] - p[0]) * d2[0] + (q[1] - p[1]) * d2[1]
        out.append(min(1.0, max(0.0, t - num / den)) if abs(den) > 1e-12 else t)
    return out


def fit_bezier(P, t1, t2, err=FIT_ERR, depth=0):
    """Fit rangkaian titik P dengan kubik (rekursif). t1/t2 = arah singgung awal (keluar) & akhir (masuk, terbalik)."""
    if len(P) == 2 or depth > 12:
        d = _dist(P[0], P[-1]) / 3.0
        return [[P[0], (P[0][0] + t1[0] * d, P[0][1] + t1[1] * d), (P[-1][0] + t2[0] * d, P[-1][1] + t2[1] * d), P[-1]]]
    u = _chord_params(P)
    b = _gen_bez(P, u, t1, t2)
    e, split = _max_err(P, b, u)
    if e < err:
        return [b]
    if e < err * 6:
        for _ in range(16):
            u = _reparam(b, P, u)
            b = _gen_bez(P, u, t1, t2)
            e, split = _max_err(P, b, u)
            if e < err:
                return [b]
    split = min(max(split, 1), len(P) - 2)
    tc = _norm((P[split - 1][0] - P[split + 1][0], P[split - 1][1] - P[split + 1][1]))
    return (fit_bezier(P[:split + 1], t1, tc, err, depth + 1)
            + fit_bezier(P[split:], (-tc[0], -tc[1]), t2, err, depth + 1))


# ------------------------------------------------------------------ cincin → perintah path
def _fmt(v):
    s = ("%.3f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def _pt(p):
    return _fmt(p[0]) + " " + _fmt(p[1])


def ring_cmds(ring):
    """Satu cincin → daftar perintah [('M', p) | ('L', p) | ('C', c1, c2, p)] + 'Z'."""
    Pc, segs = segment(ring)
    if not segs:
        return []
    # gabungkan ruas lurus pendek beruntun yang membelok sedikit-sedikit → kurva bebas (Schneider)
    items = []
    run = []

    def flush_run():
        if not run:
            return
        a = run[0][1]; b = run[-1][2]
        pts = Pc[a:b + 1]
        if len(run) >= 3 and _dist(pts[0], pts[-1]) > 1e-6:
            t1 = _norm((pts[1][0] - pts[0][0], pts[1][1] - pts[0][1]))
            t2 = _norm((pts[-2][0] - pts[-1][0], pts[-2][1] - pts[-1][1]))
            for bz in fit_bezier(pts, t1, t2):
                items.append(("C", bz[1], bz[2], bz[3]))
        else:
            for s in run:
                items.append(("L", Pc[s[2]]))
        run.clear()

    def short_bend(s, prev):
        """Ruas lurus yang melanjutkan ruas lurus sebelumnya dengan belokan kecil & panjang serupa = bagian dari
        lengkung bebas yang tercacah (bukan garis lurus asli: ruas lurus asli sudah digabung oleh clean())."""
        if s[0] != "L":
            return False
        if prev is None or prev[0] != "L":
            return True
        a = Pc[prev[1]]; b = Pc[prev[2]]; c = Pc[s[2]]
        L1 = _dist(a, b); L2 = _dist(b, c)
        if L1 < 1e-9 or L2 < 1e-9 or max(L1, L2) > 3.0 * min(L1, L2):
            return False
        v1 = (b[0] - a[0], b[1] - a[1]); v2 = (c[0] - b[0], c[1] - b[1])
        ang = abs(math.atan2(v1[0] * v2[1] - v1[1] * v2[0], v1[0] * v2[0] + v1[1] * v2[1]))
        return ang < math.radians(28)

    prev = None
    for s in segs:
        if short_bend(s, prev):
            run.append(s)
        else:
            flush_run()
            if s[0] == "L":
                items.append(("L", Pc[s[2]]))
            else:
                _, i, j, (cx, cy, r, sw) = s
                for c1, c2, e in arc_cubics(Pc[i], Pc[j], cx, cy, r, sw):
                    items.append(("C", c1, c2, e))
        prev = s
    flush_run()
    # 'L' terakhir yang kembali ke titik awal tidak perlu (Z menutup)
    if items and items[-1][0] == "L" and _dist(items[-1][1], Pc[0]) < 1e-6:
        items.pop()
    return [("M", Pc[0])] + items


def path_d(rings):
    """Daftar cincin (koordinat) → string path SVG/VectorDrawable (M/L/C/Z absolut, 3 desimal)."""
    out = []
    for r in rings:
        cmds = ring_cmds(r)
        if not cmds:
            continue
        s = []
        for c in cmds:
            if c[0] in ("M", "L"):
                s.append(c[0] + _pt(c[1]))
            else:
                s.append("C" + _pt(c[1]) + " " + _pt(c[2]) + " " + _pt(c[3]))
        out.append("".join(s) + "Z")
    return "".join(out)
