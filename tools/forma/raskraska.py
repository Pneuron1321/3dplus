"""Сетка генератора (по виду 3/4) + виды листа → раскрашенная деталь для pixel-mech.js (литая поверхность).

python3 tools/forma/raskraska.py сетка.glb папка_видов/ выход.js [--vysota 10.4] [--tri 60000] [--global ИМЯ]
                                 [--ulybka]   # рот листа закрасить жёлтым (поверх ставится живая улыбка)

Сетка генератора ставит голову анфас (лицо к +z). Её рамка совмещается с рамкой головы на каждом виде листа;
цвет вершины — с вида, куда смотрит её нормаль (спереди, сзади, сбоку). Бок — правый вид листа (он целый;
по симметрии годится и для левой стороны). Цвета сводятся к палитре кирпичей (как в obolochka.py), затем
сглаживаются по соседям — без пятен. Выход: позиции (единицы модели), треугольники, класс цвета вершины.
"""
import sys, json, base64, colorsys
import numpy as np
import trimesh, fast_simplification as fs
from PIL import Image
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components

src, D, out = sys.argv[1:4]
arg = lambda k, d: (sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d)
VYS, TRI, GLOB = float(arg('--vysota', 10.4)), int(arg('--tri', 60000)), arg('--global', 'PixelMechGolovaSetka')
m = trimesh.load(src, force='mesh')
m.merge_vertices(); m.update_faces(m.unique_faces()); m.update_faces(m.nondegenerate_faces()); m.remove_unreferenced_vertices()
v, f = fs.simplify(np.ascontiguousarray(m.vertices, dtype=np.float32), np.ascontiguousarray(m.faces, dtype=np.int32),
                   target_reduction=max(0, 1 - TRI / len(m.faces)))
f = f.astype(np.int64)
e = np.concatenate([f[:, [0, 1]], f[:, [1, 2]], f[:, [2, 0]]])
nc, lab = connected_components(coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(len(v), len(v))), directed=False)
cnt = np.bincount(lab[f[:, 0]], minlength=nc); f = f[cnt[lab[f[:, 0]]] >= 60]
used = np.unique(f); rm = -np.ones(len(v), int); rm[used] = np.arange(len(used)); v = v[used].astype(float); f = rm[f]
# шея: генератор лепит и кусок шеи под подбородком — срезать по самому узкому месту в нижней трети (как у видов листа)
ys = v[:, 1]; y0_, y1_ = ys.min(), ys.max(); bins = np.linspace(y0_, y0_ + (y1_ - y0_) * 0.35, 30)
wid = [np.ptp(v[(ys >= bins[i]) & (ys < bins[i + 1]), 0]) if ((ys >= bins[i]) & (ys < bins[i + 1])).sum() > 5 else 1e9 for i in range(len(bins) - 1)]
icut = int(np.argmin(wid)); ycut = bins[icut + 1]
if wid[icut] < 0.75 * np.ptp(v[:, 0]):
    f = f[(ys[f] > ycut).any(1)]
    used = np.unique(f); rm = -np.ones(len(v), int); rm[used] = np.arange(len(used)); v = v[used]; f = rm[f]
    print('шея срезана на', round((ycut - y0_) / (y1_ - y0_), 3), 'высоты')
mm = trimesh.Trimesh(v, f, process=False); n = mm.vertex_normals
lo, hi = v.min(0), v.max(0)
info = json.load(open(D + '/vidy.json'))
def vid(name):
    a = np.asarray(Image.open(f'{D}/vid-{name}.png').convert('RGBA')).astype(float)
    i = info[name]
    return a, (i['x0'], i['x1'], i['y0'], i['y1'])
views = {'front': vid('front'), 'back': vid('back'), 'right': vid('right')}
def proj(name, u, uy):                                          # u, uy ∈ [0,1] → пиксель вида
    a, (x0, x1, y0, y1) = views[name]
    px = np.clip((x0 + u * (x1 - x0)).round().astype(int), 0, a.shape[1] - 1)
    py = np.clip((y0 + uy * (y1 - y0)).round().astype(int), 0, a.shape[0] - 1)
    return a[py, px, :3]
ny_ = (hi[1] - v[:, 1]) / (hi[1] - lo[1])
cF = proj('front', (v[:, 0] - lo[0]) / (hi[0] - lo[0]), ny_)
cB = proj('back', (hi[0] - v[:, 0]) / (hi[0] - lo[0]), ny_)
cS = proj('right', (v[:, 2] - lo[2]) / (hi[2] - lo[2]), ny_)
wF, wB, wS = np.maximum(n[:, 2], 0) ** 2, np.maximum(-n[:, 2], 0) ** 2, n[:, 0] ** 2
top = n[:, 1] > 0.6                                             # верх: по ближайшему виду — переду или заду
zc = (v[:, 2] - lo[2]) / (hi[2] - lo[2])
wF = np.where(top, (zc > 0.5) * 1.0, wF); wB = np.where(top, (zc <= 0.5) * 1.0, wB); wS = np.where(top, 0.0, wS)
best = np.argmax(np.stack([wF, wB, wS], 1), 1)
col = np.where(best[:, None] == 0, cF, np.where(best[:, None] == 1, cB, cS))
# главный источник цвета — вид 3/4: по нему генератор и строил сетку, поэтому совпадение точное. Поворот и наклон
# подбираются по силуэту (IoU), видимость — z-буфером; невидимое — зеркально с другой стороны (голова симметрична);
# что и так не видно (затылок), остаётся с видов спереди / сзади / сбоку.
from PIL import ImageDraw
from scipy.spatial import cKDTree
A34 = np.asarray(Image.open(f'{D}/vid-v34.png').convert('RGBA')).astype(float); M34 = A34[..., 3] > 128
H34, W34 = M34.shape; ys34, xs34 = np.where(M34); mb = (xs34.min(), xs34.max(), ys34.min(), ys34.max())
def rot(p, yaw, pitch):
    cy, sy, cp, sp = np.cos(yaw), np.sin(yaw), np.cos(pitch), np.sin(pitch)
    x, y, z = p[:, 0], p[:, 1], p[:, 2]; x, z = x * cy + z * sy, -x * sy + z * cy; y, z = y * cp - z * sp, y * sp + z * cp
    return np.stack([x, y, z], 1)
def kam(r):
    x0, x1, y0, y1 = r[:, 0].min(), r[:, 0].max(), r[:, 1].min(), r[:, 1].max()
    return mb[0] + (r[:, 0] - x0) * (mb[1] - mb[0]) / (x1 - x0), mb[3] - (r[:, 1] - y0) * (mb[3] - mb[2]) / (y1 - y0)
def sil(px, py):
    im = Image.new('L', (W34, H34), 0); d_ = ImageDraw.Draw(im); P = np.stack([px, py], 1)
    for t in f[::3]: d_.polygon([tuple(P[t[0]]), tuple(P[t[1]]), tuple(P[t[2]])], fill=255)
    return np.asarray(im) > 0
reg = (0, 0, 0)
for yaw in np.radians(np.arange(-60, 61, 6)):
    for pitch in np.radians([-20, -10, 0, 10, 20]):
        s_ = sil(*kam(rot(v, yaw, pitch))); iou = (s_ & M34).sum() / (s_ | M34).sum()
        if iou > reg[0]: reg = (iou, yaw, pitch)
for yaw in reg[1] + np.radians(np.arange(-4, 5, 2)):
    for pitch in reg[2] + np.radians(np.arange(-6, 7, 3)):
        s_ = sil(*kam(rot(v, yaw, pitch))); iou = (s_ & M34).sum() / (s_ | M34).sum()
        if iou > reg[0]: reg = (iou, yaw, pitch)
print('вид 3/4: IoU %.3f, поворот %.0f°, наклон %.0f°' % (reg[0], np.degrees(reg[1]), np.degrees(reg[2])))
r34 = rot(v, reg[1], reg[2]); px, py = kam(r34)
tri = f[np.argsort(r34[f].mean(1)[:, 2])]
idimg = Image.new('I', (W34, H34), -1); di = ImageDraw.Draw(idimg); P = np.stack([px, py], 1)
for i, t in enumerate(tri): di.polygon([tuple(P[t[0]]), tuple(P[t[1]]), tuple(P[t[2]])], fill=i)
idm = np.asarray(idimg); xi = np.clip(px.round().astype(int), 0, W34 - 1); yi = np.clip(py.round().astype(int), 0, H34 - 1)
vis = np.zeros(len(v), bool)
for dx in (-1, 0, 1):
    for dy in (-1, 0, 1):
        t = idm[np.clip(yi + dy, 0, H34 - 1), np.clip(xi + dx, 0, W34 - 1)]; ok = t >= 0
        vis[ok] |= (tri[t[ok]] == np.arange(len(v))[ok, None]).any(1)
vis &= M34[yi, xi] & ((r34[:, 2] - r34[:, 2].min()) > 0)
c34 = A34[yi, xi, :3]
col = col.copy(); col[vis] = c34[vis]
# зеркало: невидимая вершина берёт цвет видимой в зеркальной точке (x → −x относительно середины)
xm = (lo[0] + hi[0]) / 2; tr = cKDTree(v[vis]); mir = v[~vis].copy(); mir[:, 0] = 2 * xm - mir[:, 0]
dd, ii = tr.query(mir); okm = dd < 0.03 * np.ptp(v[:, 1])
idx_inv = np.where(~vis)[0]; col[idx_inv[okm]] = c34[vis][ii[okm]]
print('цвет с вида 3/4:', int(vis.sum()), 'по зеркалу:', int(okm.sum()), 'с других видов:', int((~okm).sum()), 'из', len(v))
KL = 'YWLMDKPRrO'
def klass(c):
    r, g, b = np.asarray(c, float) / 255
    h, l, sat = colorsys.rgb_to_hls(r, g, b); mx, mn = max(r, g, b), min(r, g, b)
    if mx - mn < 0.16:
        vv = (r + g + b) / 3
        return 'W' if vv > 0.55 else 'L' if vv > 0.44 else 'M' if vv > 0.34 else 'D' if vv > 0.16 else 'K'
    hd = h * 360
    if 250 <= hd <= 300: return 'P'
    if hd < 14 or hd > 330: return 'K' if mx < 0.36 else 'R'          # козырёк; тёмное под ним — глаза
    if 14 <= hd < 40: return 'O'                                       # рамки ушей
    if 40 <= hd < 72: return 'Y'
    return 'D'
kl = np.array([KL.index(klass(c)) for c in col], np.uint8)
# рот листа (чёрное ниже козырька на лице спереди) — жёлтым: поверх ставится живая улыбка
if '--ulybka' in sys.argv:
    face = (best == 0) & (ny_ > 0.62) & (ny_ < 0.95) & (np.abs((v[:, 0] - (lo[0] + hi[0]) / 2) / (hi[0] - lo[0])) < 0.3)
    kl[face & (kl == KL.index('K'))] = KL.index('Y')
# сглаживание по соседям (3 прохода): вершина берёт цвет большинства соседей, если она в меньшинстве
nb = [[] for _ in range(len(v))]
for a_, b_ in e[np.isin(e[:, 0], used)] if False else np.concatenate([f[:, [0, 1]], f[:, [1, 2]], f[:, [2, 0]]]):
    nb[a_].append(b_); nb[b_].append(a_)
for _ in range(3):
    k2 = kl.copy()
    for i in range(len(v)):
        if not nb[i]: continue
        c = np.bincount(kl[nb[i]], minlength=len(KL))
        if c[kl[i]] * 3 < len(nb[i]) and c.max() * 2 > len(nb[i]): k2[i] = np.argmax(c)
    kl = k2
# в единицы модели: высота VYS, низ (подбородок) на y = 0.2, по x — по центру, лицо вперёд
k = VYS / (hi[1] - lo[1])
p = (v - [(lo[0] + hi[0]) / 2, lo[1], hi[2]]) * k + [0, 0.2, 0]
d = {'nv': int(len(p)), 'kl': KL, 'pos': base64.b64encode(p.astype(np.float32).tobytes()).decode(),
     'idx': base64.b64encode(f.astype(np.uint32).tobytes()).decode(), 'cls': base64.b64encode(kl.tobytes()).decode(),
     'raz': [float(x) for x in (p.max(0) - p.min(0))]}
js = (f'/* Литая голова по листу: сетка генератора по виду 3/4, цвета — с видов листа (tools/forma/raskraska.py). */\n'
      f'(function (g) {{ g.{GLOB} = ' + json.dumps(d) + '; })(typeof window !== "undefined" ? window : globalThis);\n')
open(out, 'w').write(js)
print('вершин', len(p), 'треугольников', len(f), 'размер', np.round(d['raz'], 2), len(js) // 1024, 'КБ')
print('классы', {KL[i]: int(c) for i, c in enumerate(np.bincount(kl, minlength=len(KL))) if c})
