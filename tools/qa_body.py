"""Сверка ТЕЛА с листом 2 (новое изображение заказчика) в мировых единицах.

Запуск:  node tools/build.mjs --view=qa && python3 tools/qa_body.py <лист2.jpg> [renders/qa.png]
Нужны Pillow и numpy. Рядом с рендером должен лежать renders/qa.json — проекции сетки
опорных точек (x −10/0/10, y 0/10/20, z 0), их пишет tools/build.mjs.

Как считает:
  • лист 2 переводится в мир по замеру: 22 px на единицу, центр x = 246, пол y = 731
    (масштаб — по ширине головы, см. docs/PROMPT-v5.md);
  • рендер — по гомографии плоскости z = 0 из проекций опорных точек (перспектива камеры учтена);
  • обе картинки переносятся на одну сетку 20 px/ед. (x −12…12, y 0…20.2 — тело без головы);
  • IoU силуэтов тела, ширина в 20 горизонтальных срезах (по 1 ед. от пола),
    медианные цвета 22 зон тела и ΔE (CIE76), ΔE по всей картинке тела после размытия.
"""
import json
import os
import sys
import tempfile
import numpy as np
from PIL import Image, ImageFilter

REF_K, REF_CX, REF_FY = 22.0, 246.0, 731.0      # лист 2: px на единицу, центр, пол
R = 20                                           # px на единицу общей сетки
X0, X1, YTOP = -11.3, 11.3, 20.2                 # окно тела в мире (справа дальше — уже вид 3/4 листа)

# зоны (x0, x1, y0, y1[, 'orange']) — левая половина фигуры (от зрителя), плоские места
ZONES = {
    'грудь белая':        (-1.0, 1.0, 15.5, 16.8),
    'грудь верх':         (-1.0, 1.0, 18.0, 19.0),
    'грудь край':         (-4.5, -3.9, 17.2, 17.9),
    'серый пояс':         (-1.0, 1.0, 14.2, 14.8),
    'тёмный пояс':        (-2.0, 2.0, 13.3, 13.7),
    'живот бока':         (-3.0, -2.0, 12.6, 13.1),
    'живот середина':     (-0.8, 0.8, 12.4, 13.0),
    'таз':                (-1.8, -1.2, 11.5, 12.1),
    'промежность':        (-0.6, 0.6, 10.2, 10.9),
    'бедро':              (-4.0, -3.2, 8.4, 9.4),
    'бедро верх':         (-4.5, -3.9, 10.75, 11.0),
    'бедро оранжевое':    (-5.6, -4.9, 9.0, 10.2, 'orange'),
    'колено':             (-4.2, -3.6, 6.8, 7.2),
    'голень':             (-4.8, -3.8, 4.2, 5.5),
    'ступня верх':        (-7.0, -5.2, 1.5, 2.4, 'orange'),
    'подошва':            (-6.0, -3.0, 0.3, 0.9),
    'стойка голеностопа': (-6.6, -5.6, 3.0, 4.2, 'orange'),
    'наплечник тёмный':   (-6.4, -5.2, 16.4, 17.0),
    'наплечник белый':    (-8.8, -7.4, 17.5, 18.5),
    'наруч серый':        (-7.8, -6.2, 11.5, 14.0),
    'наруч белый':        (-9.8, -8.6, 11.3, 12.3),
    'кулак':              (-8.6, -7.4, 10.1, 10.7),
}


def figure_mask(a):
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    bg = (b > r + 20) & (g > r + 5) & (b >= g - 5)
    return ~bg


def grid_affine():
    """Пиксель сетки (i, j) → мир (X, Y)."""
    return np.array([[1 / R, 0, X0], [0, -1 / R, YTOP], [0, 0, 1]])


def warp(img, M):
    """Перенос картинки на сетку мира: M — матрица (i, j, 1) → пиксель картинки."""
    w, h = round((X1 - X0) * R), round(YTOP * R)
    M = M / M[2, 2]
    coef = (M[0, 0], M[0, 1], M[0, 2], M[1, 0], M[1, 1], M[1, 2], M[2, 0], M[2, 1])
    out = img.transform((w, h), Image.PERSPECTIVE, coef, resample=Image.BILINEAR, fillcolor=(168, 211, 242))
    return np.asarray(out).astype(int)


def homography(world, pix):
    """DLT по парам (X, Y) → (x, y) пикселя."""
    A = []
    for (X, Y), (x, y) in zip(world, pix):
        A.append([X, Y, 1, 0, 0, 0, -x * X, -x * Y, -x])
        A.append([0, 0, 0, X, Y, 1, -y * X, -y * Y, -y])
    _, _, vt = np.linalg.svd(np.array(A, float))
    return vt[-1].reshape(3, 3)


def lab(rgb):
    c = np.asarray(rgb, float) / 255
    c = np.where(c > 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)
    M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = M @ c / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.array([116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])])


def zone_color(a, box):
    x0, x1, y0, y1 = box[:4]
    c0, c1 = int((x0 - X0) * R), int((x1 - X0) * R)
    r0, r1 = int((YTOP - y1) * R), int((YTOP - y0) * R)
    px = a[r0:r1, c0:c1].reshape(-1, 3)
    if len(box) > 4:
        r, g, b = px[:, 0], px[:, 1], px[:, 2]
        sel = (r > 140) & (r - b > 80)
        if sel.sum() > 8:
            px = px[sel]
    return np.median(px, axis=0)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    ref_path = args[0]
    ren_path = args[1] if len(args) > 1 else 'renders/qa.png'
    anc = json.load(open(os.path.splitext(ren_path)[0] + '.json'))
    ref = Image.open(ref_path).convert('RGB')
    ren = Image.open(ren_path).convert('RGB')
    world, pix = [], []
    for k, v in anc.items():
        X, Y = (float(t) for t in k[1:].split('_'))
        world.append((X, Y)); pix.append(v)
    RA = warp(ref, np.array([[REF_K, 0, REF_CX], [0, -REF_K, REF_FY], [0, 0, 1]]) @ grid_affine())
    MA = warp(ren, homography(world, pix) @ grid_affine())
    RM, MM = figure_mask(RA), figure_mask(MA)
    iou = (RM & MM).sum() / (RM | MM).sum()
    print(f'IoU тела: {iou:.3f}')
    print(' y      образец  модель  разница (ед. ширины)')
    bad = 0
    H = RM.shape[0]
    for i in range(20):
        yw = i + 0.5
        row = int((YTOP - yw) * R)
        rw, mw = RM[row].sum() / R, MM[row].sum() / R
        flag = '  <<' if abs(rw - mw) > 1.2 else ''
        bad += bool(flag)
        print(f'{yw:5.1f}  {rw:7.2f}  {mw:7.2f}  {mw - rw:+6.2f}{flag}')
    print('зона                  образец   модель    ΔE')
    des = []
    for name, box in ZONES.items():
        rc, mc = zone_color(RA, box), zone_color(MA, box)
        de = float(np.linalg.norm(lab(rc) - lab(mc)))
        des.append(de)
        print(f'{name:20s}  #{int(rc[0]):02x}{int(rc[1]):02x}{int(rc[2]):02x}  #{int(mc[0]):02x}{int(mc[1]):02x}{int(mc[2]):02x}  {de:5.1f}')
    both = RM & MM
    rb = np.asarray(Image.fromarray(RA.astype(np.uint8)).filter(ImageFilter.GaussianBlur(3))).astype(int)[both]
    mb = np.asarray(Image.fromarray(MA.astype(np.uint8)).filter(ImageFilter.GaussianBlur(3))).astype(int)[both]
    idx = np.linspace(0, len(rb) - 1, min(len(rb), 6000)).astype(int)
    ide = float(np.mean([np.linalg.norm(lab(rb[i]) - lab(mb[i])) for i in idx]))
    print(f'ИТОГ тело  IoU {iou:.3f} · плохих срезов {bad}/20 · ΔE зон ср. {np.mean(des):.1f} · макс. {np.max(des):.1f} · ΔE по картинке {ide:.1f}')
    out = tempfile.gettempdir()
    Image.fromarray(np.concatenate([RA, MA], axis=1).astype(np.uint8)).save(os.path.join(out, 'qa_body_side.png'))
    over = np.zeros(RM.shape + (3,), np.uint8)
    over[RM & ~MM] = (230, 60, 60)      # есть на листе, нет у модели
    over[MM & ~RM] = (60, 90, 230)      # лишнее у модели
    over[RM & MM] = (200, 200, 200)
    Image.fromarray(over).save(os.path.join(out, 'qa_body_sil.png'))
    print(f'картинки: {out}/qa_body_side.png, {out}/qa_body_sil.png')


if __name__ == '__main__':
    main()
