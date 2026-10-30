"""Цвет на литую сетку Плюса (telo=setka) — с кирпичной v6, а не с картинки (30.10, Александр: «форма более-менее,
цвета смазаны, совпадение не выше 20 %, наляпаны примерно в тех областях»).

python3.13 tools/forma/cvet_v6.py v6.glb выход.js [--global PixelMechTeloSetkaV6]

v6.glb — кирпичная модель с теми же ногами («утка», тапочки): node tools/forma/vygruzka.mjs v6.glb nogi=utka noAO=1.
У каждого кирпича v6 — чистый цвет палитры и правильное место (листы 1 и 2). Перенос:
1. Масштаб v6 по росту сетки, низ — к полу, центр — по x и z.
2. Части: голова, корпус и таз, руки, ноги, стопы (по именам узлов v6). Каждой вершине сетки — часть ближайшей
   точки v6; сглаживание меток по соседям.
3. Каждая часть v6 подгоняется к своей части сетки (ICP с масштабом): руки v6 шире, голова крупнее — без этого
   цвет руки ляжет на корпус.
4. Цвет вершины — палитра ближайшей точки подогнанной части (цвет v6 → ближайший цвет палитры).
5. Сглаживание: голосование соседей, островки меньше --ostrov вершин — в цвет соседей.
Выход — тот же формат, что plus-telo-setka.js (позиции и треугольники прежние, классы цвета — новые).
"""
import re, sys, base64, json
import numpy as np, trimesh
from scipy.spatial import cKDTree

v6p, out = sys.argv[1:3]
arg = lambda k, d: (sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d)
GLOB, OSTROV, SGLAD = arg('--global', 'PixelMechTeloSetkaV6'), int(arg('--ostrov', 40)), int(arg('--sglad', 3))
ROOT = __file__.rsplit('/tools/', 1)[0]

# ---------- сетка ----------
t = open(ROOT + '/src/plus-telo-setka.js').read()
s64 = lambda k: re.search(k + r': "([^"]*)"', t).group(1)
pos = np.frombuffer(base64.b64decode(s64('pos')), np.float32).reshape(-1, 3).astype(float)
idx = np.frombuffer(base64.b64decode(s64('idx')), np.uint32).reshape(-1, 3)
lico = json.loads(re.search(r'lico: (\[[^\]]*\])', t).group(1))
nv = len(pos)

# ---------- палитра (как в pixel-mech.js: sRGB → линейный) ----------
src = open(ROOT + '/src/pixel-mech.js').read()
pal_txt = src[src.index('const PALETTE = {'):src.index('};', src.index('const PALETTE = {'))]
PAL = {k: v for k, v in re.findall(r"(\w+): '#([0-9a-fA-F]{6})'", pal_txt)}
def lin(h):
    c = np.array([int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)])
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
KL = list(PAL)
PL = np.array([lin(PAL[k]) for k in KL])
# в «каше» цвета не нужны оттенки одного цвета: сводим к основным (один цвет детали — как у кирпичной фигурки)
OSNOVA = {'WH2': 'WH', 'LG2': 'LG', 'MG2': 'MG', 'MG4': 'MG3', 'DG2': 'DG', 'YE2': 'YE', 'YE3': 'YE', 'PU2': 'PU', 'PU3': 'PU',
          'O1': 'O2', 'O3': 'O4', 'O5': 'O2', 'O6': 'O2', 'OT1': 'O2', 'OT2': 'O2', 'OT3': 'O2', 'MN2': 'MN1', 'GM': 'BK',
          'RD2': 'RD1', 'SU2': 'SU1', 'SU3': 'SU1', 'SU4': 'SU1', 'NG2': 'NG1'}

def klass(c):
    """Цвет v6 (линейный) → основной класс палитры: в Lab-подобной мере (корень — ближе к глазу)."""
    d = ((np.sqrt(np.clip(c[:, None, :], 0, 1)) - np.sqrt(PL[None])) ** 2).sum(-1)
    k = np.array(KL)[d.argmin(1)]
    return np.array([OSNOVA.get(x, x) for x in k])

# ---------- v6 ----------
CHAST = [('golova', ('shlem', 'maska', 'ochki', 'naushnik', 'mouth', 'eye', 'golova', 'lico', 'shapka', 'bok')),
         ('korpus', ('korpus', 'taz', 'sheya')),
         ('rukaR', ('rukaR',)), ('rukaL', ('rukaL',)),
         ('nogaR', ('nogaR',)), ('nogaL', ('nogaL',)),
         ('stupnyaR', ('stupnyaR',)), ('stupnyaL', ('stupnyaL',))]
sc = trimesh.load(v6p, force='scene')
P, C, Q = [], [], []
for node in sc.graph.nodes_geometry:
    T, g = sc.graph[node]
    gm = sc.geometry[g]
    va = getattr(gm.visual, 'vertex_attributes', {}) or {}
    if 'color' not in va or 'linza' in node:
        continue                                                 # линза очков прозрачная — цвет оправы/маски под ней
    ch = next((i for i, (_, keys) in enumerate(CHAST) if any(node.startswith(k) for k in keys)), None)
    if ch is None:
        print('часть не узнана:', node); continue
    # точки — не только вершины, а и по граням: у больших кирпичей вершин мало
    m = trimesh.Trimesh(trimesh.transform_points(gm.vertices, T), gm.faces, process=False)
    pts, fi = trimesh.sample.sample_surface_even(m, max(200, len(gm.faces) * 2))
    col = np.asarray(va['color'])[:, :3][gm.faces[fi]].mean(1)
    P.append(pts); C.append(col); Q.append(np.full(len(pts), ch))
P, C, Q = np.concatenate(P), np.concatenate(C), np.concatenate(Q)
K6 = klass(C)
print('v6 точек', len(P), 'классы', dict(zip(*np.unique(K6, return_counts=True))))

# 1. масштаб по росту, низ к полу, центр по x/z
s = np.ptp(pos[:, 1]) / np.ptp(P[:, 1])
P = (P - [P[:, 0].mean(), P[:, 1].min(), P[:, 2].mean()]) * s + [pos[:, 0].mean(), pos[:, 1].min(), pos[:, 2].mean()]

# соседи сетки
e = np.concatenate([idx[:, [0, 1]], idx[:, [1, 2]], idx[:, [2, 0]]])
sosedi = [[] for _ in range(nv)]
for a, b in e:
    sosedi[a].append(b); sosedi[b].append(a)
sosedi = [np.unique(x) for x in sosedi]

def golosovat(lab, raz):
    for _ in range(raz):
        nov = lab.copy()
        for i in range(nv):
            sb = lab[sosedi[i]]
            if len(sb):
                u, c = np.unique(np.append(sb, lab[i]), return_counts=True)
                nov[i] = u[c.argmax()]
        lab = nov
    return lab

# 2. части сетки по ближайшей точке v6 (голова и стопы — по высоте надёжнее)
chast = Q[cKDTree(P).query(pos)[1]]
chast = golosovat(chast, 4)

# 3. каждая часть v6 — к своей части сетки (ICP: сдвиг, поворот, масштаб)
P2 = P.copy()
for ch in range(len(CHAST)):
    a, b = Q == ch, chast == ch
    if a.sum() < 50 or b.sum() < 50:
        continue
    # сначала центры и размах (масштаб по осям — грубо), потом ICP
    A, B = P[a], pos[b]
    A0 = (A - A.mean(0)) * (np.std(B, 0) / np.maximum(np.std(A, 0), 1e-6)) ** 0.5 + B.mean(0)
    sub = A0[np.random.default_rng(1).choice(len(A0), min(4000, len(A0)), replace=False)]
    M, _, cost = trimesh.registration.icp(sub, B, max_iterations=40, scale=True)
    P2[a] = trimesh.transform_points(A0, M)
    print('%-9s v6 %6d сетка %6d  ошибка %.3f' % (CHAST[ch][0], a.sum(), b.sum(), cost))

# 4. цвет — с ближайшей точки своей части (в пределах части; нет её — с любой)
klassy = np.empty(nv, dtype=object)
for ch in range(len(CHAST)):
    b, a = chast == ch, Q == ch
    if not b.any():
        continue
    if a.sum() < 20:
        a = np.ones(len(Q), bool)
    klassy[b] = K6[a][cKDTree(P2[a]).query(pos[b])[1]]
imena = sorted(set(klassy))
lab = np.array([imena.index(k) for k in klassy])

# 5. сглаживание: голосование и мелкие островки — в соседей
lab = golosovat(lab, SGLAD)
for _ in range(3):
    seen = np.zeros(nv, bool); izm = 0
    for i in range(nv):
        if seen[i]:
            continue
        st, comp = [i], []
        seen[i] = True
        while st:
            j = st.pop(); comp.append(j)
            for q in sosedi[j]:
                if not seen[q] and lab[q] == lab[i]:
                    seen[q] = True; st.append(q)
        if len(comp) < OSTROV:
            gr = np.unique(np.concatenate([sosedi[j] for j in comp]))
            gr = gr[lab[gr] != lab[i]]
            if len(gr):
                u, c = np.unique(lab[gr], return_counts=True)
                lab[comp] = u[c.argmax()]; izm += 1
    if not izm:
        break

u, c = np.unique(lab, return_counts=True)
print('итог:', {imena[k]: int(n) for k, n in zip(u, c)})
b64 = lambda a: base64.b64encode(np.ascontiguousarray(a).tobytes()).decode()
open(out, 'w').write('/* Плюс целиком — форма сетки генератора, цвет — с кирпичной v6 (tools/forma/cvet_v6.py, 30.10). */\n'
                     '(function (g) { g.%s = { pos: "%s", idx: "%s", cls: "%s", klassy: %s, lico: %s, nv: %d }; })'
                     '(typeof window !== "undefined" ? window : globalThis);\n' % (
                         GLOB, s64('pos'), s64('idx'), b64(lab.astype(np.uint8)), json.dumps(imena), json.dumps(lico), nv))
print(out)
