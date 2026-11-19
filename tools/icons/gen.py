"""
Depup Solid — generator ikon solid membulat (24×24) untuk VectorDrawable Android.

Gaya: bentuk dasar penuh bersudut squircle, detail berupa LUBANG (bukan warna putih — ikon di-tint satu warna di
kode, jadi lubang tetap benar di atas latar apa pun), ujung garis bulat, dan celah kecil antar-bagian.
Semua ikon dibangun dari geometri (shapely) lalu ditulis sebagai satu path even-odd.
"""
import math, os, sys
from shapely.geometry import Point, Polygon, LineString, box, MultiPolygon
from shapely.ops import unary_union
from shapely import affinity

Q = 8           # ruas per seperempat lingkaran (cukup halus di 24dp; titik sedikit = APK kecil)
# Ukuran mengikuti sampel "Depup Office Element Pack" (512 px → grid 24): area ikon 18,75 (tepi 2,6), garis 2,8,
# sudut ubin 2,65 (lingkaran, bukan squircle), lubang/detail ±2,6–2,95, celah antar-bagian 1,4.
SW = 2.8        # tebal garis untuk ikon garis
KW = 2.6        # tebal lubang garis di dalam bentuk solid
GAP = 1.4       # celah antar-bagian
TR = 2.65       # radius sudut ubin (persegi panjang penuh) ukuran penuh


# ------------------------------------------------------------------ primitif
def circle(cx, cy, r):
    return Point(cx, cy).buffer(r, quad_segs=Q)


def squircle(x0, y0, x1, y1, r, n=None):
    """Persegi bersudut bulat LINGKARAN (gaya sampel). Nama lama dipertahankan agar semua ikon ikut berubah."""
    r = max(0.01, min(r, (x1 - x0) / 2 - 0.01, (y1 - y0) / 2 - 0.01))
    return box(x0 + r, y0 + r, x1 - r, y1 - r).buffer(r, quad_segs=Q)


def line(pts, w=SW):
    return LineString(pts).buffer(w / 2, quad_segs=Q, cap_style="round", join_style="round")


def poly(pts, r=1.2):
    """Poligon bersudut bulat (sudut cembung & cekung)."""
    p = Polygon(pts)
    if r <= 0:
        return p
    return p.buffer(-r, quad_segs=Q, join_style="round").buffer(r, quad_segs=Q, join_style="round")


def smooth(g, r):
    """Bulatkan sudut tajam (cembung lalu cekung) pada geometri gabungan."""
    g = g.buffer(-r, quad_segs=Q).buffer(r, quad_segs=Q)
    return g.buffer(r * 0.6, quad_segs=Q).buffer(-r * 0.6, quad_segs=Q)


def arc(cx, cy, r, a0, a1, n=48):
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def plus(cx, cy, L, w):
    return unary_union([line([(cx - L, cy), (cx + L, cy)], w), line([(cx, cy - L), (cx, cy + L)], w)])


def gap(base, part, g=GAP):
    """Bagian [part] diletakkan di atas [base] dengan celah [g] di sekelilingnya."""
    return unary_union([base.difference(part.buffer(g, quad_segs=Q)), part])


def knock(base, *holes):
    return base.difference(unary_union(list(holes)))


def ring(cx, cy, r, w):
    return circle(cx, cy, r).difference(circle(cx, cy, r - w))


def outline(shape, w=SW):
    return shape.difference(shape.buffer(-w, quad_segs=Q, join_style="round"))


def star(cx, cy, ro, ri, k=5, rot=-90):
    pts = []
    for i in range(k * 2):
        r = ro if i % 2 == 0 else ri
        a = math.radians(rot + 180 * i / k)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return Polygon(pts)


def spark(cx, cy, r, waist=0.26):
    """Bintang empat sisi dengan sisi cekung (kilau)."""
    pts = []
    for q in range(4):
        a = math.radians(-90 + 90 * q)
        tip = (cx + r * math.cos(a), cy + r * math.sin(a))
        pts.append(tip)
        # sisi cekung ke tip berikutnya
        a2 = math.radians(-90 + 90 * (q + 1))
        nxt = (cx + r * math.cos(a2), cy + r * math.sin(a2))
        for i in range(1, 12):
            t = i / 12
            # kurva kuadratik dengan titik kendali dekat pusat
            ctrl = (cx + (tip[0] + nxt[0] - 2 * cx) * waist, cy + (tip[1] + nxt[1] - 2 * cy) * waist)
            x = (1 - t) ** 2 * tip[0] + 2 * (1 - t) * t * ctrl[0] + t ** 2 * nxt[0]
            y = (1 - t) ** 2 * tip[1] + 2 * (1 - t) * t * ctrl[1] + t ** 2 * nxt[1]
            pts.append((x, y))
    return Polygon(pts).buffer(-0.35, quad_segs=Q).buffer(0.35, quad_segs=Q)


def check_pts(x, y, s=1.0):
    return [(x - 3.6 * s, y + 0.1 * s), (x - 1.0 * s, y + 2.6 * s), (x + 3.8 * s, y - 2.4 * s)]


# ------------------------------------------------------------------ ikon
I = {}


def icon(name):
    def deco(f):
        I[name] = f
        return f
    return deco


def camera_body(y0=6.6, y1=19.6):
    body = squircle(2.6, y0, 21.4, y1, 4.2)
    bump = squircle(8.0, y0 - 2.6, 16.0, y0 + 2.4, 2.2)
    # hanya fillet cekung di pertemuan tonjolan & badan (pembulatan cembung membuat tonjolan penyok)
    return unary_union([body, bump]).buffer(0.9, quad_segs=Q).buffer(-0.9, quad_segs=Q)


@icon("add_photo")
def _():
    return knock(camera_body(), plus(12, 13.1, 3.0, KW))


@icon("camera")
def _():
    b = knock(camera_body(), circle(12, 13.1, 4.1))
    return unary_union([b, circle(12, 13.1, 2.15), ])


@icon("back")
def _():
    return line([(14.8, 5.2), (8.0, 12), (14.8, 18.8)], 3.05)


@icon("next")
def _():
    return line([(9.2, 5.2), (16.0, 12), (9.2, 18.8)], 3.05)


@icon("chev")
def _():
    return line([(9.6, 6.8), (14.8, 12), (9.6, 17.2)], SW)


@icon("down")
def _():
    return line([(6.4, 9.4), (12, 15.0), (17.6, 9.4)], 3.05)


@icon("up")
def _():
    return line([(6.4, 14.6), (12, 9.0), (17.6, 14.6)], 3.05)


@icon("bell")
def _():
    dome = unary_union([circle(12, 9.4, 6.0), box(6.0, 9.4, 18.0, 15.0)])
    base = squircle(3.8, 14.2, 20.2, 17.6, 1.7)
    body = smooth(unary_union([dome, base, poly([(6.0, 14.0), (18.0, 14.0), (19.4, 15.6), (4.6, 15.6)], 0)]), 0.8)
    top = line([(12, 2.6), (12, 3.6)], 3.31)
    clap = line([(10.4, 20.2), (13.6, 20.2)], 3.31)
    return unary_union([body, top, clap])


def bookmark_shape():
    p = Polygon([(5.6, 4.6), (5.6, 21.0), (12, 16.6), (18.4, 21.0), (18.4, 4.6)])
    top = squircle(5.6, 2.6, 18.4, 10, 3.0)
    return smooth(unary_union([p.difference(box(0, 0, 24, 6)), top]), 0.9)


@icon("bookmark")
def _():
    return bookmark_shape()


@icon("bookmark_off")
def _():
    return outline(bookmark_shape(), 2.0)


@icon("box")
def _():
    # sampel: tutup selebar penuh + badan lebih sempit dengan celah pegangan
    lid = squircle(2.625, 2.625, 21.375, 7.3, 2.0)
    body = squircle(4.4, 8.35, 19.6, 21.375, TR)
    slot = squircle(9.75, 10.1, 14.25, 11.5, 0.7)
    return unary_union([lid, knock(body, slot)])


@icon("bright")
def _():
    parts = [circle(12, 12, 4.3)]
    for k in range(8):
        a = math.radians(45 * k)
        parts.append(line([(12 + 7.1 * math.cos(a), 12 + 7.1 * math.sin(a)),
                           (12 + 9.0 * math.cos(a), 12 + 9.0 * math.sin(a))], 2.8))
    return unary_union(parts)


@icon("bulb")
def _():
    glass = smooth(unary_union([circle(12, 9.2, 6.6),
                                poly([(7.4, 12.6), (16.6, 12.6), (15.4, 16.8), (8.6, 16.8)], 0.4)]), 0.6)
    glass = knock(glass, line([(9.6, 8.6), (9.6 + 0.01, 8.6)], 2.42))
    base = line([(9.6, 19.6), (14.4, 19.6)], 3.05)
    return unary_union([glass, base])


@icon("chat")
def _():
    b = squircle(2.8, 3.4, 21.2, 16.8, 4.6)
    tail = poly([(7.2, 15.0), (7.4, 20.8), (13.2, 15.0)], 0.7)
    shape = smooth(unary_union([b, tail]), 0.5)
    return knock(shape, line([(7.6, 8.4), (16.4, 8.4)], KW), line([(7.6, 11.9), (12.6, 11.9)], KW))


@icon("check")
def _():
    return line([(4.8, 12.6), (9.6, 17.2), (19.2, 7.2)], 3.18)


@icon("check_ok")
def _():
    return knock(circle(12, 12, 9.6), line(check_pts(12, 12, 1.05), 1.95))


@icon("clock")
def _():
    return knock(circle(12, 12, 9.6), line([(12, 6.9), (12, 12.4), (15.4, 14.4)], 2.48))


@icon("close")
def _():
    return unary_union([line([(6.2, 6.2), (17.8, 17.8)], 3.05), line([(17.8, 6.2), (6.2, 17.8)], 3.05)])


@icon("plus")
def _():
    return plus(12, 12, 7.2, 2.4)


def cloud_shape():
    # tiga gelembung yang menyinggung garis dasar yang sama (y = 19.2) + badan di antaranya
    c = unary_union([circle(7.6, 14.8, 4.4), circle(12.6, 11.0, 5.9), circle(17.0, 15.0, 4.2),
                     box(7.6, 13.0, 17.0, 19.2)])
    return c.buffer(1.3, quad_segs=Q).buffer(-1.3, quad_segs=Q)   # lekuk antar-gelembung mulus


@icon("cloud")
def _():
    return cloud_shape()


@icon("offline")
def _():
    slash = line([(3.4, 3.4), (20.6, 20.6)], 2.8)
    return gap(cloud_shape(), slash, 1.2)


@icon("compress")
def _():
    a = unary_union([line([(19.6, 4.4), (14.0, 10.0)]), line([(14.0, 5.6), (14.0, 10.0), (18.4, 10.0)])])
    b = unary_union([line([(4.4, 19.6), (10.0, 14.0)]), line([(10.0, 18.4), (10.0, 14.0), (5.6, 14.0)])])
    return unary_union([a, b])


@icon("contrast")
def _():
    c = circle(12, 12, 9.6)
    inner = circle(12, 12, 7.5).intersection(box(12, 0, 24, 24))
    return knock(c, inner)


@icon("crop")
def _():
    return unary_union([line([(7.0, 2.8), (7.0, 17.0), (21.2, 17.0)]), line([(2.8, 7.0), (17.0, 7.0), (17.0, 21.2)])])


@icon("cut")
def _():
    blades = unary_union([line([(8.6, 8.8), (20.2, 17.6)]), line([(8.6, 15.2), (20.2, 6.4)])])
    rings = unary_union([ring(6.0, 6.8, 3.3, 2.0), ring(6.0, 17.2, 3.3, 2.0)])
    return unary_union([blades.difference(unary_union([circle(6.0, 6.8, 3.3), circle(6.0, 17.2, 3.3)]).buffer(0.0)), rings])


def page(fold=True):
    x0, y0, x1, y1 = 4.2, 2.4, 19.8, 21.6
    p = squircle(x0, y0, x1, y1, 3.4)
    if not fold:
        return p
    # sudut kanan atas terlipat: dipotong diagonal TEPAT di sisi miring lipatan; lipatan = segitiga di bawahnya
    k = 6.4
    ax, ay = x1 - k, y0          # ujung atas garis potong
    bx, by = x1, y0 + k          # ujung kanan garis potong
    corner = Polygon([(ax, ay - 5), (bx + 5, ay - 5), (bx + 5, by), (bx, by), (ax, ay)])
    body = smooth(p.difference(corner), 0.7)
    flap = Polygon([(ax, ay), (bx, by), (ax, by)]).intersection(body)
    flap = flap.buffer(-0.5, quad_segs=Q).buffer(0.5, quad_segs=Q)
    return body, flap


@icon("doc")
def _():
    body, flap = page()
    body = knock(body, line([(8.0, 12.4), (15.6, 12.4)], KW), line([(8.0, 16.2), (12.6, 16.2)], KW))
    return unary_union([body.difference(flap.buffer(GAP)), flap])


@icon("pdf")
def _():
    body, flap = page()
    band = squircle(6.8, 11.4, 17.2, 17.4, 2.0)
    body = knock(body, band)
    return unary_union([body.difference(flap.buffer(GAP)), flap, line([(9.6, 14.4), (14.4, 14.4)], 2.16)])


@icon("download")
def _():
    tray = squircle(3.2, 14.6, 20.8, 20.8, 2.8)
    arrow = unary_union([line([(12, 3.2), (12, 11.6)]), line([(8.2, 8.0), (12, 11.8), (15.8, 8.0)])])
    return gap(tray, arrow, 1.3)


@icon("upload")
def _():
    tray = squircle(3.2, 14.6, 20.8, 20.8, 2.8)
    arrow = unary_union([line([(12, 4.0), (12, 12.4)]), line([(8.2, 7.6), (12, 3.8), (15.8, 7.6)])])
    return gap(tray, arrow, 1.3)


@icon("share")
def _():
    tray = squircle(3.6, 9.4, 20.4, 21.2, 3.6)
    tray = tray.difference(box(9.0, 0, 15.0, 11.2).buffer(0.0))
    tray = smooth(tray, 0.7)
    arrow = unary_union([line([(12, 3.0), (12, 14.0)]), line([(8.4, 6.6), (12, 3.0), (15.6, 6.6)])])
    return gap(tray, arrow, 1.2)


def pencil(x0, y0, x1, y1, w):
    """Pensil diagonal: badan (kapsul) + mata pensil runcing-bulat."""
    dx, dy = x1 - x0, y1 - y0
    L = math.hypot(dx, dy)
    ux, uy = dx / L, dy / L
    nx, ny = -uy, ux
    h = w / 2
    tipL = w * 1.05
    sx, sy = x0 + ux * tipL, y0 + uy * tipL
    body = Polygon([(sx + nx * h, sy + ny * h), (x1 + nx * h, y1 + ny * h), (x1 - nx * h, y1 - ny * h), (sx - nx * h, sy - ny * h)])
    tip = Polygon([(sx + nx * h, sy + ny * h), (x0, y0), (sx - nx * h, sy - ny * h)])
    p = unary_union([body, tip])
    p = p.buffer(-0.55, quad_segs=Q).buffer(0.55, quad_segs=Q)
    # ujung belakang dibulatkan
    end = Point(x1, y1).buffer(h, quad_segs=Q)
    p = unary_union([p, end])
    # potongan penghapus
    cut = LineString([(x1 - ux * w * 1.25 + nx * w, y1 - uy * w * 1.25 + ny * w),
                      (x1 - ux * w * 1.25 - nx * w, y1 - uy * w * 1.25 - ny * w)]).buffer(0.62, cap_style="flat")
    return p.difference(cut)


@icon("edit")
def _():
    return pencil(4.0, 20.0, 18.6, 5.4, 5.0)


@icon("note")
def _():
    pad = squircle(3.0, 4.4, 18.6, 20.6, 3.8)
    pad = knock(pad, line([(6.8, 15.8), (10.4, 15.8)], KW))
    pen = pencil(11.0, 14.2, 20.4, 4.8, 3.6)
    return gap(pad, pen, 1.25)


@icon("flash")
def _():
    return poly([(14.0, 2.0), (4.6, 13.8), (11.0, 13.8), (10.0, 22.0), (19.4, 10.0), (13.0, 10.0)], 1.0)


@icon("flip")
def _():
    l = poly([(10.0, 5.0), (10.0, 18.8), (3.2, 18.8)], 1.0)
    r = poly([(14.0, 5.0), (14.0, 18.8), (20.8, 18.8)], 1.0)
    dashes = unary_union([line([(12, y), (12, y + 1.2)], 2.29) for y in (2.8, 7.4, 12.0, 16.6, 20.6)])
    return unary_union([l, r, dashes])


@icon("folder")
def _():
    # sampel: bagian belakang dengan tab (atas) dan badan depan selebar penuh, dipisah celah lurus
    back = squircle(2.625, 3.25, 21.375, 12.0, 2.4)
    tab_cut = poly([(11.2, 3.0), (24.0, 3.0), (24.0, 5.6), (13.4, 5.6)], 0.8)
    back = smooth(back.difference(tab_cut), 0.5)
    front = squircle(2.625, 10.7, 21.375, 20.75, TR)
    return unary_union([back.difference(box(0, 9.63, 24, 10.7)), front])


@icon("format_clear")
def _():
    t = unary_union([line([(4.6, 4.8), (16.4, 4.8)]), line([(10.5, 4.8), (8.6, 19.2)])])
    x = unary_union([line([(14.6, 14.2), (19.6, 19.2)], 2.67), line([(19.6, 14.2), (14.6, 19.2)], 2.67)])
    return unary_union([t, x])


@icon("full")
def _():
    s = []
    for (ax, ay, bx, by, cx, cy) in [(3.2, 8.6, 3.2, 3.2, 8.6, 3.2), (15.4, 3.2, 20.8, 3.2, 20.8, 8.6),
                                     (20.8, 15.4, 20.8, 20.8, 15.4, 20.8), (8.6, 20.8, 3.2, 20.8, 3.2, 15.4)]:
        s.append(line([(ax, ay), (bx, by), (cx, cy)]))
    return unary_union(s)


def split(shape, cuts, w=1.6, r=0.5):
    g = shape
    for c in cuts:
        g = g.difference(c.buffer(w / 2, cap_style="flat"))
    return smooth(g, r)


@icon("grid")
def _():
    sq = squircle(2.6, 2.6, 21.4, 21.4, 5.6)
    return split(sq, [LineString([(12, -1), (12, 25)]), LineString([(-1, 12), (25, 12)])])


@icon("sheet")
def _():
    sq = squircle(2.6, 3.2, 21.4, 20.8, 4.6)
    return split(sq, [LineString([(-1, 8.6), (25, 8.6)]), LineString([(9.0, 8.6), (9.0, 25)]),
                      LineString([(-1, 14.7), (25, 14.7)])])


@icon("tools")
def _():
    return unary_union([squircle(3.0, 3.0, 10.8, 10.8, 3.0), squircle(13.2, 3.0, 21.0, 10.8, 3.0),
                        squircle(3.0, 13.2, 10.8, 21.0, 3.0), circle(17.1, 17.1, 3.95)])


@icon("heart")
def _():
    h = unary_union([circle(7.7, 9.0, 4.9), circle(16.3, 9.0, 4.9),
                     Polygon([(3.25, 11.0), (12, 20.8), (20.75, 11.0), (12, 7.0)])])
    return smooth(h, 0.8)


@icon("home")
def _():
    h = poly([(2.8, 10.6), (12, 3.0), (21.2, 10.6), (21.2, 21.0), (2.8, 21.0)], 2.0)
    return knock(h, line([(12, 15.4), (12, 24)], 3.4))


def picture(x0, y0, x1, y1, r):
    sq = squircle(x0, y0, x1, y1, r)
    sun = circle(x0 + (x1 - x0) * 0.3, y0 + (y1 - y0) * 0.32, 1.85)
    w = x1 - x0; h = y1 - y0
    mtn = line([(x0 - 1, y0 + h * 0.93), (x0 + w * 0.34, y0 + h * 0.56), (x0 + w * 0.58, y0 + h * 0.8),
                (x0 + w * 0.76, y0 + h * 0.62), (x1 + 1, y0 + h * 0.96)], KW)
    return knock(sq, sun, mtn)


@icon("image")
def _():
    return picture(2.6, 3.6, 21.4, 20.4, 4.6)


@icon("img_add")
def _():
    pic = picture(2.6, 5.2, 18.4, 21.0, 4.0)
    p = plus(18.8, 5.2, 3.0, 2.2)
    return gap(pic, p, 1.3)


@icon("line_break")
def _():
    path = [(19.2, 4.4), (19.2, 10.2)] + arc(16.2, 10.2, 3.0, 0, 90, 12)[1:] + [(5.4, 13.2)]
    return unary_union([line(path), line([(9.4, 9.2), (5.4, 13.2), (9.4, 17.2)])])


@icon("lock")
def _():
    body = squircle(4.0, 9.8, 20.0, 21.4, 3.8)
    body = knock(body, line([(12, 14.2), (12, 16.8)], 2.93))
    shackle = line([(7.9, 10.2), (7.9, 7.2)] + arc(12, 7.2, 4.1, 180, 360, 24)[1:] + [(16.1, 10.2)], 2.3)
    return unary_union([body, shackle.difference(body.buffer(0.0))])


@icon("mail")
def _():
    env = squircle(2.4, 4.6, 21.6, 19.4, 4.0)
    return knock(env, line([(2.0, 7.8), (12, 13.6), (22, 7.8)], KW))


@icon("merge")
def _():
    return unary_union([line([(6.0, 3.6), (6.0, 7.6), (12, 12.8), (12, 20.2)]),
                        line([(18.0, 3.6), (18.0, 7.6), (12, 12.8)]),
                        line([(8.4, 16.8), (12, 20.4), (15.6, 16.8)])])


def crescent(cx, cy, r, ox, oy, rr):
    return smooth(circle(cx, cy, r).difference(circle(cx + ox, cy + oy, rr)), 0.4)


@icon("moon")
def _():
    return crescent(11.4, 12.6, 9.0, 5.2, -4.6, 7.2)


@icon("moon_sun")
def _():
    m = crescent(10.4, 13.6, 8.0, 4.8, -4.4, 6.4)
    s = spark(18.2, 5.6, 3.8)
    return gap(m, s, 1.0)


@icon("more")
def _():
    return unary_union([circle(5.2, 12, 2.1), circle(12, 12, 2.1), circle(18.8, 12, 2.1)])


@icon("pause")
def _():
    return unary_union([squircle(5.6, 4.0, 10.2, 20.0, 2.2), squircle(13.8, 4.0, 18.4, 20.0, 2.2)])


@icon("play")
def _():
    return poly([(6.6, 3.8), (6.6, 20.2), (20.4, 12.0)], 1.9)


@icon("stop")
def _():
    return squircle(5.0, 5.0, 19.0, 19.0, 4.4)


@icon("phone")
def _():
    p = squircle(5.6, 2.2, 18.4, 21.8, 3.8)
    return knock(p, line([(10.2, 18.2), (13.8, 18.2)], KW))


@icon("print")
def _():
    top = squircle(6.8, 2.6, 17.2, 7.0, 1.6)
    body = squircle(2.6, 8.2, 21.4, 17.4, 3.4)
    body = knock(body, circle(17.4, 11.6, 1.15))
    paper = squircle(6.8, 13.4, 17.2, 21.4, 1.8)
    return unary_union([top, gap(body, paper, 1.2)])


@icon("qr")
def _():
    def finder(x, y):
        return knock(squircle(x, y, x + 7.6, y + 7.6, 2.6), squircle(x + 2.6, y + 2.6, x + 5.0, y + 5.0, 0.9))
    dots = [squircle(x, y, x + 2.8, y + 2.8, 0.9) for (x, y) in [(13.6, 13.6), (18.2, 13.6), (13.6, 18.2), (18.2, 18.2)]]
    return unary_union([finder(2.8, 2.8), finder(13.6, 2.8), finder(2.8, 13.6)] + dots)


@icon("redo")
def _():
    path = arc(13.0, 16.6, 7.6, 180, 270, 24) + [(17.6, 9.0)]
    path = [(5.4, 18.8)] + path
    return unary_union([line(path), line([(14.4, 5.6), (17.8, 9.0), (14.4, 12.4)])])


@icon("undo")
def _():
    return affinity.scale(I["redo"](), -1, 1, origin=(12, 12))


@icon("rotate")
def _():
    a1 = 250
    pts = arc(12, 12.4, 7.8, -40, a1, 60)
    t = math.radians(a1)
    tx, ty = -math.sin(t), math.cos(t)          # arah gerak (searah jarum jam)
    nx, ny = math.cos(t), math.sin(t)
    tip = (pts[-1][0] + tx * 0.6, pts[-1][1] + ty * 0.6)
    head = [(tip[0] - tx * 3.4 + nx * 3.2, tip[1] - ty * 3.4 + ny * 3.2), tip,
            (tip[0] - tx * 3.4 - nx * 3.2, tip[1] - ty * 3.4 - ny * 3.2)]
    return unary_union([line(pts), line(head)])


@icon("scan")
def _():
    s = []
    for (ax, ay, bx, by, cx, cy) in [(3.0, 8.0, 3.0, 3.0, 8.0, 3.0), (16.0, 3.0, 21.0, 3.0, 21.0, 8.0),
                                     (21.0, 16.0, 21.0, 21.0, 16.0, 21.0), (8.0, 21.0, 3.0, 21.0, 3.0, 16.0)]:
        s.append(line([(ax, ay), (bx, by), (cx, cy)]))
    s.append(squircle(5.6, 10.4, 18.4, 13.6, 1.6))
    return unary_union(s)


@icon("search")
def _():
    lens = ring(10.6, 10.6, 7.6, 2.5)
    handle = line([(16.4, 16.4), (20.2, 20.2)], 3.4)
    return unary_union([lens, handle])


def magnifier(sym):
    lens = knock(circle(10.4, 10.4, 7.9), sym)
    handle = line([(17.0, 17.0), (20.4, 20.4)], 3.4)
    return gap(lens, handle, 1.0)


# zoom = lensa CINCIN yang sama dengan ikon cari dari sampel (bukan lensa penuh), tanda +/− di dalam lubangnya
_LX, _LY = 9.72, 9.75     # pusat lensa ikon cari sampel (lubang r ≈ 4,14)


@icon("zoom_in")
def _():
    return unary_union([I["search"](), plus(_LX, _LY, 2.0, 2.2)])


@icon("zoom_out")
def _():
    return unary_union([I["search"](), line([(_LX - 2.0, _LY), (_LX + 2.0, _LY)], 2.2)])


def shield_shape():
    pts = [(12, 2.2), (20.2, 5.4), (20.2, 11.0)] + \
          [(12 + 8.2 * math.cos(math.radians(a)), 11.0 + 10.6 * math.sin(math.radians(a))) for a in range(5, 90, 5)] + \
          [(12, 21.8)] + \
          [(12 - 8.2 * math.cos(math.radians(a)), 11.0 + 10.6 * math.sin(math.radians(a))) for a in range(85, 0, -5)] + \
          [(3.8, 11.0), (3.8, 5.4)]
    return poly(pts, 1.4)


@icon("shield")
def _():
    return knock(shield_shape(), line(check_pts(12, 11.6, 0.95), 1.95))


@icon("verified")
def _():
    b = smooth(star(12, 12, 10.2, 8.4, 12, -90), 0.9)
    return knock(b, line(check_pts(12, 12, 0.95), 1.95))


@icon("sign")
def _():
    sq = [(3.6, 15.8), (5.2, 12.0), (6.8, 9.2), (8.2, 8.6), (8.6, 10.2), (7.6, 15.0), (8.8, 15.6), (10.8, 12.4),
          (12.4, 11.8), (13.2, 13.8), (14.4, 14.4), (15.6, 12.8), (17.2, 13.4), (18.6, 13.0), (20.4, 11.8)]
    return unary_union([line(sq, 2.0), line([(3.6, 19.8), (20.4, 19.8)], 3.05)])


@icon("spark")
def _():
    return gap(spark(10.4, 13.2, 8.2), spark(18.6, 5.2, 3.4), 1.0)


@icon("star")
def _():
    return smooth(star(12, 12.8, 10.2, 4.6, 5, -90), 1.2)


@icon("trash")
def _():
    lid = line([(3.6, 6.2), (20.4, 6.2)], 3.05)
    handle = squircle(8.8, 2.2, 15.2, 5.8, 1.4)
    body = poly([(5.2, 8.6), (18.8, 8.6), (17.6, 21.4), (6.4, 21.4)], 2.0)
    body = knock(body, line([(10.0, 12.0), (10.0, 17.6)], KW), line([(14.0, 12.0), (14.0, 17.6)], KW))
    return unary_union([gap(lid, handle, 0.0), body]) if False else unary_union([lid, handle, body])


@icon("tune")
def _():
    k1 = ring(16.6, 7.6, 3.1, 2.1)
    k2 = ring(7.4, 16.4, 3.1, 2.1)
    l1 = line([(3.6, 7.6), (13.1, 7.6)])
    l2 = line([(10.9, 16.4), (20.4, 16.4)])
    return unary_union([k1, k2, l1, l2])


@icon("user")
def _():
    head = circle(12, 7.4, 4.4)
    body = squircle(3.8, 13.6, 20.2, 21.6, 5.2)
    body = body.intersection(box(0, 13.6, 24, 21.6))
    return unary_union([head, smooth(body, 0.4)])


@icon("volume")
def _():
    spk = poly([(2.8, 8.8), (7.4, 8.8), (12.6, 4.0), (12.6, 20.0), (7.4, 15.2), (2.8, 15.2)], 1.4)
    w1 = line(arc(13.4, 12, 3.6, -48, 48, 20), 2.2)
    w2 = line(arc(13.4, 12, 7.0, -50, 50, 30), 2.2)
    return unary_union([spk, w1, w2])


@icon("wand")
def _():
    stick = line([(4.0, 20.0), (14.6, 9.4)], 3.4)
    s1 = spark(17.8, 6.2, 4.0)
    s2 = spark(8.6, 4.6, 2.2)
    s3 = spark(20.0, 15.0, 2.0)
    return unary_union([gap(stick, s1, 1.1), s2, s3])


# ------------------------------------------------------------------ bentuk dari sampel
def _from_path(d):
    """Path M/L/Z (even-odd) → geometri shapely."""
    import re as _re
    g = None
    for ring in _re.findall(r"M([^MZ]*)Z", d):
        nums = [float(v) for v in _re.findall(r"-?[0-9.]+", ring)]
        p = Polygon(list(zip(nums[0::2], nums[1::2]))).buffer(0)
        g = p if g is None else g.symmetric_difference(p)
    return g


def _sample():
    try:
        from sample24 import SAMPLE
    except ImportError:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from sample24 import SAMPLE
    for n, d in SAMPLE.items():
        I[n] = (lambda dd: (lambda: _from_path(dd)))(d)


_sample()


# ------------------------------------------------------------------ keluaran
def rings_of(g):
    polys = [g] if isinstance(g, Polygon) else list(getattr(g, "geoms", []))
    out = []
    for p in polys:
        if p.is_empty:
            continue
        p = p.simplify(0.045)
        out.append(list(p.exterior.coords))
        out.extend(list(r.coords) for r in p.interiors)
    return out


def path_data(g):
    parts = []
    for r in rings_of(g):
        r = r[:-1]
        if len(r) < 3:
            continue
        s = "M%.2f %.2f" % r[0] + "".join("L%.2f %.2f" % p for p in r[1:]) + "Z"
        parts.append(s)
    return "".join(parts)


HEADER = ('<?xml version="1.0" encoding="utf-8"?>\n'
          '<!-- Depup Solid: ikon solid membulat 24dp — bentuk penuh bersudut squircle, detail berupa lubang, ujung garis\n'
          '     bulat, celah antar-bagian. Dibuat oleh tools/icons/gen.py; warna diatur lewat tint di kode. -->\n'
          '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n')


def vd(g):
    return HEADER + ('    <path android:fillColor="#FF111111" android:fillType="evenOdd"\n'
                     '        android:pathData="%s" />\n</vector>\n' % path_data(g))


def svg(g, color="#0A84FF", size=96):
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="%d" height="%d">'
            '<path fill="%s" fill-rule="evenodd" d="%s"/></svg>' % (size, size, color, path_data(g)))


if __name__ == "__main__":
    # python3 tools/icons/gen.py app/src/main/res/drawable [--svg DIR_PRATINJAU] [nama ...]
    args = sys.argv[1:]
    out = args.pop(0)
    svg_dir = None
    if "--svg" in args:
        i = args.index("--svg"); svg_dir = args[i + 1]; del args[i:i + 2]
    only = args
    os.makedirs(out, exist_ok=True)
    if svg_dir:
        os.makedirs(svg_dir, exist_ok=True)
    for n, f in I.items():
        if only and n not in only:
            continue
        g = f()
        assert g.is_valid, n
        open(os.path.join(out, "ic_%s.xml" % n), "w").write(vd(g))
        if svg_dir:   # .svg TIDAK boleh masuk res/drawable (build Android gagal)
            open(os.path.join(svg_dir, "ic_%s.svg" % n), "w").write(svg(g))
    print(len(only) or len(I), "ikon")
