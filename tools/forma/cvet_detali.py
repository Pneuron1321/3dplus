"""Цвет литой сетки Плюса — заливкой по деталям (30.10, Александр: «цвета смазаны, наляпаны примерно в тех областях»).

python3.13 tools/forma/cvet_detali.py выход.js [--k 0.9] [--min 60] [--global PixelMechTeloSetkaD]

Цвет с листа (telo_raskraska.py) стоит примерно в нужных местах, но точками — «каша». Кирпичная фигурка окрашена
по деталям: одна деталь — один цвет. Поэтому:
1. Сетка делится на детали по своей форме: граф вершин, вес ребра — угол между нормалями (сгиб, стык деталей)
   плюс небольшой вклад разницы цвета с листа; слияние Фельценшвальба (порог растёт с размером детали, --k).
2. Каждой детали — один цвет: тот, что чаще всего у её вершин с листа (по площади).
3. Мелкие детали (< --min вершин) — к соседу, на которого больше всего похожи.
Позиции, треугольники и лицо — прежние (plus-telo-setka.js).
"""
import re, sys, base64, json
import numpy as np, trimesh

out = sys.argv[1]
arg = lambda k, d: (sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d)
K, MIN, VES_CVETA = float(arg('--k', 0.9)), int(arg('--min', 60)), float(arg('--cvet', 0.35))
GLOB = arg('--global', 'PixelMechTeloSetkaD')
ROOT = __file__.rsplit('/tools/', 1)[0]

t = open(ROOT + '/src/plus-telo-setka.js').read()
s64 = lambda k: re.search(k + r': "([^"]*)"', t).group(1)
pos = np.frombuffer(base64.b64decode(s64('pos')), np.float32).reshape(-1, 3).astype(float)
idx = np.frombuffer(base64.b64decode(s64('idx')), np.uint32).reshape(-1, 3).astype(np.int64)
cls = np.frombuffer(base64.b64decode(s64('cls')), np.uint8).astype(int)
klassy = json.loads(re.search(r'klassy: (\[[^\]]*\])', t).group(1))
lico = re.search(r'lico: (\[[^\]]*\])', t).group(1)
nv = len(pos)

m = trimesh.Trimesh(pos, idx, process=False)
n = m.vertex_normals
# площадь вершины — треть площадей её треугольников (голос по площади, не по числу вершин)
pl = np.zeros(nv); np.add.at(pl, idx.ravel(), np.repeat(m.area_faces / 3, 3))

e = np.unique(np.sort(np.concatenate([idx[:, [0, 1]], idx[:, [1, 2]], idx[:, [2, 0]]]), 1), axis=0)
ugol = np.arccos(np.clip((n[e[:, 0]] * n[e[:, 1]]).sum(1), -1, 1))
w = ugol + VES_CVETA * (cls[e[:, 0]] != cls[e[:, 1]])
poryadok = np.argsort(w)

# Фельценшвальб на графе вершин
rod = np.arange(nv); razm = np.ones(nv, int); vnut = np.zeros(nv)
def koren(x):
    while rod[x] != x:
        rod[x] = rod[rod[x]]; x = rod[x]
    return x
for i in poryadok:
    a, b = koren(e[i, 0]), koren(e[i, 1])
    if a == b:
        continue
    if w[i] <= min(vnut[a] + K / razm[a], vnut[b] + K / razm[b]):
        if razm[a] < razm[b]:
            a, b = b, a
        rod[b] = a; razm[a] += razm[b]; vnut[a] = max(vnut[a], vnut[b], w[i])
lab = np.array([koren(i) for i in range(nv)])

# мелкие детали — к соседу (по самому слабому ребру между ними)
for _ in range(4):
    u, c = np.unique(lab, return_counts=True)
    melk = set(u[c < MIN])
    if not melk:
        break
    luch = {}
    for i in poryadok:
        a, b = lab[e[i, 0]], lab[e[i, 1]]
        if a == b:
            continue
        for x, y in ((a, b), (b, a)):
            if x in melk and x not in luch:
                luch[x] = y
    for x, y in luch.items():
        lab[lab == x] = y
u, lab = np.unique(lab, return_inverse=True)
print('деталей:', len(u))

# цвет детали — большинство по площади
nk = len(klassy)
golos = np.zeros((len(u), nk)); np.add.at(golos, (lab, cls), pl)
cvet = golos.argmax(1)
novyy = cvet[lab]
dolya = golos.max(1) / np.maximum(golos.sum(1), 1e-9)
print('совпадение цвета детали с её точками листа: медиана %.0f %%' % (100 * np.median(dolya)))
print('было:', dict(zip(klassy, np.bincount(cls, minlength=nk))), '\nстало:', dict(zip(klassy, np.bincount(novyy, minlength=nk))))

b64 = lambda a: base64.b64encode(np.ascontiguousarray(a).tobytes()).decode()
open(out, 'w').write('/* Плюс целиком — форма сетки генератора, цвет листа заливкой по деталям (tools/forma/cvet_detali.py, 30.10). */\n'
                     '(function (g) { g.%s = { pos: "%s", idx: "%s", cls: "%s", klassy: %s, lico: %s, nv: %d }; })'
                     '(typeof window !== "undefined" ? window : globalThis);\n' % (
                         GLOB, s64('pos'), s64('idx'), b64(novyy.astype(np.uint8)), json.dumps(klassy), lico, nv))
np.save(out + '.detali.npy', lab)
print(out)
