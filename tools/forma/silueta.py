"""Форма Плюса по силуэтам крупных видов — без генератора (30.10, Александр: «форма с изображений считалась плохо»).

python3.13 tools/forma/silueta.py виды/ выход.glb [--n 640] [--sglad 1.2]

виды/ — front, back, left (лицом влево), right (лицом вправо) .png, ортографические, фон ровный.
Точка объёма внутри, если она внутри силуэта спереди, сзади, слева и справа (оболочка по силуэтам). Каждый вид
сводится к своей высоте фигуры (низ подошвы — 0, макушка — 1), центр — середина рамки силуэта.
Оси: x вправо, y вверх, z к зрителю (лицо к +z). Спереди: x картинки = +x; сзади — −x; слева (лицо влево) — x картинки = −z;
справа — +z. Поверхность — marching cubes по размытому объёму и сглаживание Таубина (без ступенек от строк картинки).
Выход — рост 2 (y от −1 до 1), как у сетки генератора: дальше краска — tools/forma/proekciya.py.
"""
import sys
import numpy as np, trimesh
from PIL import Image
from scipy import ndimage
from skimage import measure

D, OUT = sys.argv[1], sys.argv[2]
arg = lambda k, d: type(d)(sys.argv[sys.argv.index(k) + 1]) if k in sys.argv else d
N, SGLAD = arg('--n', 640), arg('--sglad', 2.5)

def maska(put):
    a = np.asarray(Image.open(put).convert('RGB')).astype(int)
    fon = np.concatenate([a[:15, :15].reshape(-1, 3), a[:15, -15:].reshape(-1, 3)]).mean(0)
    mk = ndimage.binary_opening(np.abs(a - fon).sum(2) > 40, iterations=2)
    lab, n = ndimage.label(mk)
    if n > 1:
        mk = lab == (np.bincount(lab.ravel())[1:].argmax() + 1)
    mk = ndimage.binary_fill_holes(mk)
    ys, xs = np.nonzero(mk)
    return dict(mk=mk, niz=ys.max(), H=ys.max() - ys.min(), cx=(xs.min() + xs.max()) / 2,
                shir=(xs.max() - xs.min()) / (ys.max() - ys.min()))

V = {k: maska('%s/%s.png' % (D, k)) for k in ('front', 'back', 'left', 'right')}
for k, v in V.items():
    print('%-5s рост %d px, ширина/рост %.3f' % (k, v['H'], v['shir']))

def vnutri(v, u, y):
    """u — горизонталь картинки в долях роста от центра, y — высота в долях роста (0 — низ)."""
    px = np.round(v['cx'] + u * v['H']).astype(int); py = np.round(v['niz'] - y * v['H']).astype(int)
    ok = (px >= 0) & (px < v['mk'].shape[1]) & (py >= 0) & (py < v['mk'].shape[0])
    r = np.zeros(px.shape, bool); r[ok] = v['mk'][py[ok], px[ok]]
    return r

h = 1.0 / N
xr = 0.5 * max(V['front']['shir'], V['back']['shir']) + 4 * h
zr = 0.5 * max(V['left']['shir'], V['right']['shir']) + 4 * h
xs = np.arange(-xr, xr, h); ys = np.arange(-4 * h, 1 + 4 * h, h); zs = np.arange(-zr, zr, h)
vol = np.zeros((len(xs), len(ys), len(zs)), np.float32)
X2, Y2 = np.meshgrid(xs, ys, indexing='ij')                    # срез x-y (спереди/сзади)
Z2, Y3 = np.meshgrid(zs, ys, indexing='ij')                    # срез z-y (бока)
fb = vnutri(V['front'], X2, Y2) & vnutri(V['back'], -X2, Y2)   # (x, y)
lr = vnutri(V['left'], -Z2, Y3) & vnutri(V['right'], Z2, Y3)   # (z, y)
vol = (fb[:, :, None] & lr.T[None, :, :]).astype(np.float32)
print('объём: %d×%d×%d, заполнено %.1f %%' % (*vol.shape, 100 * vol.mean()))
if SGLAD > 0:
    vol = ndimage.gaussian_filter(vol, SGLAD)
v, f, _, _ = measure.marching_cubes(vol, 0.5, spacing=(h, h, h))
v += [xs[0], ys[0], zs[0]]
m = trimesh.Trimesh(v, f[:, ::-1], process=True)
m.update_faces(m.nondegenerate_faces()); m.remove_unreferenced_vertices()
parts = m.split(only_watertight=False)
m = max(parts, key=lambda p: len(p.faces))                       # без крошек
y0, y1 = m.vertices[:, 1].min(), m.vertices[:, 1].max()
m.vertices = (m.vertices - [0, (y0 + y1) / 2, 0]) * (2 / (y1 - y0))
if m.volume < 0:
    m.invert()
trimesh.smoothing.filter_taubin(m, lamb=0.5, nu=-0.53, iterations=20)   # остатки ступенек от строк картинки
print('сетка: %d вершин, %d треугольников' % (len(m.vertices), len(m.faces)))
m.export(OUT)
print(OUT)
