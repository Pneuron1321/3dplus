"""Сетка из генератора 3D (Hunyuan3D-2 по крупному плану) → данные для src/zver-golova.js (src/zver-setka.js).

python3 tools/zver/hy-setka.py S вход.glb src/zver-setka.js [треугольников=60000]

1. Облегчение до N треугольников (fast_simplification), мелкие обрывки выкинуты.
2. Совмещение с рисунком: поворот вокруг вертикали (и наклон) подбирается так, чтобы силуэт сетки совпал с маской
   головы на рисунке (IoU). Генератор ставит голову анфас, а рисунок повёрнут «в сторону».
3. Кожа — сам рисунок (текстура): вершинам, видимым из камеры рисунка (z-буфер), — их место на рисунке;
   невидимым — место ближайшей видимой и тень к затылку.
"""
import sys, os, json, base64
import numpy as np
import trimesh, fast_simplification as fs
from PIL import Image, ImageDraw
from scipy import ndimage as nd
from scipy.spatial import cKDTree
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from maska import maska
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории

S, IN, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
N = int(sys.argv[4]) if len(sys.argv) > 4 else 60000
m = trimesh.load(IN, force='mesh')
m.merge_vertices(); m.update_faces(m.unique_faces()); m.update_faces(m.nondegenerate_faces()); m.remove_unreferenced_vertices()   # у генератора треугольники задвоены
v, f = fs.simplify(np.ascontiguousarray(m.vertices, dtype=np.float32), np.ascontiguousarray(m.faces, dtype=np.int32), target_reduction=max(0, 1 - N / len(m.faces)))
f = f.astype(np.int64)
# обрывки: связные куски меньше 150 треугольников — прочь
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components
e = np.concatenate([f[:, [0, 1]], f[:, [1, 2]], f[:, [2, 0]]])
g = coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(len(v), len(v)))
nc, lab = connected_components(g, directed=False)
fl = lab[f[:, 0]]; cnt = np.bincount(fl, minlength=nc)
f = f[cnt[fl] >= 150]
used = np.unique(f); remap = -np.ones(len(v), int); remap[used] = np.arange(len(used)); v = v[used]; f = remap[f]
print('сетка', len(v), 'вершин', len(f), 'треугольников, кусков было', nc)

im = Image.open(MAKET + '/ref-zver-2.jpg').convert('RGB'); W, H = im.size
D = np.load(S + '/zv2/glubina.npy'); M = maska(D, W, H, 5.0)
ys, xs = np.where(M); mb = (xs.min(), xs.max(), ys.min(), ys.max())

def rot(v, yaw, pitch):
    cy, sy, cp, sp = np.cos(yaw), np.sin(yaw), np.cos(pitch), np.sin(pitch)
    x, y, z = v[:, 0], v[:, 1], v[:, 2]
    x, z = x * cy + z * sy, -x * sy + z * cy
    y, z = y * cp - z * sp, y * sp + z * cp
    return np.stack([x, y, z], 1)

def kamera(r):
    """экранные точки рисунка для повёрнутой сетки: масштаб и сдвиг — по рамке силуэта (высота и ширина отдельно)"""
    x0, x1, y0, y1 = r[:, 0].min(), r[:, 0].max(), r[:, 1].min(), r[:, 1].max()
    kx = (mb[1] - mb[0]) / (x1 - x0); ky = (mb[3] - mb[2]) / (y1 - y0); k = (kx + ky) / 2
    px = mb[0] + (r[:, 0] - x0) * kx; py = mb[3] - (r[:, 1] - y0) * ky
    return px, py, k

def silhuet(px, py, sc=0.25):
    img = Image.new('L', (int(W * sc), int(H * sc)), 0); d = ImageDraw.Draw(img)
    P = np.stack([px * sc, py * sc], 1)
    for t in f[::2]: d.polygon([tuple(P[t[0]]), tuple(P[t[1]]), tuple(P[t[2]])], fill=255)
    return np.asarray(img) > 0

Ms = np.asarray(Image.fromarray(M.astype(np.uint8) * 255).resize((int(W * 0.25), int(H * 0.25)))) > 127
best = None
for yaw in np.radians(np.arange(-50, 51, 5)):
    for pitch in np.radians([-15, -5, 5, 15]):
        px, py, _ = kamera(rot(v, yaw, pitch)); s = silhuet(px, py)
        iou = (s & Ms).sum() / (s | Ms).sum()
        if best is None or iou > best[0]: best = (iou, yaw, pitch)
for yaw in best[1] + np.radians(np.arange(-4, 5, 2)):
    for pitch in best[2] + np.radians(np.arange(-4, 5, 2)):
        px, py, _ = kamera(rot(v, yaw, pitch)); s = silhuet(px, py)
        iou = (s & Ms).sum() / (s | Ms).sum()
        if iou > best[0]: best = (iou, yaw, pitch)
IOU, YAW, PITCH = best
print('совмещение: IoU %.3f, поворот %.1f°, наклон %.1f°' % (IOU, np.degrees(YAW), np.degrees(PITCH)))
r = rot(v, YAW, PITCH); px, py, K = kamera(r)

# видимость из камеры рисунка: z-буфер по треугольникам (ближе к зрителю — больше z)
ZB = np.full((H, W), -1e9)
tri = f[np.argsort(r[f].mean(1)[:, 2])]                          # от дальних к ближним
idimg = Image.new('I', (W, H), -1); di = ImageDraw.Draw(idimg)
P = np.stack([px, py], 1)
for i, t in enumerate(tri): di.polygon([tuple(P[t[0]]), tuple(P[t[1]]), tuple(P[t[2]])], fill=i)
idm = np.asarray(idimg)
xi, yi = np.clip(px.round().astype(int), 0, W - 1), np.clip(py.round().astype(int), 0, H - 1)
vid = np.zeros(len(v), bool)
for dx in (-1, 0, 1):
    for dy in (-1, 0, 1):
        t = idm[np.clip(yi + dy, 0, H - 1), np.clip(xi + dx, 0, W - 1)]
        ok = t >= 0
        vid[ok] |= (tri[t[ok]] == np.arange(len(v))[ok, None]).any(1)
inm = M[yi, xi]
vid &= inm
# невидимые вершины берут место на рисунке у ближайших видимых (растяжка кожи), к затылку темнее
uvx, uvy = px.copy(), py.copy()
tr = cKDTree(r[vid]); dd, ii = tr.query(r[~vid], k=1)
uvx[~vid] = px[vid][ii]; uvy[~vid] = py[vid][ii]
ten = np.ones(len(v))
zn = (r[~vid][:, 2] - r[:, 2].min()) / np.ptp(r[:, 2])
ten[~vid] = 0.62 + 0.33 * zn
print('видимых вершин', vid.sum(), 'из', len(v))
# текстура — рамка головы на рисунке
X0, X1, Y0, Y1 = max(0, mb[0] - 8), min(W, mb[1] + 9), max(0, mb[2] - 8), min(H, mb[3] + 9)
tex = np.asarray(im.crop((X0, Y0, X1, Y1)))[::-1].copy()          # строки снизу вверх — как ждёт DataTexture
uv = np.stack([(uvx - X0) / (X1 - X0), 1 - (uvy - Y0) / (Y1 - Y0)], 1)
# в единицы головы: высота силуэта рисунка — как у головы Плюса-зверя (≈ 12.5), подбородок на y = −0.3
yb = r[:, 1].min(); HGT = 12.5 / (r[:, 1].max() - yb)
q = np.stack([r[:, 0], r[:, 1] - yb, r[:, 2]], 1) * HGT
q[:, 1] -= 0.3; q[:, 0] -= (q[:, 0].max() + q[:, 0].min()) / 2; q[:, 2] -= q[:, 2].max() - 5.6
lo, hi = q.min(0), q.max(0)
qq = np.round((q - lo) / (hi - lo) * 65535).astype(np.uint16)
cc = np.round(np.clip(ten, 0, 1) * 255).astype(np.uint8)
uu = np.round(np.clip(uv, 0, 1) * 65535).astype(np.uint16)
d = {'n': int(len(v)), 'lo': lo.tolist(), 'hi': hi.tolist(), 'pos': base64.b64encode(qq.tobytes()).decode(),
     'ten': base64.b64encode(cc.tobytes()).decode(), 'uv': base64.b64encode(uu.tobytes()).decode(),
     'tex': base64.b64encode(tex.tobytes()).decode(), 'tw': int(tex.shape[1]), 'th': int(tex.shape[0]), 'idx': base64.b64encode(f.astype(np.uint32).tobytes()).decode(),
     'yaw': float(YAW), 'pitch': float(PITCH), 'iou': float(IOU)}
js = ('/* Голова зверя: сетка генератора 3D (Hunyuan3D-2) по крупному плану Александра, облегчённая и совмещённая с\n'
      '   рисунком; цвет вершин — сам рисунок из его камеры. Для src/zver-golova.js (opts.zver3). */\n'
      '(function (g) { g.PixelMechZverSetka = ' + json.dumps(d) + '; })(typeof window !== "undefined" ? window : globalThis);\n')
open(OUT, 'w').write(js); print(len(js) // 1024, 'КБ')
# проверка: видимые вершины поверх рисунка
pv = (np.asarray(im) * 0.5).astype(np.uint8); pv[yi[vid], xi[vid]] = (255, 255, 0)
Image.fromarray(pv).save(S + '/hy/sovmeshchenie.png')
