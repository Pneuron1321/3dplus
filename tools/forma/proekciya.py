"""Краска Плюса — проекцией крупных видов прямо на развёртку сетки (30.10, вместо краски генератора: она мажет по ~512 px).

python3.13 tools/forma/proekciya.py сетка.glb виды/ выход.glb [--treug 80000] [--tex 4096]

виды/ — front, left, back, right, v34 .png (quest-claude docs/maket/vidy-30-10/), ортографические, фон ровный.
1. Сетка упрощается (Blender, decimate) до --treug треугольников, развёртка — xatlas.
2. Каждый вид совмещается с сеткой по силуэту: маска фигуры на картинке против силуэта сетки (перебор масштаба и сдвига,
   для боковых — и зеркала: так узнаём, куда смотрит лицо).
3. Для каждой точки текстуры (тексель) — её место на сетке и нормаль. Из видов, где точка видна (буфер глубины),
   берётся цвет того, что смотрит на неё прямее всех (вес cos^8, смешение только у швов между видами — без «каши»).
   Невидимые ни с одного вида точки (подмышки, между ног) — цвет ближайшего окрашенного соседа по развёртке.
Материал — матовый пластик (metallic 0, roughness 0.85).
"""
import os, sys, math, subprocess, tempfile
import numpy as np, trimesh, xatlas
from PIL import Image
from scipy import ndimage

SETKA, VIDY_D, OUT = sys.argv[1:4]
arg = lambda k, d: type(d)(sys.argv[sys.argv.index(k) + 1]) if k in sys.argv else d
TREUG, TEX, STEP = arg('--treug', 80000), arg('--tex', 4096), arg('--step', 8.0)
# --tochno: сетка собрана по этим же видам (sborka.py) — лицо к +z, без поиска: рамка фигуры = рамка силуэта, без 3/4
TOCHNO = '--tochno' in sys.argv

# ---------- 1. упрощение (Blender в отдельном процессе) и развёртка ----------
tmp = tempfile.mkdtemp()
FCOL = FNOGA = None
if TOCHNO:
    # сборка: детали отдельными узлами — у каждой грани свой цвет детали (для мест, невидимых ни с одного вида)
    # и признак «нога» (вид сбоку по высоте с видом спереди не согласован в ногах — с бока ноги не красим)
    sc = trimesh.load(SETKA, force='scene'); vv, ff, cc, nn, n0 = [], [], [], [], 0
    for node in sc.graph.nodes_geometry:
        T, g = sc.graph[node]; gm = sc.geometry[g]
        bc = np.asarray(gm.visual.material.baseColorFactor[:3], float); bc = bc / 255 if bc.max() > 1 else bc
        vv.append(trimesh.transform_points(gm.vertices, T)); ff.append(gm.faces + n0); n0 += len(gm.vertices)
        cc.append(np.tile(np.where(bc <= 0.0031308, bc * 12.92, 1.055 * bc ** (1 / 2.4) - 0.055) * 255, (len(gm.faces), 1)))
        nn.append(np.full(len(gm.faces), node.startswith(('bedro', 'koleno', 'golen', 'lodyzhka', 'tapok'))))
    src = trimesh.Trimesh(np.concatenate(vv), np.concatenate(ff), process=False)
    FCOL, FNOGA = np.concatenate(cc), np.concatenate(nn)
else:
    src = trimesh.load(SETKA, force='mesh')
if not TOCHNO and len(src.faces) > TREUG * 1.1:
    src.export(tmp + '/in.ply')
    subprocess.run([sys.executable, '-c', '''
import bpy, sys
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.wm.ply_import(filepath="%s/in.ply")
o = bpy.context.selected_objects[0]; bpy.context.view_layer.objects.active = o
d = o.modifiers.new("d", "DECIMATE"); d.ratio = %f
bpy.ops.object.modifier_apply(modifier="d")
bpy.ops.wm.ply_export(filepath="%s/out.ply", export_normals=False, export_uv=False, export_colors="NONE")
''' % (tmp, TREUG / len(src.faces), tmp)], check=True, capture_output=True)
    src = trimesh.load(tmp + '/out.ply', force='mesh')
V0, F0 = np.asarray(src.vertices, float), np.asarray(src.faces)
print('сетка:', len(V0), 'вершин,', len(F0), 'треугольников')
vmap, F, UV = xatlas.parametrize(V0, F0)
V = V0[vmap]
m = trimesh.Trimesh(V, F, process=False)
FN = m.face_normals

# ---------- растеризатор (numpy, по треугольникам) ----------
def rast(P2, F, W, H, z=None):
    """P2 — точки в пикселях (x, y). Возвращает (индекс треугольника, барицентры) на сетке W×H; с z — ближайший."""
    tri = np.full((H, W), -1, np.int64); bar = np.zeros((H, W, 3), np.float32)
    zb = np.full((H, W), np.inf) if z is not None else None
    T = P2[F]
    lo = np.floor(T.min(1)).astype(int); hi = np.ceil(T.max(1)).astype(int)
    lo = np.clip(lo, 0, [W - 1, H - 1]); hi = np.clip(hi, 0, [W - 1, H - 1])
    for i in range(len(F)):
        x0, y0 = lo[i]; x1, y1 = hi[i]
        if x1 < x0 or y1 < y0:
            continue
        xs, ys = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        a, b, c = T[i]
        d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(d) < 1e-12:
            continue
        l0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / d
        l1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / d
        l2 = 1 - l0 - l1
        ok = (l0 >= -1e-4) & (l1 >= -1e-4) & (l2 >= -1e-4)
        if not ok.any():
            continue
        yy, xx = np.nonzero(ok); yy += y0; xx += x0
        L = np.stack([l0[ok], l1[ok], l2[ok]], 1)
        if z is not None:
            zz = L @ z[F[i]]
            near = zz < zb[yy, xx]
            yy, xx, L, zz = yy[near], xx[near], L[near], zz[near]
            zb[yy, xx] = zz
        tri[yy, xx] = i; bar[yy, xx] = L
    return tri, bar, zb

# ---------- 2. виды и совмещение ----------
def maska(im):
    a = np.asarray(im).astype(int)
    fon = np.concatenate([a[:15, :15].reshape(-1, 3), a[:15, -15:].reshape(-1, 3)]).mean(0)
    mk = np.abs(a - fon).sum(2) > 40
    mk = ndimage.binary_opening(mk, iterations=2)
    lab, n = ndimage.label(mk)
    if n > 1:
        mk = lab == (np.bincount(lab.ravel())[1:].argmax() + 1)
    return ndimage.binary_fill_holes(mk)

def povorot(ugol):
    c, s = math.cos(ugol), math.sin(ugol)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])

def proekt(Vr, s, ox, oy, H, sx=1.0):
    """ортографически: x вправо, y вверх → пиксели (y вниз); sx — свой масштаб по ширине (генератор чуть шире/уже)."""
    return np.stack([Vr[:, 0] * s * sx + ox, H - (Vr[:, 1] * s + oy)], 1)

def sovmestit(mk, Vr, toch=False):
    """масштаб и сдвиг по рамке силуэта, потом спуск по IoU (пока улучшается). toch — силуэт растеризацией."""
    H, W = mk.shape
    ys, xs = np.nonzero(mk)
    s = (ys.max() - ys.min()) / np.ptp(Vr[:, 1])
    oy = (H - ys.max()) - Vr[:, 1].min() * s
    ox = (xs.min() + xs.max()) / 2 - (Vr[:, 0].min() + Vr[:, 0].max()) / 2 * s
    k = 4; mks = mk[::k, ::k]
    def iou(s, ox, oy, sx):
        if toch:
            t, _, _ = rast(proekt(Vr, s, ox, oy, H, sx) / k, F, mks.shape[1], mks.shape[0]); sil = t >= 0
        else:
            # силуэт — точки поверхности на уменьшенной картинке, щели закрыты (быстро, без растеризации)
            p = (proekt(Vr, s, ox, oy, H, sx) / k).astype(int)
            ok = (p[:, 0] >= 0) & (p[:, 0] < mks.shape[1]) & (p[:, 1] >= 0) & (p[:, 1] < mks.shape[0])
            sil = np.zeros_like(mks); sil[p[ok, 1], p[ok, 0]] = True
            sil = ndimage.binary_fill_holes(ndimage.binary_closing(sil, iterations=2))
        return (sil & mks).sum() / max((sil | mks).sum(), 1)
    x = [s, ox, oy, 1.0]
    best = iou(*x)
    if TOCHNO:
        return (best, *x)
    for shag in ((16.0, 0.02), (8.0, 0.01), (4.0, 0.005), (2.0, 0.0025)):
        luchshe = True
        while luchshe:
            luchshe = False
            for i, d in ((0, x[0] * shag[1]), (1, shag[0]), (2, shag[0]), (3, shag[1])):
                for zn in (1, -1):
                    y = list(x); y[i] += zn * d
                    v = iou(*y)
                    if v > best + 1e-5:
                        best, x, luchshe = v, y, True
    return (best, *x)

# лицо сетки: +z или −z? проверяем по боковому виду «left» (лицо к левому краю картинки)
vidy = {k: Image.open(os.path.join(VIDY_D, k + '.png')).convert('RGB') for k in ('front', 'back', 'left', 'right', 'v34')}
maski = {k: maska(im) for k, im in vidy.items()}
# поворот сетки вокруг y, при котором вид смотрит на неё: front — лицо к камере (+z к зрителю)
# left: лицо смотрит влево на картинке → камера видит правый бок фигуры; right — наоборот; v34 — ~40° между front и одним боком
varianty = {0.0: {'left': 1.0}} if TOCHNO else {}
for lico in (() if TOCHNO else (0.0, math.pi)):                                   # 0 — лицо сетки к +z, π — к −z
    sc = {}
    for k, u in (('left', -math.pi / 2), ('right', math.pi / 2)):
        R = povorot(lico + u)
        sc[k] = sovmestit(maski[k], V @ R.T)[0]
    varianty[lico] = sc
    print('лицо', 'к +z' if lico == 0 else 'к −z', {k: round(v, 3) for k, v in sc.items()})
LICO = max(varianty, key=lambda l: sum(varianty[l].values()))
# v34: угол перебором
best34 = ((0, 1, 0, 0, 1), 0.0) if TOCHNO else None
for g in (() if TOCHNO else range(5, 61, 5)):
    for zn in (1, -1):
        R = povorot(LICO + zn * math.radians(g))
        r = sovmestit(maski['v34'], V @ R.T)
        if best34 is None or r[0] > best34[0][0]:
            best34 = (r, zn * math.radians(g))
print('3/4: угол %.0f°, IoU %.3f' % (math.degrees(best34[1]), best34[0][0]))
UGLY = {'front': LICO, 'back': LICO + math.pi, 'left': LICO - math.pi / 2, 'right': LICO + math.pi / 2, 'v34': LICO + best34[1]}
if TOCHNO:
    del UGLY['v34']

kamery = {}
for k, u in UGLY.items():
    R = povorot(u); Vr = V @ R.T
    iou, s, ox, oy, sx = best34[0] if k == 'v34' else sovmestit(maski[k], Vr)
    H, W = maski[k].shape
    P2 = proekt(Vr, s, ox, oy, H, sx)
    _, _, zb = rast(P2, F, W, H, z=-Vr[:, 2])                 # глубина: к камере (+z после поворота) — меньше
    kamery[k] = dict(R=R, s=s, sx=sx, ox=ox, oy=oy, H=H, W=W, zb=zb, img=np.asarray(vidy[k]).astype(np.float32),
                    mk=ndimage.binary_erosion(maski[k], iterations=4))   # край фигуры — с примесью фона, не берём
    print('%-5s IoU %.3f  масштаб %.1f px/ед, ширина ×%.3f' % (k, iou, s, sx))

# ---------- 3. запекание по текселям ----------
UVp = np.stack([UV[:, 0] * TEX, (1 - UV[:, 1]) * TEX], 1)
tri, bar, _ = rast(UVp, F, TEX, TEX)
yy, xx = np.nonzero(tri >= 0)
ti = tri[yy, xx]; L = bar[yy, xx]
P = (V[F[ti]] * L[:, :, None]).sum(1)
N = FN[ti]
acc = np.zeros((len(P), 3)); wsum = np.zeros(len(P)); wmax = np.zeros(len(P)); cbest = np.zeros((len(P), 3))
eps = 0.004
for k, c in kamery.items():
    Pr = P @ c['R'].T; Nr = N @ c['R'].T
    cosv = Nr[:, 2]                                           # камера смотрит вдоль −z, зритель на +z
    px = proekt(Pr, c['s'], c['ox'], c['oy'], c['H'], c['sx'])
    xi = np.clip(px[:, 0].astype(int), 0, c['W'] - 1); yi = np.clip(px[:, 1].astype(int), 0, c['H'] - 1)
    vis = (cosv > 0.05) & (-Pr[:, 2] <= c['zb'][yi, xi] + eps)
    if FNOGA is not None and k in ('left', 'right'):
        # ноги на боковых видах ниже, чем на виде спереди (бедро на 140 точек): цвет берём по опорным высотам
        # (вид спереди → вид слева: бедро, низ бедра, колено, верх и низ голени, верх тапка, подошва)
        PF = np.array([776, 905, 945, 975, 1100, 1185, 1383.]); PL = np.array([915, 1060, 1093, 1118, 1210, 1235, 1409.])
        ys_, = np.nonzero(c['mk'].any(1)); verh, niz = ys_.min(), ys_.max()
        PL = verh + (PL - 43) * (niz - verh) / 1366                  # для вида справа — по его рамке
        yF = 1383 - P[:, 1] * 1000
        noga = FNOGA[ti]
        yk = np.where(noga, np.interp(yF, PF, PL), px[:, 1])
        px = np.stack([px[:, 0], yk], 1)
        yi = np.clip(px[:, 1].astype(int), 0, c['H'] - 1)
    vis &= c['mk'][yi, xi]
    # цвет — билинейно
    fx = np.clip(px[:, 0] - .5, 0, c['W'] - 1.001); fy = np.clip(px[:, 1] - .5, 0, c['H'] - 1.001)
    x0 = fx.astype(int); y0 = fy.astype(int); ax = (fx - x0)[:, None]; ay = (fy - y0)[:, None]
    I = c['img']
    col = (I[y0, x0] * (1 - ax) * (1 - ay) + I[y0, x0 + 1] * ax * (1 - ay) + I[y0 + 1, x0] * (1 - ax) * ay + I[y0 + 1, x0 + 1] * ax * ay)
    w = np.where(vis, np.clip(cosv, 0, 1) ** STEP, 0)
    if k == 'v34':
        # 3/4 — только если генератор/картинка повёрнуты заметно (≥ 25°): иначе это второй «спереди» с другим
        # совмещением, и смесь двоит визор (30.10, угол вышел 10°)
        w *= 0.5 if abs(UGLY['v34'] - LICO) >= math.radians(25) else 0.0
    acc += col * w[:, None]; wsum += w
    better = w > wmax; cbest[better] = col[better]; wmax[better] = w[better]
    print('%-5s видно %.0f %% текселей' % (k, 100 * vis.mean()))
# смесь только около швов: если лучший вид сильно впереди — берём его цвет целиком
mix = acc / np.maximum(wsum, 1e-9)[:, None]
dom = wmax / np.maximum(wsum, 1e-9)
cvet = np.where((dom > 0.55)[:, None], cbest, mix)
tex = np.zeros((TEX, TEX, 3), np.float32); est = np.zeros((TEX, TEX), bool)
ok = wsum > 0
tex[yy[ok], xx[ok]] = cvet[ok]; est[yy[ok], xx[ok]] = True
print('окрашено %.1f %% текселей сетки' % (100 * ok.mean()))
# невидимые и поля вокруг островов развёртки — ближайший окрашенный
if FCOL is not None:                                          # невидимое ни с одного вида — цвет своей детали
    tex[yy[~ok], xx[~ok]] = FCOL[ti[~ok]]; est[yy, xx] = True
_, (iy, ix) = ndimage.distance_transform_edt(~est, return_indices=True)
tex = tex[iy, ix]
Image.fromarray(np.clip(tex, 0, 255).astype(np.uint8)).save(OUT.rsplit('.', 1)[0] + '-tex.png')

mat = trimesh.visual.material.PBRMaterial(baseColorTexture=Image.fromarray(np.clip(tex, 0, 255).astype(np.uint8)),
                                          metallicFactor=0.0, roughnessFactor=0.85)
m.visual = trimesh.visual.TextureVisuals(uv=UV, material=mat)
m.export(OUT)
print(OUT)
