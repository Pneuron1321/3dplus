"""Объём по видам листа (оболочка по силуэтам) прямо в сетке шипов + цвет каждой клетки с видов → данные для Vox.

python3 tools/forma/obolochka.py папка_видов/ выход.js [--vysota 10.4] [--global PixelMechForma]

Виды — из tools/forma/list.py (vid-front.png, vid-left.png, vid-back.png, vidy.json). Лист ортогональный и
выровнен по строкам, поэтому строка листа = высота в модели для всех видов сразу. Масштаб — по высоте головы
(от верха до подбородка) = --vysota единиц (шаг шипа 0.8).
Ось x — вправо, y — вверх, z — к зрителю. Перед — вид спереди; бок — вид слева (лицо смотрит влево, это камера
на +x: +z слева); зад — вид сзади (зеркало по x); правый бок — зеркало левого.
Клетка внутри, если её центр внутри силуэта спереди И сбоку (доля покрытия клетки ≥ 0.5).
Цвет клетки на поверхности — с вида, куда она смотрит (по открытым соседям), и сводится к ближайшему цвету
палитры кирпичей. Выход: S (шаг), R (ряд), начало сетки, размеры, строка классов (по клетке символ, '.' — пусто).
"""
import sys, json
import numpy as np
from PIL import Image

D, OUT = sys.argv[1], sys.argv[2]
arg = lambda k, d: (sys.argv[sys.argv.index(k) + 1] if k in sys.argv else d)
VYS, GLOB = float(arg('--vysota', 10.4)), arg('--global', 'PixelMechForma')
info = json.load(open(D + '/vidy.json'))
def vid(n):
    a = np.asarray(Image.open(f'{D}/vid-{n}.png').convert('RGBA')).astype(float)
    return a[..., :3], a[..., 3] > 128
Fc, Fm = vid('front'); Lc, Lm = vid('left'); Bc, Bm = vid('back')
fi, li, bi = info['front'], info['left'], info['back']
TOP = min(fi['y0'], li['y0']); CHIN = fi['y1']
K = VYS / (CHIN - TOP)                                         # единиц на точку листа
cxF = (fi['x0'] + fi['x1']) / 2; cxB = (bi['x0'] + bi['x1']) / 2
zF = li['x0']                                                  # перед головы на виде сбоку (лицо слева)
Z_FRONT = 5.6
S, R, Y0 = 0.8, 0.512, 0.2
NX = int(np.ceil((fi['x1'] - fi['x0']) * K / S)) + 2
NZ = int(np.ceil((li['x1'] - li['x0']) * K / S)) + 2
NY = int(np.ceil(VYS / R)) + 1
X0 = -NX * S / 2; Z0 = Z_FRONT - NZ * S + 0.0
pf = lambda x: cxF + x / K                                     # x модели → столбец вида спереди
pb = lambda x: cxB - x / K                                     # вид сзади — зеркало
pl = lambda z: zF + (Z_FRONT - z) / K                          # z модели → столбец вида сбоку
py = lambda y: CHIN - (y - Y0) / K
def frac(m, xs, ys):                                           # доля точек клетки внутри маски
    xs = np.clip(np.round(xs).astype(int), 0, m.shape[1] - 1); ys = np.clip(np.round(ys).astype(int), 0, m.shape[0] - 1)
    return m[ys, xs].mean()
sub = np.linspace(-0.4, 0.4, 4)
def hull():
    o = np.zeros((NX, NY, NZ), bool)
    for i in range(NX):
        x = X0 + (i + 0.5) * S
        for j in range(NY):
            y = Y0 + (j + 0.5) * R
            yy = py(y + sub * R)
            if frac(Fm, *np.meshgrid(pf(x + sub * S), yy)) < 0.5: continue
            for k in range(NZ):
                z = Z0 + (k + 0.5) * S
                if frac(Lm, *np.meshgrid(pl(z + sub * S), yy)) >= 0.5: o[i, j, k] = True
    return o
occ0 = hull()
# сечение в плане: по двум видам шар выходит «скруглённым кубом» — в каждом ряду вписываем суперэллипс
# (ширина — с переда, глубина — с бока), показатель p и поворот вида 3/4 подбираем по силуэту вида 3/4
Vc, Vm = vid('v34'); vi = info['v34']
def sechenie(p):
    o = occ0.copy()
    for j in range(NY):
        sl = occ0[:, j, :]
        if not sl.any(): continue
        ii = np.where(sl.any(1))[0]; kk = np.where(sl.any(0))[0]
        cx = (X0 + (ii.min() + ii.max() + 1) * S / 2); a = (ii.max() - ii.min() + 1) * S / 2
        cz = (Z0 + (kk.min() + kk.max() + 1) * S / 2); c = (kk.max() - kk.min() + 1) * S / 2
        X = X0 + (np.arange(NX) + 0.5) * S; Z = Z0 + (np.arange(NZ) + 0.5) * S
        e = (np.abs(X[:, None] - cx) / a) ** p + (np.abs(Z[None, :] - cz) / c) ** p
        o[:, j, :] &= e <= 1.0
    return o
def iou34(o, yaw):
    # ортогональная проекция под поворотом yaw (лицо вправо-к зрителю, как на листе) → маска в точках вида 3/4
    ii, jj, kk = np.where(o)
    x = X0 + (ii + 0.5) * S; y = Y0 + (jj + 0.5) * R; z = Z0 + (kk + 0.5) * S
    u = x * np.cos(yaw) + z * np.sin(yaw)
    # рамка к рамке (вид 3/4 снят чуть сверху и в своём масштабе): сравниваем форму, а не положение
    h, w = Vm.shape; m = np.zeros((h, w), bool)
    vx = np.where(Vm.any(0))[0]; vy = np.where(Vm.any(1))[0]
    uu = vx.min() + (u - u.min()) / (np.ptp(u) + S) * (vx.max() - vx.min())
    yy = vy.max() - (y - y.min()) / (np.ptp(y) + R) * (vy.max() - vy.min())
    rx = max(1, int(round(S / (np.ptp(u) + S) * (vx.max() - vx.min()) / 2))); ry = max(1, int(round(R / (np.ptp(y) + R) * (vy.max() - vy.min()) / 2)))
    for a_, b_ in zip(uu.astype(int), yy.astype(int)):
        m[max(0, b_ - ry):b_ + ry + 1, max(0, a_ - rx):a_ + rx + 1] = True
    return (m & Vm).sum() / max(1, (m | Vm).sum())
best = (0, None, None)
for p_ in [1.6, 2.0, 2.5, 3.0, 4.0, 8.0]:
    row = []
    o = sechenie(p_)
    for yaw in np.radians(np.arange(-60, 61, 10)):
        v = iou34(o, yaw)
        row.append(v)
        if v > best[0]: best = (v, p_, yaw)
    print('  p', p_, 'лучшее IoU 3/4 %.3f' % max(row))
print('вид 3/4: IoU %.3f, показатель сечения %.1f, поворот %d°' % (best[0], best[1], np.degrees(best[2])))
occ = sechenie(best[1])
print('сетка', NX, NY, NZ, 'клеток', int(occ.sum()), 'масштаб', round(K, 4))

# палитра кирпичей (sRGB) → символ класса
PAL = {'Y': (246, 205, 16), 'W': (225, 226, 224), 'L': (189, 190, 190), 'M': (138, 139, 140), 'D': (86, 87, 89),
       'K': (25, 25, 27), 'P': (134, 86, 236), 'R': (215, 50, 30), 'r': (120, 20, 12), 'O': (242, 116, 0)}
keys = list(PAL); pal = np.array([PAL[k] for k in keys], float)
import colorsys
def klass(c):
    r, g_, b = np.asarray(c, float) / 255
    h, l, sat = colorsys.rgb_to_hls(r, g_, b); mx = max(r, g_, b); mn = min(r, g_, b)
    if mx - mn < 0.16:                                         # серые: белое на листе в тени сереет — пороги по яркости
        v = (r + g_ + b) / 3
        return 'W' if v > 0.6 else 'L' if v > 0.47 else 'M' if v > 0.36 else 'D' if v > 0.17 else 'K'
    hd = h * 360
    if 255 <= hd <= 300: return 'P'
    if (hd < 38 or hd > 330) and (r > g_ * 1.5): return 'r' if mx < 0.33 else 'V'     # козырёк (под ним — лицо)
    if 38 <= hd < 70: return 'Y'
    if 18 <= hd < 38: return 'O'
    if hd < 18 or hd > 330: return 'R' if mx > 0.55 else 'r'
    return 'D'
def cvet(img, x, y):
    xs = np.clip(np.round(x).astype(int), 0, img.shape[1] - 1); ys = np.clip(np.round(y).astype(int), 0, img.shape[0] - 1)
    px = img[ys, xs].reshape(-1, 3)
    return np.percentile(px, 70, axis=0)                       # швы между кирпичами темнее — берём светлую часть
# козырёк: красное на видах спереди и сбоку → рамка (полуширина, верх, низ, задний край) для прозрачной линзы
def krasnoe(img, m):
    a_ = img / 255; mx = a_.max(2); mn = a_.min(2); sat = (mx - mn) / np.maximum(mx, 1e-3)
    r, g2, b2 = a_[..., 0], a_[..., 1], a_[..., 2]
    k = m & (sat > 0.5) & (r > g2 * 2.4) & (r > b2 * 2.0)          # красный, не оранжевый
    from scipy import ndimage as nd
    k = nd.binary_closing(k, iterations=3)
    lab, n = nd.label(k); sz = nd.sum(k, lab, range(1, n + 1))
    return lab == (1 + int(np.argmax(sz)))
kf = krasnoe(Fc, Fm); kl = krasnoe(Lc, Lm)
ys_, xs_ = np.where(kf); yl_, xl_ = np.where(kl)
VIS = {'a': float(max(abs(xs_.min() - cxF), abs(xs_.max() - cxF)) * K), 'yt': float(Y0 + (CHIN - ys_.min()) * K),
       'yb': float(Y0 + (CHIN - np.percentile(ys_, 97)) * K), 'zb': float(Z_FRONT - (np.percentile(xl_, 95) - zF) * K)}
print('козырёк', {k: round(v, 2) for k, v in VIS.items()})
cls = np.full((NX, NY, NZ), '.', dtype='<U1')
g = lambda i, j, k: 0 <= i < NX and 0 <= j < NY and 0 <= k < NZ and occ[i, j, k]
for i in range(NX):
    for j in range(NY):
        for k in range(NZ):
            if not occ[i, j, k]: continue
            x, y, z = X0 + (i + 0.5) * S, Y0 + (j + 0.5) * R, Z0 + (k + 0.5) * S
            open_ = {'zf': not g(i, j, k + 1), 'zb': not g(i, j, k - 1), 'xr': not g(i + 1, j, k), 'xl': not g(i - 1, j, k), 'yt': not g(i, j + 1, k)}
            if not any(open_.values()): cls[i, j, k] = 'D'; continue
            yy = py(y + sub[1:3] * R)
            # куда смотрит клетка: перед/зад/бок; верх — по ближайшему из переда и зада
            if open_['zf'] or (open_['yt'] and z > 1.0 and not (open_['xr'] or open_['xl'])):
                c = cvet(Fc, *np.meshgrid(pf(x + sub[1:3] * S), yy))
            elif open_['zb'] or (open_['yt'] and z < -1.0 and not (open_['xr'] or open_['xl'])):
                c = cvet(Bc, *np.meshgrid(pb(x + sub[1:3] * S), yy))
            else:
                c = cvet(Lc, *np.meshgrid(pl(z + sub[1:3] * S), yy))
            cls[i, j, k] = klass(c)
# сглаживание: одиночная клетка чужого цвета среди соседей одного цвета — перекрашивается (шум выборки)
from collections import Counter
cl2 = cls.copy()
for i in range(NX):
    for j in range(NY):
        for k in range(NZ):
            c0 = cls[i, j, k]
            if c0 in '.D' : continue
            nb = [cls[i + a, j + b, k + e] for a, b, e in [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]
                  if 0 <= i + a < NX and 0 <= j + b < NY and 0 <= k + e < NZ and cls[i + a, j + b, k + e] not in '.D']
            if len(nb) >= 3:
                top, n = Counter(nb).most_common(1)[0]
                if top != c0 and n >= len(nb) - 1 and c0 not in 'PrK': cl2[i, j, k] = top
cls = cl2
d = {'visor': VIS, 'S': S, 'R': R, 'X0': X0, 'Y0': Y0, 'Z0': Z0, 'NX': NX, 'NY': NY, 'NZ': NZ, 'K': K,
     'cells': ''.join(cls.transpose(2, 1, 0).ravel())}             # порядок: k (z) внешний, j, i внутренний
js = ('/* Голова Плюса по листу 24.10: объём по силуэтам видов и цвет клеток с видов (tools/forma/obolochka.py). */\n'
      f'(function (g) {{ g.{GLOB} = ' + json.dumps(d) + '; })(typeof window !== "undefined" ? window : globalThis);\n')
open(OUT, 'w').write(js)
u, c = np.unique(cls[occ], return_counts=True); print('классы', dict(zip(u, c)))
