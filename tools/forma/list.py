"""Лист с ракурсами детали → маски видов (силуэты) и их цвета.

python3 tools/forma/list.py лист.png выход/ [--vidy front,left,back,right,v34] [--nn isnet-general-use.onnx]

Фон листа — светлый (сетка, подписи). Деталь отделяется по отличию от фона; каждый вид — отдельная колонка
(ищется по проекции маски на ось x). Подписи сверху и размерные линии снизу отрезаются (тонкие — открытием),
остаётся самый крупный кусок, дыры заполнены. Голова отделяется от подставки по самой узкой строке шеи.
Выход: vid-<имя>.png (RGBA: цвет + маска головы), vid-<имя>-vse.png (вместе с подставкой), vidy.json (рамки, подбородок).
"""
import sys, os, json
import numpy as np
from PIL import Image
from scipy import ndimage as nd

src, out = sys.argv[1], sys.argv[2]
names = (sys.argv[sys.argv.index('--vidy') + 1] if '--vidy' in sys.argv else 'front,left,back,right,v34').split(',')
os.makedirs(out, exist_ok=True)
NN = sys.argv[sys.argv.index('--nn') + 1] if '--nn' in sys.argv else None   # путь к isnet-general-use.onnx
a = np.asarray(Image.open(src).convert('RGB')).astype(float)
H, W, _ = a.shape
bg = np.median(a.reshape(-1, 3), 0)
d = a - bg
# деталь: заметно светлее фона (белые кирпичи), заметно темнее или цветная; тонкие линии сетки уберёт открытие
fg = (d.min(2) > 10) | (d.max(2) < -38) | ((a.max(2) - a.min(2)) > 40)
fg = nd.binary_opening(fg, iterations=1)
fg = nd.binary_closing(fg, iterations=2)
fg[:int(H * 0.16)] = False                                   # подписи видов сверху
cols = nd.binary_opening(fg, iterations=1).sum(0)
runs, s = [], None
for x, c in enumerate(cols):
    if c > 3 and s is None: s = x
    if c <= 3 and s is not None:
        if x - s > 40: runs.append((s, x))
        s = None
if s is not None and W - s > 40: runs.append((s, W))
assert len(runs) >= len(names), f'видов найдено {len(runs)}: {runs}'
runs = sorted(runs, key=lambda r: r[1] - r[0], reverse=True)[:len(names)]
runs.sort()
info = {}
for name, (x0, x1) in zip(names, runs):
    m = fg[:, max(0, x0 - 4):min(W, x1 + 4)]
    if NN:                                                   # нейросеть-вырезалка: белое на светлом фоне
        import subprocess, tempfile
        tmp = tempfile.mkdtemp(); Image.fromarray(a[:, max(0, x0 - 4):min(W, x1 + 4)].astype(np.uint8)).save(tmp + '/v.png')
        subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'vyrez.py'), NN, tmp + '/v.png', tmp + '/m.png'], check=True)
        m = (np.asarray(Image.open(tmp + '/m.png')) > 128) & m.any(1, keepdims=True) | (np.asarray(Image.open(tmp + '/m.png')) > 128)
        m[:int(H * 0.16)] = False
    m = nd.binary_opening(m, iterations=2)                   # размерные линии и тонкие подписи — прочь
    m = nd.binary_fill_holes(nd.binary_closing(m, iterations=3))
    lab, n = nd.label(m); sz = nd.sum(m, lab, range(1, n + 1)); m = lab == (1 + int(np.argmax(sz)))
    m = nd.binary_fill_holes(m)
    ys, xs = np.where(m)
    y0, y1 = ys.min(), ys.max()
    # шея: самая узкая строка в нижней половине (между головой и подставкой)
    wid = np.array([m[y].sum() for y in range(y0, y1 + 1)])
    lo, hi = int(len(wid) * 0.5), int(len(wid) * 0.9)
    neck = y0 + lo + int(np.argmin(wid[lo:hi]))
    # подбородок: последняя строка над шеей, где ширина ещё ≥ 60 % ширины головы
    head_w = wid[:neck - y0].max()
    chin = neck
    while chin > y0 and m[chin].sum() < 0.6 * head_w: chin -= 1
    cut = np.asarray(a[:, max(0, x0 - 4):min(W, x1 + 4)]).astype(np.uint8)
    mh = m.copy(); mh[chin + 1:] = False
    lab, n = nd.label(mh); sz = nd.sum(mh, lab, range(1, n + 1)); mh = lab == (1 + int(np.argmax(sz)))
    Image.fromarray(np.dstack([cut, (mh * 255).astype(np.uint8)])).save(f'{out}/vid-{name}.png')
    Image.fromarray(np.dstack([cut, (m * 255).astype(np.uint8)])).save(f'{out}/vid-{name}-vse.png')
    ys, xs = np.where(mh)
    info[name] = {'x0': int(xs.min()), 'x1': int(xs.max()), 'y0': int(ys.min()), 'y1': int(ys.max()),
                  'sheya': int(neck), 'niz': int(y1), 'list_x0': int(max(0, x0 - 4))}
    print(name, info[name])
json.dump(info, open(f'{out}/vidy.json', 'w'), indent=1)
