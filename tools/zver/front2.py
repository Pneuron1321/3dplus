import sys, colorsys
from PIL import Image, ImageDraw
import numpy as np
from scipy import ndimage as nd
import os
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории
S = sys.argv[1]
im = Image.open(MAKET + "/ref-zver-2.jpg").convert("RGB")
W, H = im.size
poly = [(185,175),(230,90),(300,55),(360,50),(430,85),(470,130),(490,210),(480,300),(470,380),(440,470),(400,560),
        (370,640),(330,700),(270,718),(200,708),(150,672),(128,612),(115,540),(95,470),(92,400),(96,330),(112,280),(150,238)]
pm = Image.new('L', (W, H), 0); ImageDraw.Draw(pm).polygon(poly, fill=255); pm = np.asarray(pm) > 0
a = np.asarray(im).astype(float) / 255
mx = a.max(2); mn = a.min(2); sat = (mx - mn) / np.maximum(mx, 1e-3)
r, g, b = a[..., 0], a[..., 1], a[..., 2]
yy, xx = np.mgrid[0:H, 0:W]
bezh = (sat > 0.2) & (mx > 0.6) & (r > g * 1.04) & (g > b * 1.15) & (r - b > 0.2)           # жёлто-бежевый фон
kras = (yy < 240) & (r > g * 1.3) & (sat > 0.35)                                             # красное кольцо за гребнем
m = pm & ~bezh & ~kras
m = nd.binary_opening(m, iterations=1)
lab, n = nd.label(m); sz = nd.sum(m, lab, range(1, n + 1)); m = lab == (1 + int(np.argmax(sz)))
m = nd.binary_fill_holes(m)
rgba = np.dstack([np.asarray(im), (nd.gaussian_filter(m.astype(float), 0.8) * 255)]).astype(np.uint8)
C = 222
crop = Image.fromarray(rgba[40:726, 80:500], 'RGBA')                                         # голова целиком, как на картинке
SX = float(sys.argv[2]) if len(sys.argv) > 2 else 1.25                                      # лицо шире (просьба Александра)
both = crop.resize((round(crop.width * SX), crop.height), Image.LANCZOS)
fin = both.resize((round(both.width * 0.5), round(both.height * 0.5)), Image.LANCZOS)
fin.save(S + "/zv2/front2.png"); print('front2', fin.size, 'cx', (C - 80) * SX * 0.5, 'chin', (715 - 40) * 0.5, 'eye', (400 - 40) * 0.5)
pv = Image.new('RGB', fin.size, (30, 60, 120)); pv.paste(fin, (0, 0), fin); pv.resize((fin.width * 2, fin.height * 2), Image.LANCZOS).save(S + "/zv2/front2-prev.png")
