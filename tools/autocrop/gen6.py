# Benchmark besar: banyak kondisi & perspektif. GT = masker dokumen/halaman yang dituju.
# Pemakaian: python3 gen6.py <seed> <jumlah> <folder> <cases.json>
import numpy as np, cv2, json, math, random, sys, os

seed = int(sys.argv[1]); N = int(sys.argv[2]); OUT = sys.argv[3]; CJ = sys.argv[4]
rng = np.random.default_rng(seed); random.seed(seed)
os.makedirs(OUT, exist_ok=True)
CW, CH = 2000, 2600          # kanvas datar (meja dilihat tegak lurus)

def noise(h, w, s): return rng.normal(0, s, (h, w, 1))
def U(a, b): return float(rng.uniform(a, b))

# ------------------------------------------------------------------ latar
def wood(w, h, base):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    f = U(0.008, 0.03); t = np.sin(x * f + np.sin(y * U(0.002, 0.008)) * U(1, 4)) * 0.5 + np.sin(x * f * 5 + y * 0.01) * 0.15
    return np.array(base, np.float32) + t[..., None] * np.array([28, 18, 12], np.float32) + noise(h, w, 5)
def marble(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    t = np.sin(x * 0.006 + y * 0.009 + np.sin(x * 0.02) * 2)
    return np.array([205, 203, 198], np.float32) + t[..., None] * 16 + noise(h, w, 8)
def granite(w, h, base): return np.array(base, np.float32) + noise(h, w, 20)
def flat(c, s=4): return lambda w, h: np.array(c, np.float32) + noise(h, w, s)
def checker(w, h):
    y, x = np.mgrid[0:h, 0:w]; sz = random.choice([30, 50, 80]); s = ((x // sz + y // sz) % 2)[..., None]
    a = rng.integers(40, 220, 3); b = rng.integers(40, 220, 3)
    return np.where(s, a, b).astype(np.float32) + noise(h, w, 6)
def tiles(w, h):
    y, x = np.mgrid[0:h, 0:w]; sz = random.choice([150, 220, 300]); base = U(200, 235)
    img = np.zeros((h, w, 3), np.float32) + base + noise(h, w, 4)
    m = ((x % sz) < 4) | ((y % sz) < 4); img[m] = base - 70; return img
def sheet(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    t = np.sin(x * 0.01 + np.sin(y * 0.008) * 2) * 0.5 + np.sin(y * 0.017) * 0.5
    c = random.choice([[232, 232, 236], [225, 230, 240], [240, 232, 225]])
    return np.array(c, np.float32) + t[..., None] * 12 + noise(h, w, 3)
def carpet(w, h):
    c = rng.integers(60, 180, 3).astype(np.float32)
    n = cv2.GaussianBlur(rng.normal(0, 1, (h, w)).astype(np.float32), (0, 0), 2)
    return c + n[..., None] * 25 + noise(h, w, 6)
def laptop(w, h):
    tone = random.choice([[238, 230, 200], [228, 228, 232], [200, 200, 205], [60, 60, 65]])
    img = np.zeros((h, w, 3), np.float32) + tone + noise(h, w, 3)
    kx0, ky0 = int(w * U(0.1, 0.25)), int(h * U(0.1, 0.25)); kx1, ky1 = int(w * U(0.75, 0.95)), int(h * U(0.45, 0.6))
    cv2.rectangle(img, (kx0, ky0), (kx1, ky1), (25, 25, 28), -1)
    kw = (kx1 - kx0) // 12; kh = (ky1 - ky0) // 6
    for r in range(6):
        for c in range(12):
            x = kx0 + 6 + c * kw; y = ky0 + 6 + r * kh
            cv2.rectangle(img, (x, y), (x + kw - 10, y + kh - 10), (48, 48, 52), -1)
    for y in range(ky0, ky1, 10):
        for x in range(int(w * 0.02), kx0 - 15, 10): cv2.circle(img, (x, y), 2, (np.array(tone) * 0.5).tolist(), -1)
    cv2.rectangle(img, (int(w * 0.3), int(h * 0.65)), (int(w * 0.7), int(h * 0.9)), (np.array(tone) * 0.9).tolist(), 4)
    return img
def newspaper(w, h):   # latar berupa tulisan (sulit)
    img = np.zeros((h, w, 3), np.float32) + np.array([215, 212, 200]) + noise(h, w, 4)
    for y in range(20, h, 18):
        x = 10
        while x < w:
            l = int(U(20, 90)); cv2.line(img, (x, y), (x + l, y), (90, 90, 90), 3); x += l + int(U(8, 20))
    return img
def concrete(w, h):
    n = cv2.GaussianBlur(rng.normal(0, 1, (h, w)).astype(np.float32), (0, 0), 6)
    return np.array([150, 148, 145], np.float32) + n[..., None] * 30 + noise(h, w, 8)
def split2(w, h):  # meja kayu + lantai
    a = wood(w, h, [110, 70, 45]); b = tiles(w, h); cut = int(h * U(0.5, 0.75))
    yy = np.arange(h)[:, None] + (np.sin(np.arange(w) / U(60, 120)) * 25)[None, :]
    return np.where((yy > cut)[..., None], b, a)

BG = {
    'kayu': lambda w, h: wood(w, h, [110, 70, 45]), 'kayumuda': lambda w, h: wood(w, h, [200, 160, 115]),
    'kayugelap': lambda w, h: wood(w, h, [60, 38, 25]), 'marmer': marble, 'granit': lambda w, h: granite(w, h, [90, 88, 85]),
    'granitputih': lambda w, h: granite(w, h, [200, 198, 195]), 'putih': flat([238, 238, 236]), 'abu': flat([170, 172, 175]),
    'gelap': flat([40, 40, 44], 6), 'krem': flat([236, 228, 210]), 'kain': checker, 'ubin': tiles, 'sprei': sheet,
    'karpet': carpet, 'laptop': laptop, 'koran': newspaper, 'semen': concrete, 'kayu+lantai': split2,
}

# ------------------------------------------------------------------ dokumen
def text_lines(d, x0, y0, x1, y1, step, thick, col, density=0.85):
    y = y0
    while y < y1:
        if random.random() < density:
            cv2.rectangle(d, (x0, y), (x0 + int((x1 - x0) * U(0.3, 1.0)), y + thick), col, -1)
        y += step
def handwriting(d, x0, y0, x1, y1, step, col):
    y = y0
    while y < y1:
        x = x0 + int(U(0, 40))
        while x < x1 - 30 and random.random() < 0.93:
            l = int(U(15, 60))
            for k in range(0, l, 5):
                cv2.line(d, (x + k, y + int(U(-6, 2))), (x + k + 3, y + int(U(-2, 6))), col, 2)
            x += l + int(U(8, 20))
        y += step * random.choice([1, 1, 2])

def make_doc(kind):
    """Mengembalikan (gambar RGB float, masker target (bool, sama ukuran), keterangan)."""
    if kind == 'struk':
        w, h = 300, int(U(500, 900)); c = random.choice([(246, 246, 242), (250, 250, 250), (240, 238, 230)])
        d = np.zeros((h, w, 3), np.float32) + c
        text_lines(d, 25, 30, w - 25, h - 30, 20, 6, (50, 50, 60))
        if random.random() < 0.6: cv2.rectangle(d, (w // 2 - 55, h // 2 - 55), (w // 2 + 55, h // 2 + 55), (25, 25, 25), -1)
        return d, None
    if kind in ('a4', 'a4warna', 'faktur'):
        w, h = 500, 707
        c = (250, 250, 248) if kind != 'a4warna' else random.choice([(250, 240, 170), (250, 210, 220), (200, 225, 250), (210, 245, 210)])
        d = np.zeros((h, w, 3), np.float32) + c
        if kind == 'faktur':
            cv2.rectangle(d, (0, 0), (w, 90), random.choice([(30, 60, 120), (150, 30, 30), (40, 40, 40)]), -1)
            for r in range(8): cv2.line(d, (30, 200 + r * 40), (w - 30, 200 + r * 40), (120, 120, 120), 2)
            text_lines(d, 40, 210, w - 40, 520, 40, 8, (40, 40, 50))
        else:
            text_lines(d, 40, 50, w - 40, h - 50, 26, 8, (40, 40, 50))
            if random.random() < 0.3: cv2.rectangle(d, (w - 130, 40), (w - 40, 110), (int(U(0, 255)), 80, 160), -1)
        return d, None
    if kind == 'ktp':
        w, h = 560, 353; d = np.zeros((h, w, 3), np.float32) + random.choice([(170, 205, 235), (235, 220, 190), (230, 230, 230)])
        cv2.rectangle(d, (20, 20), (w - 20, 60), (40, 90, 160), -1); cv2.rectangle(d, (w - 170, 90), (w - 30, 300), (200, 120, 110), -1)
        text_lines(d, 30, 90, 360, 320, 26, 10, (40, 40, 60))
        return d, None
    if kind == 'kartu':
        w, h = 350, 200; d = np.zeros((h, w, 3), np.float32) + (250, 250, 250)
        text_lines(d, 20, 40, 300, 180, 30, 8, (30, 30, 30)); return d, None
    if kind == 'buku1':   # halaman buku tulis bergaris
        w, h = 500, 680; d = np.zeros((h, w, 3), np.float32) + (245, 242, 230)
        for y in range(60, h - 30, 22): cv2.line(d, (15, y), (w - 15, y), (170, 185, 215), 1)
        handwriting(d, 40, 70, w - 40, h - 60, 22, (40, 40, 90)); return d, None
    if kind == 'spread':  # buku terbuka 2 halaman; target sesuai aturan
        pw, ph = 500, 680
        L = random.choice(['isi', 'kosong']); R = 'isi' if L == 'kosong' else random.choice(['isi', 'kosong'])
        pages = []
        for s in (L, R):
            p = np.zeros((ph, pw, 3), np.float32) + (245, 242, 230)
            for y in range(60, ph - 30, 22): cv2.line(p, (15, y), (pw - 15, y), (170, 185, 215), 1)
            if s == 'isi': handwriting(p, 40, 70, pw - 40, ph - 60, 22, (40, 40, 90))
            pages.append(p)
        sp = np.concatenate([pages[0], np.zeros((ph, 6, 3), np.float32) + 90, pages[1]], 1)
        xs = np.arange(sp.shape[1]); sp *= (1 - 0.3 * np.exp(-((xs - 503) / 18.0) ** 2))[None, :, None]
        m = np.zeros(sp.shape[:2], bool)
        if L == 'kosong': m[:, 506:] = True
        elif R == 'kosong': m[:, :500] = True
        else: m[:] = True
        return sp, m
    if kind == 'kusut':   # kertas kusut: bayangan lipatan
        d, _ = make_doc('a4')
        h, w = d.shape[:2]
        for _ in range(4):
            a = U(0, math.pi); y, x = np.mgrid[0:h, 0:w]
            t = (x * math.cos(a) + y * math.sin(a)); c = U(0, w + h)
            d *= (1 - 0.12 * np.exp(-((t - c) / U(10, 40)) ** 2))[..., None]
        return d, None
    raise ValueError(kind)

KINDS = ['struk', 'struk', 'a4', 'a4', 'a4warna', 'faktur', 'ktp', 'kartu', 'buku1', 'spread', 'kusut']

def homography(src, dst):
    return cv2.getPerspectiveTransform(np.float32(src), np.float32(dst))

cases = []
for n in range(N):
    bgk = random.choice(list(BG)); kind = random.choice(KINDS)
    canvas = BG[bgk](CW, CH)
    # benda pengganggu di meja
    tags = []
    for _ in range(int(rng.integers(0, 3))):
        t = random.choice(['ponsel', 'pulpen', 'kabel', 'mug'])
        x, y = int(U(0, CW)), int(U(0, CH))
        if t == 'ponsel': cv2.rectangle(canvas, (x, y), (x + 220, y + 440), (18, 18, 22), -1)
        elif t == 'pulpen': cv2.line(canvas, (x, y), (x + int(U(-300, 300)), y + int(U(-300, 300))), (30, 60, 170), 12)
        elif t == 'kabel':
            pts = np.int32([[x + int(U(-200, 200)) * k // 3, y + k * 150] for k in range(6)]); cv2.polylines(canvas, [pts], False, (20, 20, 20), 8)
        else: cv2.circle(canvas, (x, y), 110, (200, 200, 205), -1); cv2.circle(canvas, (x, y), 85, (90, 50, 30), -1)
        tags.append(t)
    D, Mt = make_doc(kind); dh, dw = D.shape[:2]
    frac = U(0.03, 0.12) if kind in ('struk', 'ktp', 'kartu') else U(0.12, 0.6)
    if kind == 'spread': frac = U(0.35, 0.8)
    W, H = 900, 1200
    # ukuran tampak kamera di kanvas: dokumen mengisi kira-kira [frac] dari foto
    Vw = 1100.0; Vh = Vw * H / W
    s = math.sqrt(frac * Vw * Vh / (dw * dh)); ww, hh = dw * s, dh * s
    cx = CW / 2 + U(-0.22, 0.22) * Vw * (1 - math.sqrt(frac)); cy = CH / 2 + U(-0.22, 0.22) * Vh * (1 - math.sqrt(frac))
    ang = math.radians(U(-35, 35) if kind not in ('spread',) else U(-10, 10)); ca, sa = math.cos(ang), math.sin(ang)
    corners = []
    for (x, y) in [(-ww / 2, -hh / 2), (ww / 2, -hh / 2), (ww / 2, hh / 2), (-ww / 2, hh / 2)]:
        jx, jy = U(-0.02, 0.02) * ww, U(-0.02, 0.02) * hh
        corners.append((cx + (x + jx) * ca - (y + jy) * sa, cy + (x + jx) * sa + (y + jy) * ca))
    Hd = homography([(0, 0), (dw, 0), (dw, dh), (0, dh)], corners)
    alpha = np.ones((dh, dw), np.float32)
    if random.random() < 0.25:   # tepi melengkung / sobek
        a = np.zeros((dh, dw), np.uint8) + 255; amp = U(0.01, 0.035) * dh
        pts = [(x, int(amp * (0.5 + 0.5 * math.sin(x / dw * math.pi * U(1, 3))))) for x in range(0, dw + 1, 6)]
        cv2.fillPoly(a, [np.int32([(0, 0)] + pts + [(dw, 0)])], 0); alpha = a.astype(np.float32) / 255; tags.append('lengkung')
    warped = cv2.warpPerspective(D, Hd, (CW, CH), flags=cv2.INTER_LINEAR)
    am = cv2.warpPerspective(alpha, Hd, (CW, CH), flags=cv2.INTER_LINEAR)
    tgt = Mt.astype(np.float32) if Mt is not None else np.ones((dh, dw), np.float32)
    tm = cv2.warpPerspective(tgt * alpha, Hd, (CW, CH), flags=cv2.INTER_NEAREST)
    sh = cv2.GaussianBlur(am, (0, 0), U(4, 14)); sh = np.roll(np.roll(sh, int(U(2, 12)), 0), int(U(2, 10)), 1)
    canvas = canvas * (1 - U(0.1, 0.3) * sh[..., None])
    canvas = canvas * (1 - am[..., None]) + warped * am[..., None]
    # jari memegang tepi dokumen
    if random.random() < 0.15:
        k = int(rng.integers(0, 4)); px, py = corners[k]; qx, qy = corners[(k + 1) % 4]
        fx, fy = (px + qx) / 2, (py + qy) / 2
        cv2.ellipse(canvas, (int(fx), int(fy)), (60, 110), U(0, 180), 0, 360, (150, 110, 90), -1)
        cv2.ellipse(tm, (int(fx), int(fy)), (60, 110), 0, 0, 360, 1, -1)  # jari di atas kertas tetap dianggap bagian dokumen
        tags.append('jari')
    # pencahayaan
    light = random.choice(['rata', 'gradien', 'vinyet', 'hangat', 'dingin', 'bayangan', 'silau', 'redup'])
    tags.append(light)
    y, x = np.mgrid[0:CH, 0:CW].astype(np.float32)
    if light == 'gradien': canvas *= (np.linspace(U(0.6, 0.9), U(1.0, 1.15), CW)[None, :, None])
    elif light == 'vinyet': canvas *= (1 - 0.45 * (((x - CW / 2) / CW) ** 2 + ((y - CH / 2) / CH) ** 2))[..., None] * 1.1
    elif light == 'hangat': canvas *= np.array([1.05, 0.97, 0.82])
    elif light == 'dingin': canvas *= np.array([0.88, 0.97, 1.08])
    elif light == 'bayangan':
        pts = np.int32([[U(0, CW), U(0, CH)] for _ in range(4)]); m = np.zeros((CH, CW), np.float32); cv2.fillConvexPoly(m, pts, 1)
        m = cv2.GaussianBlur(m, (0, 0), U(5, 40)); canvas *= (1 - U(0.3, 0.55) * m)[..., None]
    elif light == 'silau':
        gx, gy = U(0.2, 0.8) * CW, U(0.2, 0.8) * CH; r = U(100, 300)
        canvas += (U(60, 120) * np.exp(-((x - gx) ** 2 + (y - gy) ** 2) / (2 * r * r)))[..., None]
    elif light == 'redup': canvas = canvas * U(0.35, 0.55)
    # kamera: perspektif (miring), rotasi, sebagian keluar bingkai
    tilt = U(0, 0.3); side = random.choice(['atas', 'bawah', 'kiri', 'kanan'])
    zoom = U(0.0, 0.2) if random.random() < 0.85 else U(0.3, 0.5)    # zoom besar = dokumen bisa terpotong bingkai
    vw, vh = Vw * (1 - zoom), Vh * (1 - zoom)
    vx, vy = CW / 2 + U(-0.08, 0.08) * vw, CH / 2 + U(-0.08, 0.08) * vh
    # kamera miring: sisi yang jauh dari kamera tampak lebih lebar di bidang meja (trapesium)
    tl = [vx - vw / 2, vy - vh / 2]; tr = [vx + vw / 2, vy - vh / 2]; br = [vx + vw / 2, vy + vh / 2]; bl = [vx - vw / 2, vy + vh / 2]
    e = tilt
    if side == 'atas': tl[0] -= e * vw; tr[0] += e * vw
    if side == 'bawah': bl[0] -= e * vw; br[0] += e * vw
    if side == 'kiri': tl[1] -= e * vh; bl[1] += e * vh
    if side == 'kanan': tr[1] -= e * vh; br[1] += e * vh
    rot = math.radians(U(-8, 8))
    def rt(p): dx, dy = p[0] - vx, p[1] - vy; return (vx + dx * math.cos(rot) - dy * math.sin(rot), vy + dx * math.sin(rot) + dy * math.cos(rot))
    src = [rt(tl), rt(tr), rt(br), rt(bl)]
    dst = [(0, 0), (W, 0), (W, H), (0, H)]
    Hc = homography(src, dst)
    img = cv2.warpPerspective(np.clip(canvas, 0, 255).astype(np.float32), Hc, (W, H), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REPLICATE)
    tmask = cv2.warpPerspective(tm, Hc, (W, H), flags=cv2.INTER_NEAREST)
    if tilt > 0.15: tags.append('miring')
    if zoom >= 0.3: tags.append('zoom')
    # sensor & kompresi
    blur = U(0.5, 2.5) if random.random() < 0.8 else U(2.5, 4.0)
    img = cv2.GaussianBlur(img, (0, 0), blur)
    if random.random() < 0.15:
        k = int(U(7, 15)); ker = np.zeros((k, k), np.float32); ker[k // 2, :] = 1 / k
        img = cv2.filter2D(img, -1, cv2.warpAffine(ker, cv2.getRotationMatrix2D((k / 2, k / 2), U(0, 180), 1), (k, k))); tags.append('goyang')
    img = img + rng.normal(0, 8 if light == 'redup' else 3, img.shape)
    if blur > 2.5: tags.append('buram')
    vis = tmask.sum() / (W * H)
    if vis < 0.004: continue
    if random.random() < 0.5 and n % 2 == 0: img = np.ascontiguousarray(np.rot90(img)); tmask = np.ascontiguousarray(np.rot90(tmask))
    name = f"g{seed}_{n:03d}_{bgk}_{kind}.jpg"
    cv2.imwrite(os.path.join(OUT, name), np.clip(img, 0, 255).astype(np.uint8)[:, :, ::-1], [cv2.IMWRITE_JPEG_QUALITY, int(U(60, 92))])
    cv2.imwrite(os.path.join(OUT, name.replace('.jpg', '_mask.png')), cv2.resize((tmask > 0.5).astype(np.uint8) * 255, (200, 200), interpolation=cv2.INTER_NEAREST))
    cases.append({'file': name, 'bg': bgk, 'kind': kind, 'tags': tags})
open(CJ, 'w').write('[\n' + ',\n'.join(json.dumps(c) for c in cases) + '\n]')
print(len(cases))
