# Set "putih di atas putih": kertas di atas kain/sprei/papan putih yang menempel ke satu-dua sisinya, foto terlalu
# terang (putih jenuh 255) sehingga batas kertas hanya berupa garis bayangan tipis yang samar. Latar sekitarnya ramai
# (tumpukan baju, buku warna-warni, kipas). Kasus nyata: laporan pengguna 15-11-2026 (real/user1.png).
# GT = masker kertas. Pemakaian: python3 gen8.py <seed> <jumlah> <folder> <cases.json>
import sys, os, math, random, json
import numpy as np, cv2

_here = os.path.dirname(os.path.abspath(__file__))
seed = int(sys.argv[1]); N = int(sys.argv[2]); OUT = sys.argv[3]; CJ = sys.argv[4]
# pakai latar, dokumen & util dari gen6 tanpa menjalankan pembuatannya (N=0)
_g = {'__name__': 'gen6'}
_argv = sys.argv
sys.argv = ['gen6.py', str(seed), '0', OUT, os.devnull]
exec(open(os.path.join(_here, 'gen6.py')).read(), _g)
sys.argv = _argv
U, rng, make_doc, BG, homography = _g['U'], _g['rng'], _g['make_doc'], _g['BG'], _g['homography']
random.seed(seed)
os.makedirs(OUT, exist_ok=True)
CW, CH = 2000, 2600

def clutter(img):
    """Benda warna-warni ramai (buku, kipas, pita meteran, baju bertumpuk)."""
    h, w = img.shape[:2]
    for _ in range(int(rng.integers(6, 16))):
        col = rng.integers(20, 240, 3).tolist()
        x, y = int(U(0, w)), int(U(0, h))
        k = random.random()
        if k < 0.45:
            cv2.rectangle(img, (x, y), (x + int(U(80, 600)), y + int(U(40, 400))), col, -1)
        elif k < 0.7:
            cv2.circle(img, (x, y), int(U(60, 300)), col, -1)
            if random.random() < 0.5:
                for a in range(0, 360, 15):
                    r = U(30, 250); cv2.line(img, (x, y), (int(x + r * math.cos(math.radians(a))), int(y + r * math.sin(math.radians(a)))), (40, 40, 40), 6)
        else:
            pts = np.int32([[x + U(-300, 300), y + U(-300, 300)] for _ in range(5)])
            cv2.fillPoly(img, [pts], col)
    return img

def soft_white(w, h):
    """Kain/sprei/papan putih: hampir rata, lipatan sangat lembut."""
    base = U(236, 252)
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    t = np.sin(x * U(0.002, 0.01) + np.sin(y * U(0.002, 0.008)) * U(0.5, 3)) * U(0, 6)
    c = np.array([base, base - U(0, 6), base - U(0, 10)], np.float32)
    return c + t[..., None] + rng.normal(0, 2, (h, w, 1))

KINDS = ['a4', 'a4', 'a4', 'faktur', 'kusut', 'buku1']
cases = []
for n in range(N):
    bgk = random.choice(['kain', 'karpet', 'kayu', 'sprei', 'semen', 'kayugelap', 'granit'])
    canvas = clutter(BG[bgk](CW, CH))
    kind = random.choice(KINDS)
    D, _ = make_doc(kind); dh, dw = D.shape[:2]
    D = np.minimum(D * U(1.0, 1.06), 255)
    frac = U(0.12, 0.38)
    W, H = 900, 1200
    Vw = 1100.0; Vh = Vw * H / W
    s = math.sqrt(frac * Vw * Vh / (dw * dh)); ww, hh = dw * s, dh * s
    cx = CW / 2 + U(-0.12, 0.12) * Vw; cy = CH / 2 + U(-0.12, 0.12) * Vh
    ang = math.radians(U(-12, 12)); ca, sa = math.cos(ang), math.sin(ang)
    corners = [(cx + x * ca - y * sa, cy + x * sa + y * ca) for (x, y) in [(-ww / 2, -hh / 2), (ww / 2, -hh / 2), (ww / 2, hh / 2), (-ww / 2, hh / 2)]]
    # permukaan putih di bawah kertas: menempel pada 1-2 sisi, lebar, sering sampai keluar bingkai
    sides = random.sample(range(4), 1 if random.random() < 0.6 else 2)
    tags = ['putih' + ''.join('ABKk'[k] for k in sorted(sides))]
    wm = np.zeros((CH, CW), np.float32)
    for k in sides:
        p, q = np.array(corners[k]), np.array(corners[(k + 1) % 4])
        c = np.array([cx, cy]); mid = (p + q) / 2; out = (mid - c) / np.linalg.norm(mid - c)
        along = (q - p) / np.linalg.norm(q - p)
        ext = U(0.4, 1.6) * max(ww, hh); slack = U(0.05, 0.6) * np.linalg.norm(q - p)
        poly = [p - along * slack - out * 30, q + along * slack - out * 30, q + along * slack + out * ext, p - along * slack + out * ext]
        cv2.fillPoly(wm, [np.int32(poly)], 1)
    wm = cv2.GaussianBlur(wm, (0, 0), 3)
    white = soft_white(CW, CH)
    canvas = canvas * (1 - wm[..., None]) + white * wm[..., None]
    Hd = homography([(0, 0), (dw, 0), (dw, dh), (0, dh)], corners)
    am = cv2.warpPerspective(np.ones((dh, dw), np.float32), Hd, (CW, CH), flags=cv2.INTER_LINEAR)
    warped = cv2.warpPerspective(D, Hd, (CW, CH), flags=cv2.INTER_LINEAR)
    tm = cv2.warpPerspective(np.ones((dh, dw), np.float32), Hd, (CW, CH), flags=cv2.INTER_NEAREST)
    # garis bayangan tipis di tepi kertas (satu-satunya petunjuk batas kertas vs kain putih)
    edge = cv2.dilate((am > 0.5).astype(np.uint8), np.ones((3, 3), np.uint8)) - (am > 0.5).astype(np.uint8)
    edge = cv2.GaussianBlur(edge.astype(np.float32), (0, 0), U(1.5, 3.5))
    edge = edge / max(1e-6, edge.max())
    dip = U(0.02, 0.10); tags.append('samar' if dip < 0.05 else 'tipis')
    canvas = canvas * (1 - am[..., None]) + warped * am[..., None]
    # cahaya: terlalu terang (putih jenuh), kadang gradasi
    gain = U(1.0, 1.35); tags.append('terang' if gain > 1.15 else 'normal')
    y, x = np.mgrid[0:CH, 0:CW].astype(np.float32)
    canvas = canvas * gain * (1 + U(-0.12, 0.12) * (x / CW - 0.5))[..., None]
    if random.random() < 0.3: canvas *= np.array([1.03, 1.0, 0.92]); tags.append('hangat')
    # sensor jenuh dulu, BARU bayangan tepi: garis bayangan di HP tetap terlihat walau kertas & kain sama-sama 255
    canvas = np.clip(canvas, 0, 255) * (1 - dip * edge)[..., None]
    # kamera
    tilt = U(0, 0.22); side = random.choice(['atas', 'bawah', 'kiri', 'kanan'])
    zoom = U(0.0, 0.25)
    vw, vh = Vw * (1 - zoom), Vh * (1 - zoom)
    vx, vy = CW / 2 + U(-0.06, 0.06) * vw, CH / 2 + U(-0.06, 0.06) * vh
    tl = [vx - vw / 2, vy - vh / 2]; tr = [vx + vw / 2, vy - vh / 2]; br = [vx + vw / 2, vy + vh / 2]; bl = [vx - vw / 2, vy + vh / 2]
    e = tilt
    if side == 'atas': tl[0] -= e * vw; tr[0] += e * vw
    if side == 'bawah': bl[0] -= e * vw; br[0] += e * vw
    if side == 'kiri': tl[1] -= e * vh; bl[1] += e * vh
    if side == 'kanan': tr[1] -= e * vh; br[1] += e * vh
    Hc = homography([tl, tr, br, bl], [(0, 0), (W, 0), (W, H), (0, H)])
    img = cv2.warpPerspective(np.clip(canvas, 0, 255).astype(np.float32), Hc, (W, H), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REPLICATE)
    tmask = cv2.warpPerspective(tm, Hc, (W, H), flags=cv2.INTER_NEAREST)
    if tilt > 0.12: tags.append('miring')
    img = cv2.GaussianBlur(img, (0, 0), U(0.5, 2.0))
    img = np.clip(img + rng.normal(0, 2.5, img.shape), 0, 255)
    if tmask.sum() / (W * H) < 0.05: continue
    if random.random() < 0.3: img = np.ascontiguousarray(np.rot90(img)); tmask = np.ascontiguousarray(np.rot90(tmask))
    name = f"w{seed}_{n:03d}_{bgk}_{kind}.jpg"
    cv2.imwrite(os.path.join(OUT, name), img.astype(np.uint8)[:, :, ::-1], [cv2.IMWRITE_JPEG_QUALITY, int(U(60, 90))])
    cv2.imwrite(os.path.join(OUT, name.replace('.jpg', '_mask.png')), cv2.resize((tmask > 0.5).astype(np.uint8) * 255, (200, 200), interpolation=cv2.INTER_NEAREST))
    cases.append({'file': name, 'bg': bgk, 'kind': kind, 'tags': tags})
open(CJ, 'w').write('[\n' + ',\n'.join(json.dumps(c) for c in cases) + '\n]')
print(len(cases))
