# Dokumen BERWARNA & dokumen yang DIPEGANG. GT = masker dokumen utuh (bagian yang tertutup jari tetap dihitung).
#  - jenis: kertas warna pekat (merah/biru/hijau/oranye/ungu/...), papan/tanda bersudut bulat dengan bingkai putih,
#    poster, sampul buku, brosur (pita & blok warna), kartu bergradasi; plus struk/A4 putih untuk kasus dipegang.
#  - adegan: di atas meja (latar gen6), dipegang tangan dengan latar ruangan buram (lampu, jendela, orang),
#    tergantung di plafon (rangka besi, lampu neon, atap tembus cahaya) / menempel di dinding.
#  - pengganggu: bidang putih terang di samping dokumen (lampu/jendela/silau) — penyebab hasil "melebar"; papan lain
#    yang terpotong bingkai; foto terlalu terang (putih jenuh).
# Kasus nyata: real/user2.png (papan merah) & real/user3.png (struk dipegang) — laporan pengguna 20-11-2026.
# Pemakaian: python3 gen10.py <seed> <jumlah> <folder> <cases.json>   (set 10: seed 10, 240 foto; set 11: seed 11, 150)
import numpy as np, cv2, json, math, random, sys, os

seed = int(sys.argv[1]); N = int(sys.argv[2]); OUT = sys.argv[3]; CJ = sys.argv[4]
rng = np.random.default_rng(seed); random.seed(seed)
os.makedirs(OUT, exist_ok=True)
CW, CH = 1500, 2000
W, H = 900, 1200

def noise(h, w, s): return rng.normal(0, s, (h, w, 1)).astype(np.float32)
def U(a, b): return float(rng.uniform(a, b))
def hsv(hd, s, v):
    """Warna RGB 0..255 dari rona (derajat), saturasi & nilai 0..1."""
    c = cv2.cvtColor(np.uint8([[[int(hd / 2) % 180, int(s * 255), int(v * 255)]]]), cv2.COLOR_HSV2RGB)[0, 0]
    return tuple(float(x) for x in c)
def vivid():
    return hsv(random.choice([0, 8, 20, 35, 50, 120, 150, 200, 215, 230, 260, 285, 320, 345]) + U(-6, 6), U(0.55, 0.95), U(0.45, 0.95))

# ------------------------------------------------------------------ latar meja (seperti gen6)
def wood(w, h, base):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    f = U(0.008, 0.03); t = np.sin(x * f + np.sin(y * U(0.002, 0.008)) * U(1, 4)) * 0.5 + np.sin(x * f * 5 + y * 0.01) * 0.15
    return np.array(base, np.float32) + t[..., None] * np.array([28, 18, 12], np.float32) + noise(h, w, 5)
def marble(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    t = np.sin(x * 0.006 + y * 0.009 + np.sin(x * 0.02) * 2)
    return np.array([205, 203, 198], np.float32) + t[..., None] * 16 + noise(h, w, 8)
def flat(c, s=4): return lambda w, h: np.array(c, np.float32) + noise(h, w, s)
def granite(w, h, base): return np.array(base, np.float32) + noise(h, w, 20)
def tiles(w, h):
    y, x = np.mgrid[0:h, 0:w]; sz = random.choice([150, 220, 300]); base = U(200, 235)
    img = np.zeros((h, w, 3), np.float32) + base + noise(h, w, 4)
    m = ((x % sz) < 4) | ((y % sz) < 4); img[m] = base - 70; return img
def sheet(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    t = np.sin(x * 0.01 + np.sin(y * 0.008) * 2) * 0.5 + np.sin(y * 0.017) * 0.5
    return np.array(random.choice([[232, 232, 236], [225, 230, 240], [240, 232, 225]]), np.float32) + t[..., None] * 12 + noise(h, w, 3)
def carpet(w, h):
    c = rng.integers(60, 180, 3).astype(np.float32)
    n = cv2.GaussianBlur(rng.normal(0, 1, (h, w)).astype(np.float32), (0, 0), 2)
    return c + n[..., None] * 25 + noise(h, w, 6)

TABLE = {
    'kayu': lambda w, h: wood(w, h, [110, 70, 45]), 'kayumuda': lambda w, h: wood(w, h, [200, 160, 115]),
    'marmer': marble, 'granit': lambda w, h: granite(w, h, [90, 88, 85]), 'putih': flat([238, 238, 236]),
    'abu': flat([170, 172, 175]), 'gelap': flat([40, 40, 44], 6), 'krem': flat([236, 228, 210]), 'ubin': tiles,
    'sprei': sheet, 'karpet': carpet,
}

# ------------------------------------------------------------------ latar adegan tegak (dipegang / tergantung)
def ceiling(w, h):
    """Plafon pasar/kantin: dasar terang, atap tembus cahaya putih jenuh, rangka besi gelap, lampu neon."""
    img = np.zeros((h, w, 3), np.float32) + U(150, 215) + noise(h, w, 5)
    img *= np.linspace(U(0.85, 1.0), U(1.0, 1.15), h)[:, None, None]
    for _ in range(int(U(2, 5))):      # atap tembus cahaya
        x0, y0 = U(-0.2, 0.9) * w, U(-0.1, 0.9) * h; ww, hh = U(0.2, 0.7) * w, U(0.04, 0.2) * h; sk = U(-0.3, 0.3) * ww
        cv2.fillConvexPoly(img, np.int32([[x0, y0], [x0 + ww, y0], [x0 + ww + sk, y0 + hh], [x0 + sk, y0 + hh]]), (255, 255, 255))
    a0 = U(-0.4, 0.4)
    for _ in range(int(U(6, 16))):     # rangka (dua keluarga arah)
        a = a0 + (math.pi / 2 if random.random() < 0.4 else 0) + U(-0.08, 0.08)
        cx, cy = U(0, w), U(0, h); L = 3 * max(w, h)
        p0 = (int(cx - L * math.cos(a)), int(cy - L * math.sin(a))); p1 = (int(cx + L * math.cos(a)), int(cy + L * math.sin(a)))
        cv2.line(img, p0, p1, (U(40, 110),) * 3, int(U(4, 22)))
    for _ in range(int(U(1, 6))):      # lampu neon
        cx, cy = U(0, w), U(0, h); a = a0 + U(-0.1, 0.1); L = U(80, 260)
        cv2.line(img, (int(cx - L * math.cos(a)), int(cy - L * math.sin(a))), (int(cx + L * math.cos(a)), int(cy + L * math.sin(a))), (255, 255, 250), int(U(10, 26)))
    for _ in range(int(U(0, 4))):      # kipas / benda gelap
        cv2.circle(img, (int(U(0, w)), int(U(0, h))), int(U(20, 60)), (U(30, 80),) * 3, -1)
    return img
def room(w, h):
    """Ruangan (latar buram di belakang dokumen yang dipegang): rak, orang, meja, jendela & lampu terang."""
    img = np.zeros((h, w, 3), np.float32) + np.array([U(60, 160), U(60, 160), U(60, 160)], np.float32)
    img *= np.linspace(U(0.7, 1.1), U(0.6, 1.0), h)[:, None, None]
    for _ in range(int(U(10, 30))):
        x0, y0 = int(U(-0.1, 1) * w), int(U(-0.1, 1) * h); x1, y1 = x0 + int(U(0.05, 0.4) * w), y0 + int(U(0.03, 0.3) * h)
        c = random.choice([(U(20, 90),) * 3, vivid(), (U(150, 240),) * 3])
        if random.random() < 0.5: cv2.rectangle(img, (x0, y0), (x1, y1), c, -1)
        else: cv2.ellipse(img, ((x0 + x1) // 2, (y0 + y1) // 2), ((x1 - x0) // 2 + 1, (y1 - y0) // 2 + 1), U(0, 180), 0, 360, c, -1)
    for _ in range(int(U(1, 4))):      # jendela / lampu terang
        x0, y0 = int(U(0, 0.9) * w), int(U(0, 0.9) * h)
        cv2.rectangle(img, (x0, y0), (x0 + int(U(0.08, 0.5) * w), y0 + int(U(0.03, 0.25) * h)), (255, 255, 252), -1)
    return img
def wall(w, h):
    c = random.choice([(240, 238, 232), (225, 225, 228), (235, 225, 205), (205, 220, 230), (230, 215, 215), (200, 200, 195)])
    img = np.array(c, np.float32) + noise(h, w, 3) + cv2.GaussianBlur(rng.normal(0, 1, (h, w)).astype(np.float32), (0, 0), 30)[..., None] * 60
    if random.random() < 0.5:          # kusen pintu / list
        x = int(U(0, w)); cv2.rectangle(img, (x, 0), (x + int(U(20, 60)), h), (U(90, 200),) * 3, -1)
    return img

SCENE = {'plafon': ceiling, 'ruangan': room, 'dinding': wall}

# ------------------------------------------------------------------ dokumen
def text_lines(d, x0, y0, x1, y1, step, thick, col, density=0.85):
    y = y0
    while y < y1:
        if random.random() < density:
            cv2.rectangle(d, (int(x0), int(y)), (int(x0 + (x1 - x0) * U(0.3, 1.0)), int(y + thick)), col, -1)
        y += step
def contrast_ink(c): return (25, 25, 30) if 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2] > 150 else (250, 250, 250)
def rounded(h, w, r):
    a = np.zeros((h, w), np.uint8)
    cv2.rectangle(a, (r, 0), (w - r, h), 255, -1); cv2.rectangle(a, (0, r), (w, h - r), 255, -1)
    for (x, y) in [(r, r), (w - r, r), (w - r, h - r), (r, h - r)]: cv2.circle(a, (x, y), r, 255, -1)
    return a.astype(np.float32) / 255

def make_doc(kind):
    """(gambar RGB float, alpha 0..1 bentuk dokumen)"""
    if kind == 'warna':           # kertas warna pekat + tulisan
        w, h = random.choice([(500, 707), (420, 594), (707, 500)]); c = vivid()
        d = np.zeros((h, w, 3), np.float32) + c
        text_lines(d, 40, 60, w - 40, h - 60, 26, 8, contrast_ink(c))
        if random.random() < 0.4: cv2.rectangle(d, (w // 2 - 80, 40), (w // 2 + 80, 120), (250, 250, 250), -1)
        return d, np.ones((h, w), np.float32)
    if kind == 'papan':           # papan/tanda: sudut bulat, bingkai putih, isi warna pekat, tulisan & gambar garis putih
        w = 640; h = int(w / U(1.4, 2.3)); c = vivid()
        d = np.zeros((h, w, 3), np.float32) + (250, 250, 248)
        rim = int(U(4, 14)); r = int(U(0.04, 0.12) * h)
        inner = rounded(h - 2 * rim, w - 2 * rim, max(2, r - rim))
        d[rim:h - rim, rim:w - rim] = d[rim:h - rim, rim:w - rim] * (1 - inner[..., None]) + np.array(c, np.float32) * inner[..., None]
        ink = (250, 250, 250) if random.random() < 0.8 else contrast_ink(c)
        tx = int(w * U(0.42, 0.55))
        for k in range(int(U(2, 4))):
            y = int(h * (0.18 + 0.2 * k)); cv2.rectangle(d, (tx, y), (tx + int(U(0.25, 0.45) * w), y + int(h * 0.09)), ink, -1)
        cx, cy = int(w * 0.25), int(h * 0.5)
        cv2.circle(d, (cx, cy), int(h * 0.3), ink, int(U(4, 9))); cv2.rectangle(d, (cx - int(h * 0.15), cy - int(h * 0.12)), (cx + int(h * 0.1), cy + int(h * 0.2)), ink, int(U(3, 7)))
        return d, rounded(h, w, r)
    if kind == 'poster':
        w, h = 500, 707; c1 = np.array(vivid(), np.float32); c2 = np.array(vivid(), np.float32)
        t = np.linspace(0, 1, h)[:, None, None]
        d = (c1 * (1 - t) + c2 * t + np.zeros((h, w, 3), np.float32)).astype(np.float32)
        cv2.rectangle(d, (30, 40), (w - 30, 160), contrast_ink(tuple(c1)), -1)
        for _ in range(int(U(3, 8))): cv2.circle(d, (int(U(60, w - 60)), int(U(220, 520))), int(U(20, 90)), vivid(), -1)
        text_lines(d, 40, 560, w - 40, h - 40, 24, 9, contrast_ink(tuple(c2)))
        return d, np.ones((h, w), np.float32)
    if kind == 'sampul':          # sampul buku / majalah
        w, h = 480, 680; c = vivid() if random.random() < 0.7 else (U(20, 60), U(20, 60), U(30, 80))
        d = np.zeros((h, w, 3), np.float32) + c + noise(h, w, 3)
        ink = random.choice([(250, 250, 250), (250, 220, 60), contrast_ink(c)])
        cv2.rectangle(d, (40, 60), (w - 40, 130), ink, -1); cv2.rectangle(d, (80, 150), (w - 80, 180), ink, -1)
        cv2.circle(d, (w // 2, int(h * 0.6)), int(U(70, 140)), vivid(), -1)
        xs = np.arange(w); d *= (1 - 0.25 * np.exp(-(xs / 18.0) ** 2))[None, :, None]       # punggung buku
        return d, np.ones((h, w), np.float32)
    if kind == 'brosur':          # kertas putih dengan pita & blok warna besar
        w, h = 500, 707; d = np.zeros((h, w, 3), np.float32) + (250, 250, 248)
        cv2.rectangle(d, (0, 0), (w, int(h * U(0.15, 0.35))), vivid(), -1)
        for _ in range(int(U(1, 3))):
            x0, y0 = int(U(30, w - 200)), int(U(h * 0.4, h - 220)); cv2.rectangle(d, (x0, y0), (x0 + int(U(120, 200)), y0 + int(U(100, 200))), vivid(), -1)
        text_lines(d, 40, int(h * 0.38), w - 40, h - 40, 26, 7, (40, 40, 50), 0.6)
        if random.random() < 0.5: cv2.rectangle(d, (0, h - 50), (w, h), vivid(), -1)
        return d, np.ones((h, w), np.float32)
    if kind == 'kartuwarna':
        w, h = 428, 270; c1 = np.array(vivid(), np.float32); c2 = np.array(vivid(), np.float32)
        t = np.linspace(0, 1, w)[None, :, None]; d = (c1 * (1 - t) + c2 * t + np.zeros((h, w, 3), np.float32)).astype(np.float32)
        cv2.rectangle(d, (40, 90), (100, 140), (220, 190, 90), -1)
        text_lines(d, 40, 180, 360, 250, 30, 9, contrast_ink(tuple(c1)))
        return d, rounded(h, w, 18)
    if kind == 'struk':
        w, h = 300, int(U(450, 800)); c = random.choice([(246, 246, 242), (250, 250, 250), (240, 238, 230)])
        d = np.zeros((h, w, 3), np.float32) + c
        text_lines(d, 25, 30, w - 25, h - 30, 20, 6, (50, 50, 60))
        return d, np.ones((h, w), np.float32)
    if kind == 'a4':
        w, h = 500, 707; d = np.zeros((h, w, 3), np.float32) + (250, 250, 248)
        text_lines(d, 40, 50, w - 40, h - 50, 26, 8, (40, 40, 50))
        return d, np.ones((h, w), np.float32)
    raise ValueError(kind)

COLORED = ['warna', 'warna', 'papan', 'papan', 'poster', 'sampul', 'brosur', 'kartuwarna']

def homography(src, dst): return cv2.getPerspectiveTransform(np.float32(src), np.float32(dst))

def place(canvas, D, A, corners, shadow):
    dh, dw = D.shape[:2]
    Hd = homography([(0, 0), (dw, 0), (dw, dh), (0, dh)], corners)
    wd = cv2.warpPerspective(D, Hd, (CW, CH), flags=cv2.INTER_LINEAR)
    am = cv2.warpPerspective(A, Hd, (CW, CH), flags=cv2.INTER_LINEAR)
    if shadow:
        sh = cv2.GaussianBlur(am, (0, 0), U(4, 14)); sh = np.roll(np.roll(sh, int(U(2, 12)), 0), int(U(2, 10)), 1)
        canvas = canvas * (1 - U(0.1, 0.35) * sh[..., None])
    return canvas * (1 - am[..., None]) + wd * am[..., None], am

def hand(canvas, corners, mode):
    """Tangan memegang dokumen: telapak di luar, ibu jari menutup tepi dokumen."""
    k = int(rng.integers(0, 4)) if mode == 'bebas' else random.choice([2, 3])   # sisi bawah / kiri paling sering
    p, q = np.array(corners[k]), np.array(corners[(k + 1) % 4])
    c = np.array(corners).mean(0)
    t = U(0.15, 0.85) if random.random() < 0.6 else random.choice([U(0.0, 0.12), U(0.88, 1.0)])
    e = p + (q - p) * t
    out = (e - c) / max(1e-3, np.linalg.norm(e - c))
    skin = np.array(random.choice([(205, 160, 130), (180, 130, 100), (150, 105, 80), (225, 185, 160)]), np.float32) * U(0.8, 1.1)
    palm = e + out * U(110, 200)
    ang = math.degrees(math.atan2(out[1], out[0]))
    cv2.ellipse(canvas, (int(palm[0]), int(palm[1])), (int(U(150, 220)), int(U(110, 160))), ang, 0, 360, skin.tolist(), -1)
    th = e - out * U(10, 45)   # ibu jari di atas dokumen
    cv2.ellipse(canvas, (int(th[0]), int(th[1])), (int(U(55, 90)), int(U(28, 40))), ang + U(-40, 40), 0, 360, (skin * 1.05).tolist(), -1)
    cv2.line(canvas, (int(e[0]), int(e[1])), (int(palm[0]), int(palm[1])), (skin * 0.95).tolist(), int(U(50, 80)))
    return canvas

cases = []
n = 0
while len(cases) < N:
    n += 1
    scene = random.choice(['meja', 'meja', 'pegang', 'pegang', 'gantung'])
    tags = [scene]
    if scene == 'meja':
        bgk = random.choice(list(TABLE)); canvas = TABLE[bgk](CW, CH); kind = random.choice(COLORED)
    elif scene == 'pegang':
        bgk = random.choice(['ruangan', 'ruangan', 'plafon', 'dinding']); canvas = SCENE[bgk](CW, CH)
        kind = random.choice(COLORED + ['struk', 'struk', 'a4', 'kartuwarna'])
        canvas = cv2.GaussianBlur(canvas, (0, 0), U(3, 14))            # latar di luar fokus
    else:
        bgk = random.choice(['plafon', 'plafon', 'dinding']); canvas = SCENE[bgk](CW, CH); kind = random.choice(['papan', 'papan', 'poster', 'warna'])
    D, A = make_doc(kind); dh, dw = D.shape[:2]
    frac = U(0.04, 0.25) if kind in ('kartuwarna', 'struk') else U(0.06, 0.42)
    Vw = CW * 0.8; Vh = Vw * H / W
    s = math.sqrt(frac * Vw * Vh / (dw * dh)); ww, hh = dw * s, dh * s
    cx = CW / 2 + U(-0.2, 0.2) * Vw * (1 - math.sqrt(frac)); cy = CH / 2 + U(-0.2, 0.2) * Vh * (1 - math.sqrt(frac))
    ang = math.radians(U(-35, 35) if scene == 'meja' else U(-12, 12)); ca, sa = math.cos(ang), math.sin(ang)
    corners = []
    for (x, y) in [(-ww / 2, -hh / 2), (ww / 2, -hh / 2), (ww / 2, hh / 2), (-ww / 2, hh / 2)]:
        jx, jy = U(-0.03, 0.03) * ww, U(-0.03, 0.03) * hh
        corners.append((cx + (x + jx) * ca - (y + jy) * sa, cy + (x + jx) * sa + (y + jy) * ca))
    # bidang putih terang tepat di samping dokumen (lampu / jendela / silau) — penyebab sisi "melebar"
    if scene != 'meja' and random.random() < 0.45:
        k = int(rng.integers(0, 4)); p, q = np.array(corners[k]), np.array(corners[(k + 1) % 4]); c = np.array(corners).mean(0)
        mid = (p + q) / 2; out = (mid - c) / max(1e-3, np.linalg.norm(mid - c)); L = np.linalg.norm(q - p)
        g0 = mid + out * U(0, 30); g1 = mid + out * U(80, 260)
        poly = np.int32([g0 - (q - p) / 2 * U(0.6, 1.3), g0 + (q - p) / 2 * U(0.6, 1.3), g1 + (q - p) / 2 * U(0.5, 1.4), g1 - (q - p) / 2 * U(0.5, 1.4)])
        lay = np.zeros((CH, CW), np.float32); cv2.fillConvexPoly(lay, poly, 1); lay = cv2.GaussianBlur(lay, (0, 0), U(2, 25))
        canvas = canvas * (1 - lay[..., None]) + np.array([U(235, 255)] * 3, np.float32) * lay[..., None]; tags.append('terangsamping')
    # papan lain di sekitar (terpotong bingkai) sebagai pengganggu
    if scene != 'meja' and random.random() < 0.35:
        D2, A2 = make_doc(random.choice(['papan', 'poster', 'warna'])); h2, w2 = D2.shape[:2]
        s2 = s * U(0.6, 1.2); x0 = random.choice([U(-0.3, -0.05), U(0.85, 1.1)]) * CW; y0 = U(0, 0.8) * CH
        canvas, _ = place(canvas, D2, A2, [(x0, y0), (x0 + w2 * s2, y0), (x0 + w2 * s2, y0 + h2 * s2), (x0, y0 + h2 * s2)], False); tags.append('papanlain')
    canvas, am = place(canvas, D, A, corners, scene == 'meja')
    Hd = homography([(0, 0), (dw, 0), (dw, dh), (0, dh)], corners)
    tm = cv2.warpPerspective(A, Hd, (CW, CH), flags=cv2.INTER_NEAREST)
    if scene == 'pegang': canvas = hand(canvas, corners, 'bebas' if random.random() < 0.3 else 'bawah'); tags.append('jari')
    elif scene == 'meja' and random.random() < 0.15: canvas = hand(canvas, corners, 'bebas'); tags.append('jari')
    # pencahayaan & paparan
    light = random.choice(['rata', 'rata', 'terang', 'terang', 'gradien', 'hangat', 'dingin', 'silau', 'redup'])
    tags.append(light)
    yy, xx = np.mgrid[0:CH, 0:CW].astype(np.float32)
    if light == 'terang': canvas = canvas * U(1.15, 1.5) + U(10, 40)                     # putih jenuh (seperti real/user2)
    elif light == 'gradien': canvas *= (np.linspace(U(0.6, 0.9), U(1.0, 1.15), CW)[None, :, None])
    elif light == 'hangat': canvas *= np.array([1.05, 0.97, 0.82], np.float32)
    elif light == 'dingin': canvas *= np.array([0.88, 0.97, 1.08], np.float32)
    elif light == 'silau':
        gx, gy = U(0.2, 0.8) * CW, U(0.2, 0.8) * CH; r = U(80, 260)
        canvas += (U(60, 120) * np.exp(-((xx - gx) ** 2 + (yy - gy) ** 2) / (2 * r * r)))[..., None]
    elif light == 'redup': canvas = canvas * U(0.4, 0.6)
    # kamera: perspektif (dari bawah untuk papan tergantung), rotasi kecil
    tilt = U(0, 0.3) if scene != 'gantung' else U(0.1, 0.35)
    side = random.choice(['atas', 'bawah', 'kiri', 'kanan']) if scene != 'gantung' else 'bawah'
    zoom = U(0.0, 0.15)
    vw, vh = Vw * (1 - zoom), Vh * (1 - zoom)
    vx, vy = CW / 2 + U(-0.05, 0.05) * vw, CH / 2 + U(-0.05, 0.05) * vh
    tl = [vx - vw / 2, vy - vh / 2]; tr = [vx + vw / 2, vy - vh / 2]; br = [vx + vw / 2, vy + vh / 2]; bl = [vx - vw / 2, vy + vh / 2]
    e = tilt
    if side == 'atas': tl[0] -= e * vw; tr[0] += e * vw
    if side == 'bawah': bl[0] -= e * vw; br[0] += e * vw
    if side == 'kiri': tl[1] -= e * vh; bl[1] += e * vh
    if side == 'kanan': tr[1] -= e * vh; br[1] += e * vh
    rot = math.radians(U(-6, 6))
    def rt(p): dx, dy = p[0] - vx, p[1] - vy; return (vx + dx * math.cos(rot) - dy * math.sin(rot), vy + dx * math.sin(rot) + dy * math.cos(rot))
    Hc = homography([rt(tl), rt(tr), rt(br), rt(bl)], [(0, 0), (W, 0), (W, H), (0, H)])
    img = cv2.warpPerspective(np.clip(canvas, 0, 255).astype(np.float32), Hc, (W, H), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REPLICATE)
    tmask = cv2.warpPerspective(tm, Hc, (W, H), flags=cv2.INTER_NEAREST)
    pc = cv2.perspectiveTransform(np.float32([corners]), Hc)[0]
    cut = bool(((pc[:, 0] < 4) | (pc[:, 0] > W - 4) | (pc[:, 1] < 4) | (pc[:, 1] > H - 4)).any())
    if cut and random.random() < 0.8: continue                        # sebagian kecil saja yang terpotong bingkai
    if cut: tags.append('terpotong')
    if tilt > 0.15: tags.append('miring')
    img = cv2.GaussianBlur(img, (0, 0), U(0.5, 2.0) if random.random() < 0.85 else U(2.0, 3.5))
    img = img + rng.normal(0, 7 if light == 'redup' else 3, img.shape)
    vis = tmask.sum() / (W * H)
    if vis < 0.02: continue
    if random.random() < 0.3 and scene == 'meja': img = np.ascontiguousarray(np.rot90(img)); tmask = np.ascontiguousarray(np.rot90(tmask))
    name = f"c{seed}_{len(cases):03d}_{scene}_{bgk}_{kind}.jpg"
    cv2.imwrite(os.path.join(OUT, name), np.clip(img, 0, 255).astype(np.uint8)[:, :, ::-1], [cv2.IMWRITE_JPEG_QUALITY, int(U(60, 92))])
    cv2.imwrite(os.path.join(OUT, name.replace('.jpg', '_mask.png')), cv2.resize((tmask > 0.5).astype(np.uint8) * 255, (200, 200), interpolation=cv2.INTER_NEAREST))
    cases.append({'file': name, 'bg': bgk, 'kind': kind, 'tags': tags})
open(CJ, 'w').write('[\n' + ',\n'.join(json.dumps(c) for c in cases) + '\n]')
print(len(cases))
