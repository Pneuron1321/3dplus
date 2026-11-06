"""Мера похожести: силуэт рендера против силуэта вида на листе (IoU), рамка к рамке, по каждому виду.

python3 tools/forma/metrika.py папка_видов/ рендеры/ [пары вид:рендер ...]
По умолчанию: front:hfront-dbg back:hback-dbg left:hleft-dbg v34:h34-dbg. Фон рендера — однотонный (угол кадра).
Зеркальное положение вида не важно: берётся лучшее из прямого и зеркального.
"""
import sys, json
import numpy as np
from PIL import Image
from scipy import ndimage as nd
D, RD = sys.argv[1], sys.argv[2]
pary = [p.split(':') for p in (sys.argv[3:] or ['front:hfront-dbg', 'back:hback-dbg', 'left:hleft-dbg', 'v34:h34-dbg'])]
def rama(m, n=128):
    ys, xs = np.where(m)
    c = m[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
    return np.asarray(Image.fromarray(c.astype(np.uint8) * 255).resize((n, int(n * c.shape[0] / c.shape[1])))) > 127, c.shape[1] / c.shape[0]
itog = {}
for v, r in pary:
    lm = np.asarray(Image.open(f'{D}/vid-{v}.png'))[..., 3] > 128
    a = np.asarray(Image.open(f'{RD}/{r}.png').convert('RGB')).astype(int)
    rm = np.abs(a - a[2, 2]).max(2) > 18
    rm = nd.binary_fill_holes(nd.binary_opening(rm, iterations=1))
    # шея-штырь рендера ниже подбородка — срезать по самой узкой строке, как у листа
    ys, xs = np.where(rm); y0, y1 = ys.min(), ys.max(); wid = rm.sum(1)
    lo = y0 + int((y1 - y0) * 0.75); neck = lo + int(np.argmin(wid[lo:y1 + 1]))
    rm[neck:] = False
    A, ra = rama(lm); best = 0
    for flip in (False, True):
        B, rb = rama(rm[:, ::-1] if flip else rm)
        h = min(A.shape[0], B.shape[0])
        iou = (A[:h] & B[:h]).sum() / max(1, (A[:h] | B[:h]).sum())
        best = max(best, iou)
    itog[v] = {'IoU': round(float(best), 3), 'ш/в лист': round(ra, 3), 'ш/в рендер': round(rb, 3)}
    print(f'{v:6s} IoU {best:.3f}   ширина/высота: лист {ra:.2f}, рендер {rb:.2f}')
json.dump(itog, open(f'{D}/metrika.json', 'w'), ensure_ascii=False, indent=1)
