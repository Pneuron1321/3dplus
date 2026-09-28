"""Сверка рендера с листом-образцом: силуэт, профиль ширины, цвета по зонам.

Запуск:  python3 tools/qa.py <образец.jpg> [renders/front.png]
Нужны Pillow и numpy. Образец — исходный лист 1312×1199 (вид спереди берётся из него по координатам).

Что считает:
  • IoU силуэтов (фон — голубой) после совмещения по габаритной рамке;
  • ширину фигуры в 24 горизонтальных срезах (доли высоты) — где модель шире/уже образца;
  • медианные цвета 16 зон (лицо, линза, шапка, пришелец, грудь…) и ΔE (CIE76) между образцом и рендером.
"""
import os
import sys
import tempfile
import numpy as np
from PIL import Image

REF_FRONT_BOX = (0, 80, 445, 560)          # вид спереди на листе-образце

# зоны цвета в мировых единицах вида спереди (x0, x1, y0, y1): фигура высотой 28 от пола до шипов рожек,
# центр по голове. Брать плоские места без шипов и швов.
FIG_H = 28.0
ZONES = {
    'лицо (жёлтый)':        (2.6, 3.6, 18.6, 19.5),
    'линза очков':          (-4.4, -3.2, 22.6, 23.4),
    'шапка (белый)':        (-1.5, 1.5, 25.0, 25.5),
    'пришелец (фиолет.)':   (-1.0, 1.0, 26.7, 27.0),
    'ухо (оранжевое)':      (-7.0, -5.6, 19.8, 24.0, 'orange'),   # тонкое в анфас: берём только оранжевые пиксели
    'боковая плита':        (-5.9, -5.3, 19.0, 19.8),
    'грудь светло-серая':   (-3.8, -2.6, 15.35, 15.65),
    'грудь белая':          (-1.2, 1.2, 12.9, 13.9),
    'пояс':                 (-3.0, 3.0, 10.6, 10.9),
    'таз тёмно-серый':      (-2.5, 2.5, 8.8, 9.4),
    'плечо тёмное':         (-7.0, -6.0, 14.3, 15.0),
    'кулак светлый':        (-11.4, -8.6, 10.2, 12.8),
    'голень':               (-5.4, -4.2, 5.8, 6.6),
    'ступня низ':           (-6.2, -4.4, 0.4, 1.0),
    'ступня середина':      (-7.0, -3.4, 1.9, 2.3),
    'ступня верх':          (-6.6, -3.8, 2.6, 3.6),
    'шея':                  (-0.8, 0.8, 16.6, 17.0),
}


def load_rgb(path, box=None):
    im = Image.open(path).convert('RGB')
    if box:
        im = im.crop(box)
    return np.asarray(im).astype(int)


def figure_mask(a):
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    bg = (b > r + 20) & (g > r + 5) & (b >= g - 5)          # голубой фон и тени на нём
    return ~bg


def bbox(m):
    ys, xs = np.where(m)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def normalize(a, m, H=460, W=460):
    """Масштаб по высоте фигуры (без искажения), центр — по голове (верхние 35% рамки)."""
    x0, y0, x1, y1 = bbox(m)
    k = H / (y1 - y0)
    head = m[y0:y0 + int(0.35 * (y1 - y0))]
    cx = np.where(head)[1].mean()
    im_a = Image.fromarray(a.astype(np.uint8)).resize((round(a.shape[1] * k), round(a.shape[0] * k)), Image.LANCZOS)
    im_m = Image.fromarray((m * 255).astype(np.uint8)).resize(im_a.size, Image.NEAREST)
    left, top = round(cx * k - W / 2), round(y0 * k)
    box = (left, top, left + W, top + H)
    bg = Image.new('RGB', (W, H), (168, 211, 242)); bg.paste(im_a.crop(box), (0, 0))
    mk = Image.new('L', (W, H), 0); mk.paste(im_m.crop(box), (0, 0))
    return np.asarray(bg).astype(int), np.asarray(mk) > 127


def lab(rgb):
    c = np.asarray(rgb, float) / 255
    c = np.where(c > 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)
    M = np.array([[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]])
    xyz = M @ c / np.array([0.95047, 1.0, 1.08883])
    f = np.where(xyz > 0.008856, np.cbrt(xyz), 7.787 * xyz + 16 / 116)
    return np.array([116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])])


def zone_color(a, box):
    h, w = a.shape[:2]
    k = FIG_H / h
    x0, x1, y0, y1 = box[:4]
    c0, c1 = int(w / 2 + x0 / k), int(w / 2 + x1 / k)
    r0, r1 = int((FIG_H - y1) / k), int((FIG_H - y0) / k)
    px = a[r0:max(r1, r0 + 1), c0:max(c1, c0 + 1)].reshape(-1, 3)
    if len(box) > 4 and box[4] == 'orange':
        r, g, b = px[:, 0], px[:, 1], px[:, 2]
        sel = (r > 120) & (r - b > 80) & (g < r - 30)
        if sel.sum() > 10:
            px = px[sel]
    return np.median(px, axis=0)


def main():
    ref_path = sys.argv[1]
    ren_path = sys.argv[2] if len(sys.argv) > 2 else 'renders/front.png'
    ra = load_rgb(ref_path, REF_FRONT_BOX)
    rm = figure_mask(ra)
    ma = load_rgb(ren_path)
    mm = figure_mask(ma)
    rx = bbox(rm); mx = bbox(mm)
    print(f'рамка образца {rx[2]-rx[0]}×{rx[3]-rx[1]} (ш/в {(rx[2]-rx[0])/(rx[3]-rx[1]):.3f}), '
          f'модели {mx[2]-mx[0]}×{mx[3]-mx[1]} (ш/в {(mx[2]-mx[0])/(mx[3]-mx[1]):.3f})')
    RA, RM = normalize(ra, rm)
    MA, MM = normalize(ma, mm)
    iou = (RM & MM).sum() / (RM | MM).sum()
    print(f'IoU силуэтов: {iou:.3f}')
    print('срез  образец  модель  разница (доли ширины кадра)')
    H, W = RM.shape
    for i in range(24):
        y = int((i + 0.5) / 24 * H)
        rw, mw = RM[y].sum() / W, MM[y].sum() / W
        flag = '  <<' if abs(rw - mw) > 0.06 else ''
        print(f'{(i + 0.5) / 24:4.2f}  {rw:6.3f}  {mw:6.3f}  {mw - rw:+.3f}{flag}')
    print('зона                    образец        модель         ΔE')
    des = []
    for name, box in ZONES.items():
        rc, mc = zone_color(RA, box), zone_color(MA, box)
        de = float(np.linalg.norm(lab(rc) - lab(mc)))
        des.append(de)
        hexr = '#%02x%02x%02x' % tuple(int(v) for v in rc)
        hexm = '#%02x%02x%02x' % tuple(int(v) for v in mc)
        print(f'{name:22s}  {hexr}  {hexm}  {de:5.1f}')
    print(f'средний ΔE: {np.mean(des):.1f}   худший: {np.max(des):.1f}')
    bad = sum(1 for i in range(24) if abs(RM[int((i + 0.5) / 24 * H)].sum() / W - MM[int((i + 0.5) / 24 * H)].sum() / W) > 0.06)
    print(f'ИТОГ  IoU {iou:.3f} · плохих срезов {bad}/24 · ΔE ср. {np.mean(des):.1f} · ΔE макс. {np.max(des):.1f}')
    out = tempfile.gettempdir()
    Image.fromarray(np.concatenate([RA, MA], axis=1).astype(np.uint8)).save(os.path.join(out, 'qa_side_by_side.png'))
    over = np.zeros((H, W, 3), np.uint8)
    over[RM & ~MM] = (230, 60, 60)      # есть в образце, нет в модели
    over[MM & ~RM] = (60, 90, 230)      # лишнее у модели
    over[RM & MM] = (200, 200, 200)
    Image.fromarray(over).save(os.path.join(out, 'qa_silhouette.png'))
    print(f'картинки сверки: {out}/qa_side_by_side.png, {out}/qa_silhouette.png')


if __name__ == '__main__':
    main()
