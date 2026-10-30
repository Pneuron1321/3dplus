"""Сборка Плюса рядом с крупными видами — ортографически, в масштабе вида спереди (30.10).

python3.13 tools/forma/orto.py модель.glb виды/ выход.png

Слева направо пары «картинка | модель»: спереди, слева, сзади. 1 мм модели = 1 точка вида спереди (как в sborka.py),
вид сбоку приведён к масштабу вида спереди (×1330/1366). Цвет — материал детали или текстура, свет мягкий сверху-спереди.
"""
import sys
import numpy as np, trimesh
from PIL import Image

GLB, VD, OUT = sys.argv[1:4]
CX, NIZ, CZ, KL = 540, 1383, 520, 1330 / 1366
W, H = 1086, 1448

sc = trimesh.load(GLB, force='scene')
V, F, C, UVF = [], [], [], []
TEX = None                                                     # текстура (одна на модель — как у proekciya.py)
n0 = 0
for node in sc.graph.nodes_geometry:
    T, g = sc.graph[node]
    m = sc.geometry[g]
    v = trimesh.transform_points(m.vertices, T)
    vis = m.visual
    if isinstance(vis, trimesh.visual.TextureVisuals) and getattr(vis.material, 'baseColorTexture', None) is not None and vis.uv is not None:
        TEX = np.asarray(vis.material.baseColorTexture.convert('RGB')).astype(float)
        uvf = vis.uv[m.faces]
        col = np.full((len(m.faces), 3), -1.0)                    # −1 — брать из текстуры по точке
    else:
        try:
            bc = np.asarray(vis.material.baseColorFactor[:3], float)
            bc = bc / 255 if bc.max() > 1 else bc
            col = np.tile(np.where(bc <= 0.0031308, bc * 12.92, 1.055 * bc ** (1 / 2.4) - 0.055) * 255, (len(m.faces), 1))
        except Exception:
            col = np.tile([200, 200, 200], (len(m.faces), 1))
        uvf = np.zeros((len(m.faces), 3, 2))
    if 'uvf' not in dir() or len(uvf) != len(m.faces):
        uvf = np.zeros((len(m.faces), 3, 2))
    V.append(v); F.append(m.faces + n0); C.append(col); UVF.append(uvf); n0 += len(v)
    del uvf
V, F, C, UVF = np.concatenate(V) * 1000, np.concatenate(F), np.concatenate(C), np.concatenate(UVF)
FN = np.cross(V[F[:, 1]] - V[F[:, 0]], V[F[:, 2]] - V[F[:, 0]])
FN /= np.maximum(np.linalg.norm(FN, axis=1, keepdims=True), 1e-12)

def render(Vr, Nr):
    """Vr — (x картинки, y вверх, глубина к камере). Растр по треугольникам с буфером глубины."""
    img = np.full((H, W, 3), 175.0); zb = np.full((H, W), -np.inf)
    P = np.stack([Vr[:, 0], NIZ - Vr[:, 1]], 1)
    svet = np.array([0.3, 0.5, 0.8]); svet /= np.linalg.norm(svet)
    sh = 0.55 + 0.45 * np.clip(Nr @ svet, 0, 1)
    for i in np.nonzero(Nr[:, 2] > 0)[0]:
        T = P[F[i]]; z = Vr[F[i], 2]
        x0, y0 = np.floor(T.min(0)).astype(int); x1, y1 = np.ceil(T.max(0)).astype(int)
        x0, y0 = max(x0, 0), max(y0, 0); x1, y1 = min(x1, W - 1), min(y1, H - 1)
        if x1 < x0 or y1 < y0:
            continue
        xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        a, b, c = T
        d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(d) < 1e-9:
            continue
        l0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / d
        l1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / d
        l2 = 1 - l0 - l1
        ok = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
        zz = l0 * z[0] + l1 * z[1] + l2 * z[2]
        sub = zb[y0:y1 + 1, x0:x1 + 1]
        ok &= zz > sub
        sub[ok] = zz[ok]
        if C[i, 0] < 0:
            u = l0 * UVF[i, 0, 0] + l1 * UVF[i, 1, 0] + l2 * UVF[i, 2, 0]
            w = l0 * UVF[i, 0, 1] + l1 * UVF[i, 1, 1] + l2 * UVF[i, 2, 1]
            tx = np.clip((u % 1) * (TEX.shape[1] - 1), 0, TEX.shape[1] - 1).astype(int)
            ty = np.clip((1 - w % 1) * (TEX.shape[0] - 1), 0, TEX.shape[0] - 1).astype(int)
            img[y0:y1 + 1, x0:x1 + 1][ok] = TEX[ty[ok], tx[ok]] * sh[i]
        else:
            img[y0:y1 + 1, x0:x1 + 1][ok] = C[i] * sh[i]
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))

x, y, z = V[:, 0], V[:, 1], V[:, 2]
nx, ny, nz = FN[:, 0], FN[:, 1], FN[:, 2]
vidy = [('front', np.stack([x + CX, y, z], 1), np.stack([nx, ny, nz], 1)),
        ('left', np.stack([CZ - z, y, x], 1), np.stack([-nz, ny, nx], 1)),       # лицо влево: x картинки = −z, к камере = +x
        ('back', np.stack([CX - x, y, -z], 1), np.stack([-nx, ny, -nz], 1))]
pary = []
for k, Vr, Nr in vidy:
    im = Image.open('%s/%s.png' % (VD, k)).convert('RGB')
    if k == 'left':                                        # к масштабу вида спереди: низ подошвы — 1383, рост 1330
        im = im.resize((round(W * KL), round(H * KL)), Image.LANCZOS)
        o = Image.new('RGB', (W, H), im.getpixel((5, 5))); o.paste(im, (round(CZ - CZ * KL), round(NIZ - 1409 * KL))); im = o
    pary += [im, render(Vr, Nr)]
lst = Image.new('RGB', (W * len(pary), H), (255, 255, 255))
for i, im in enumerate(pary):
    lst.paste(im, (i * W, 0))
lst.resize((lst.width // 2, lst.height // 2), Image.LANCZOS).save(OUT)
print(OUT)
