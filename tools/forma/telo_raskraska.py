"""Сетка генератора (Плюс целиком, по виду 3/4) + виды листа тела → раскрашенная литая фигура для pixel-mech.js.

python3 tools/forma/telo_raskraska.py сетка.glb папка_видов/ выход.js [--vysota 26] [--tri 90000] [--global ИМЯ]

Папка видов: vid-v34.png, vid-front.png, vid-back.png, vid-right.png (RGBA, маска — альфа; спереди — с улыбкой, не ртом вниз).
Сетка стоит анфас (лицо к +z). Угол вида 3/4 подбирается по силуэту; каждый вид совмещается с проекцией сетки подгонкой
рамки по IoU силуэтов. Цвет вершины — с вида, куда смотрит её нормаль и где она видна (буфер глубины); 3/4 (по нему
лепил генератор) — с весом. Бока — правый вид листа и 3/4 (левый — зеркально).
Цвет — медиана окна 3×3, затем сглаживание по соседям. Выход: позиции, треугольники, цвет вершины (RGB).
"""
import sys, base64, json
import numpy as np
import trimesh, fast_simplification as fs
from PIL import Image
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components

src, D, out = sys.argv[1:4]
arg = lambda k, d: (sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d)
VYS, TRI, GLOB = float(arg('--vysota', 26)), int(arg('--tri', 60000)), arg('--global', 'PixelMechTeloSetka')
m = trimesh.load(src, force='mesh')
m.merge_vertices(); m.update_faces(m.unique_faces()); m.update_faces(m.nondegenerate_faces()); m.remove_unreferenced_vertices()
# генератор кладёт под ноги тонкую плиту во всю ширину рамки — срезать всё ниже подошв
yy = m.vertices[:, 1]; plita = yy.min() + 0.0125 * np.ptp(yy)
m.update_faces((yy[m.faces] > plita).all(1)); m.remove_unreferenced_vertices()
v, f = fs.simplify(np.ascontiguousarray(m.vertices, dtype=np.float32), np.ascontiguousarray(m.faces, dtype=np.int32),
                   target_reduction=max(0, 1 - TRI / len(m.faces)))
f = f.astype(np.int64)
e = np.concatenate([f[:, [0, 1]], f[:, [1, 2]], f[:, [2, 0]]])
nc, lab = connected_components(coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(len(v), len(v))), directed=False)
cnt = np.bincount(lab[f[:, 0]], minlength=nc); f = f[cnt[lab[f[:, 0]]] >= 60]           # мелкий мусор — прочь
used = np.unique(f); rm = -np.ones(len(v), int); rm[used] = np.arange(len(used)); v = v[used].astype(float); f = rm[f]
n = trimesh.Trimesh(v, f, process=False).vertex_normals
lo, hi = v.min(0), v.max(0)

from scipy import ndimage as nd
def proekt(yaw):
    """Вид с угла yaw (камера на оси, повёрнутой от +z к +x): u — вправо на картинке, dep — к камере, d — взгляд."""
    c, s_ = np.cos(yaw), np.sin(yaw)
    return v[:, 0] * c - v[:, 2] * s_, v[:, 0] * s_ + v[:, 2] * c, np.array([s_, 0, c])
def siluet(px, py, H, W):
    sl = np.zeros((H, W), bool); ix, iy = px.round().astype(int), py.round().astype(int)
    ok = (ix >= 0) & (ix < W) & (iy >= 0) & (iy < H); sl[iy[ok], ix[ok]] = True
    return nd.binary_fill_holes(nd.binary_closing(sl, iterations=3))
def podgon(u, msk):
    """Совмещение проекции с маской вида: рамка, затем подгонка масштабов и сдвига по IoU силуэтов."""
    H, W = msk.shape; ys, xs = np.where(msk)
    p = [xs.min(), xs.max(), ys.min(), ys.max()]
    def kart(p):
        px = p[0] + (u - u.min()) / np.ptp(u) * (p[1] - p[0]); py = p[3] - (v[:, 1] - lo[1]) / (hi[1] - lo[1]) * (p[3] - p[2])
        return px, py
    def iou(p):
        sl = siluet(*kart(p), H, W); return (sl & msk).sum() / max(1, (sl | msk).sum())
    best_ = iou(p)
    for shag in (6, 3, 1):
        for _ in range(4):
            for i in range(4):
                for dd in (-shag, shag):
                    q = list(p); q[i] += dd; val = iou(q)
                    if val > best_: best_, p = val, q
    return kart(p), best_
# виды: (файл, yaw камеры или None — подобрать, зеркало, вес); 3/4 — по нему лепил генератор, ему доверие больше
VIDY = [('v34', None, False, 0.25), ('v34', None, True, 0.25), ('front', 0.0, False, 0.0), ('back', np.pi, False, 0.0),
        ('right', np.pi / 2, False, 0.0), ('right', -np.pi / 2, True, 0.0)]
cv = np.zeros((len(v), 3)); best = np.full(len(v), -2.0)
for imya, yaw, zerk, ves in VIDY:
    a = np.asarray(Image.open(f'{D}/vid-{imya}.png').convert('RGBA')).astype(float)
    msk = a[..., 3] > 128; H, W = msk.shape
    if yaw is None:                                                 # угол 3/4 — по силуэту
        res = []
        for y_ in np.radians(np.arange(-60, 61, 5)):
            u, dep, d = proekt(-y_ if zerk else y_)
            if zerk: u = -u
            (px, py), q = podgon(u, msk); res.append((q, y_))
        q, yaw = max(res); print(imya, 'зеркало' if zerk else '', 'угол', round(np.degrees(yaw)), 'IoU', round(q, 3))
        if zerk: yaw = -yaw
    u, dep, d = proekt(yaw)
    if zerk: u = -u
    (px, py), q = podgon(u, msk); print(imya, round(np.degrees(yaw)), 'IoU', round(q, 3))
    ix, iy = np.clip(px.round().astype(int), 0, W - 1), np.clip(py.round().astype(int), 0, H - 1)
    zb = np.full((H, W), -1e9); np.maximum.at(zb, (iy, ix), dep)
    zb = nd.maximum_filter(zb, size=3)
    tol = (hi - lo).max() * 0.03
    vid = (dep >= zb[iy, ix] - tol) & msk[iy, ix]
    sc = n @ d + ves
    for i in np.where(vid & (sc > best))[0]:
        win = a[max(0, iy[i] - 1):iy[i] + 2, max(0, ix[i] - 1):ix[i] + 2]
        win = win[win[..., 3] > 128]
        if len(win): cv[i] = np.median(win[:, :3], 0); best[i] = sc[i]
# не попавшие ни в один вид — цвет ближайшего соседа по рёбрам
e = np.concatenate([f[:, [0, 1]], f[:, [1, 2]], f[:, [2, 0]]]); e = np.concatenate([e, e[:, ::-1]])
A = coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(len(v), len(v))).tocsr()
est = best > -2
for _ in range(30):
    if est.all(): break
    s = A @ (cv * est[:, None]); c = A @ est.astype(float)
    nov = (~est) & (c > 0); cv[nov] = s[nov] / c[nov, None]; est |= nov
# палитра Плюса (как на листе тела): цвет вершины → ближайший класс; затем большинство соседей — без пятен
PAL = np.array([[236, 236, 232], [178, 180, 182], [112, 114, 118], [46, 46, 50], [246, 206, 30], [222, 34, 34],
                [130, 80, 220], [238, 120, 30], [170, 214, 150]], float)
def lab_(c):                                                        # грубый перцептивный вес: яркость и цветность
    c = c / 255.0; y = c @ [0.3, 0.59, 0.11]; return np.c_[y * 2.2, (c - y[:, None]) * 3.0]
dist = ((lab_(cv)[:, None, :] - lab_(PAL)[None, :, :]) ** 2).sum(2)
kl = dist.argmin(1)
K = len(PAL); c = np.asarray(A.sum(1)).ravel()
for _ in range(3):
    oh = np.eye(K)[kl]; votes = A @ oh + oh * 2
    kl = votes.argmax(1)
# лицо — по разметке листа, а не пятнами с картинки: визор красный с двумя тёмными глазами, лицо жёлтое,
# улыбка по середине (Александр: улыбка, не рот вниз). Зона — вершины головы, смотрящие вперёд.
Y, R, DK = 4, 5, 3
gol = (v[:, 1] > lo[1] + 0.62 * (hi[1] - lo[1])) & (n[:, 2] > 0.2)
yel, red = gol & (kl == Y), gol & (kl == R)
if yel.sum() > 50 and red.sum() > 50:
    rx0, rx1 = np.percentile(v[red, 0], [3, 97]); ry0, ry1 = np.percentile(v[red, 1], [5, 95])
    fx0, fx1 = np.percentile(v[yel, 0], [3, 97]); fy0 = np.percentile(v[yel, 1], 3)
    vz = gol & (v[:, 0] > rx0) & (v[:, 0] < rx1) & (v[:, 1] > ry0) & (v[:, 1] < ry1)
    lz = gol & (v[:, 0] > fx0) & (v[:, 0] < fx1) & (v[:, 1] > fy0) & (v[:, 1] <= ry0)
    kl[vz] = R; kl[lz] = Y
    cx, vw, vh = (rx0 + rx1) / 2, rx1 - rx0, ry1 - ry0
    for gx in (-0.17, 0.17):                                         # глаза за стеклом
        kl[vz & (np.abs(v[:, 0] - cx - gx * vw) < 0.07 * vw) & (np.abs(v[:, 1] - (ry0 + ry1) / 2) < 0.28 * vh)] = DK
    fw, fh = fx1 - fx0, ry0 - fy0
    ux = (v[:, 0] - (fx0 + fx1) / 2) / fw; uy = (v[:, 1] - fy0) / fh  # 0 — подбородок, 1 — низ визора
    t = 0.1
    ulyb = lz & (((np.abs(ux) < 0.2) & (np.abs(uy - 0.38) < t / 2)) |
                 ((np.abs(np.abs(ux) - 0.2) < t / 2) & (uy > 0.38 - t / 2) & (uy < 0.38 + 1.6 * t)))
    kl[ulyb] = DK
    print('лицо: визор', int(vz.sum()), 'лицо', int(lz.sum()), 'улыбка', int(ulyb.sum()))
    LICO = [fx0, fx1, fy0, ry0, float(np.percentile(v[lz, 2], 97))]   # рамка лица и его перед — для улыбки в модели
else: LICO = None
cv = PAL[kl]
print('классы', np.bincount(kl, minlength=K))
# единицы модели: высота VYS, тапочки — на полу, середина — по x и z
k = VYS / (hi[1] - lo[1])
pos = np.stack([(v[:, 0] - (lo[0] + hi[0]) / 2) * k, (v[:, 1] - lo[1]) * k, (v[:, 2] - (lo[2] + hi[2]) / 2) * k], 1)
b64 = lambda x: base64.b64encode(x.tobytes()).decode()
KLASSY = ['WH2', 'LG', 'MG', 'DG3', 'YE', 'RD1', 'PU', 'O2', 'MN1']      # классы → палитра pixel-mech.js
lico = 'null'
if LICO:
    fx0, fx1, fy0, fy1, fz = LICO; c0 = (lo[0] + hi[0]) / 2; cz = (lo[2] + hi[2]) / 2
    lico = '[' + ','.join(f'{x:.4f}' for x in [(fx0 - c0) * k, (fx1 - c0) * k, (fy0 - lo[1]) * k, (fy1 - lo[1]) * k, (fz - cz) * k]) + ']'
js = (f'/* Плюс целиком по листу тела Александра: сетка генератора 3D, цвет — с видов листа (tools/forma/telo_raskraska.py). */\n'
      f'(function (g) {{ g.{GLOB} = {{ pos: "{b64(pos.astype(np.float32))}", idx: "{b64(f.astype(np.uint32))}", '
      f'cls: "{b64(kl.astype(np.uint8))}", klassy: {json.dumps(KLASSY)}, lico: {lico}, nv: {len(pos)} }}; }})'
      f'(typeof window !== "undefined" ? window : globalThis);\n')
open(out, 'w').write(js)
print('вершин', len(pos), 'треугольников', len(f), 'без вида', int((best <= -2).sum()), len(js) // 1024, 'КБ')
