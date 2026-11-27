"""Сетка (GLB из генератора 3D) → кирпичи: вокселизация в сетке шипов + цвет клеток с картинки → данные для Vox.

python3 tools/forma/kirpichi.py вход.glb картинка_сбоку.png выход.js --global ИМЯ [--dlina 8.4] [--kletka 0.4,0.3,0.4]

Для обуви: длина сетки (её самая длинная ось в плане) → ось z модели (носок вперёд), высота → y, ширина → x.
Длина задаётся в единицах модели (шаг шипа 0.8). Клетка — полшипа (0.4 × 0.3 × 0.4): мягкая форма читается.
Вокселизация: плотная выборка точек поверхности → клетки оболочки; замкнутые полости залиты, проём ворота
остаётся открытым. Цвет: картинка сбоку (вырезка с маской), точка клетки проецируется по длине и высоте:
неоновое → подошва, оранжевое → замша, остальное (фиолетовый знак и т. п.) → замша — чужие знаки не переносим.
"""
import sys, json
import numpy as np
import trimesh, fast_simplification as fs
from PIL import Image
from scipy import ndimage as nd

src, kart, out = sys.argv[1:4]
arg = lambda k, d: (sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d)
GLOB, DL = arg('--global', 'PixelMechSapog'), float(arg('--dlina', 8.4))
CX, CY, CZ = [float(v) for v in arg('--kletka', '0.4,0.3,0.4').split(',')]
m = trimesh.load(src, force='mesh')
m.merge_vertices(); m.update_faces(m.unique_faces()); m.update_faces(m.nondegenerate_faces()); m.remove_unreferenced_vertices()
v, f = fs.simplify(np.ascontiguousarray(m.vertices, dtype=np.float32), np.ascontiguousarray(m.faces, dtype=np.int32), target_reduction=0.8)
m = trimesh.Trimesh(v, f, process=False)
# генератор по картинке обуви иногда лепит пару — берём самый крупный связный кусок
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components
e = np.concatenate([f[:, [0, 1]], f[:, [1, 2]], f[:, [2, 0]]])
nc, lab = connected_components(coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(len(v), len(v))), directed=False)
cnt = np.bincount(lab[f[:, 0]], minlength=nc); big = np.argmax(cnt)
m = trimesh.Trimesh(v, f[lab[f[:, 0]] == big], process=False); m.remove_unreferenced_vertices()
print('кусков', nc, 'берём', int(cnt[big]), 'треугольников')
b = m.bounds; ext = b[1] - b[0]
# оси: длина — большая из x/z, высота — y
L = 0 if ext[0] >= ext[2] else 2; Wd = 2 - L
k = DL / ext[L]
P = m.sample(1_500_000)
# носок: на картинке сбоку носок слева (у генератора длинная ось идёт так же) → носок к +z модели
zz = (b[1][L] - P[:, L]) * k                                   # 0 у пятки? проверяется ниже по высоте
yy = (P[:, 1] - b[0][1]) * k
xx = (P[:, Wd] - (b[0][Wd] + b[1][Wd]) / 2) * k
# пятка выше носка: если у z=0 средняя высота меньше, чем у z=max, — развернуть
lo, hi = yy[zz < DL * 0.2].mean(), yy[zz > DL * 0.8].mean()
if lo < hi: zz = DL - zz
# теперь z=0 — носок; модели нужен носок на +z → z = DL − zz
zm = DL - zz
NX = int(np.ceil(np.ptp(xx) / CX)) + 2; NY = int(np.ceil(yy.max() / CY)) + 1; NZ = int(np.ceil(DL / CZ)) + 1
X0 = -NX * CX / 2
gi = np.clip(((xx - X0) / CX).astype(int), 0, NX - 1); gj = np.clip((yy / CY).astype(int), 0, NY - 1); gk = np.clip((zm / CZ).astype(int), 0, NZ - 1)
occ = np.zeros((NX, NY, NZ), bool); occ[gi, gj, gk] = True
occ = nd.binary_closing(occ, iterations=1)
occ = nd.binary_fill_holes(occ)
print('сетка', NX, NY, NZ, 'клеток', int(occ.sum()), 'высота', round(NY * CY, 2), 'ширина', round(NX * CX, 2))
# цвет с картинки сбоку: носок на картинке слева
a = np.asarray(Image.open(kart).convert('RGBA')).astype(float)
msk = a[..., 3] > 128; ys, xs = np.where(msk)
def klass(z, y):
    px = xs.min() + (DL - z) / DL * (xs.max() - xs.min())          # носок (z=DL) — слева
    py = ys.max() - y / (NY * CY) * (ys.max() - ys.min())
    px, py = int(np.clip(px, 0, a.shape[1] - 1)), int(np.clip(py, 0, a.shape[0] - 1))
    win = a[max(0, py - 3):py + 4, max(0, px - 3):px + 4]
    win = win[win[..., 3] > 128]
    if not len(win): return 'S'
    r, g, b2 = np.median(win[:, :3], 0) / 255
    if g > r * 1.15 and g > b2 * 1.5: return 'N'                  # неоновая подошва
    return 'S'                                                     # замша (и чужой знак — тоже замша)
cls = np.full((NX, NY, NZ), '.', dtype='<U1')
for i, j, kk in zip(*np.where(occ)):
    cls[i, j, kk] = klass((kk + 0.5) * CZ, (j + 0.5) * CY)
# протектор: всё ниже 0.35 — подошва
cls[:, :max(1, int(0.35 / CY)), :][occ[:, :max(1, int(0.35 / CY)), :]] = 'N'
d = {'CX': CX, 'CY': CY, 'CZ': CZ, 'X0': X0, 'NX': NX, 'NY': NY, 'NZ': NZ, 'dlina': DL,
     'cells': ''.join(cls.transpose(2, 1, 0).ravel())}
js = (f'/* Сапог по картинке Александра: сетка генератора 3D → кирпичи полшипа (tools/forma/kirpichi.py). */\n'
      f'(function (g) {{ g.{GLOB} = ' + json.dumps(d) + '; })(typeof window !== "undefined" ? window : globalThis);\n')
open(out, 'w').write(js)
u, c = np.unique(cls[occ], return_counts=True); print('классы', dict(zip(u, c)), len(js) // 1024, 'КБ')

# --setka N: та же сетка гладкой поверхностью (литая деталь), облегчённая до N треугольников, в координатах модели;
# цвет вершин — класс с картинки (неон/замша). Пишется в тот же файл (поля pos, idx, kls) — сборка берёт её вместо кирпичей.
if '--setka' in sys.argv:
    import base64
    N = int(arg('--setka', 16000))
    v2, f2 = fs.simplify(np.ascontiguousarray(m.vertices, dtype=np.float32), np.ascontiguousarray(m.faces, dtype=np.int32),
                         target_reduction=max(0, 1 - N / len(m.faces)))
    zz2 = (b[1][L] - v2[:, L]) * k
    if lo < hi: zz2 = DL - zz2
    pos = np.stack([(v2[:, Wd] - (b[0][Wd] + b[1][Wd]) / 2) * k, (v2[:, 1] - b[0][1]) * k, zz2], 1)   # z: 0 — носок
    kls = np.array([1 if klass(DL - z, y) == 'N' or y < 0.35 else 0 for z, y in zip(pos[:, 2], pos[:, 1])], np.uint8)
    d.update({'pos': base64.b64encode(pos.astype(np.float32).tobytes()).decode(),
              'idx': base64.b64encode(f2.astype(np.uint32).tobytes()).decode(),
              'kls': base64.b64encode(kls.tobytes()).decode(), 'nv': int(len(pos))})
    js = (f'/* Сапог по картинке Александра: сетка генератора 3D → кирпичи полшипа и гладкая литая поверхность (tools/forma/kirpichi.py). */\n'
          f'(function (g) {{ g.{GLOB} = ' + json.dumps(d) + '; })(typeof window !== "undefined" ? window : globalThis);\n')
    open(out, 'w').write(js); print('гладкая сетка', len(pos), 'вершин', len(f2), 'треугольников', len(js) // 1024, 'КБ')
