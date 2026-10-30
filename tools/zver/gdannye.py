import sys, base64, json
from PIL import Image, ImageDraw
import numpy as np
from scipy import ndimage as nd
import os
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории
S, OUT = sys.argv[1], sys.argv[2]
SX = float(sys.argv[3]) if len(sys.argv) > 3 else 1.25
im = Image.open(MAKET + "/ref-zver-2.jpg").convert("RGB")
W, H = im.size
D = np.load(S + "/zv2/glubina.npy").astype(np.float32)
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
from maska import maska
thr = float(sys.argv[4]) if len(sys.argv) > 4 else 5.0
m = maska(D, W, H, thr)
a = np.asarray(im).copy()
X0, Y0, X1, Y1 = 40, 0, 600, 740
def kad(arr, mode):
    c = Image.fromarray(arr[Y0:Y1, X0:X1], mode)
    w, h = round((X1 - X0) * SX * 0.5), round((Y1 - Y0) * 0.5)
    return c.resize((w, h), Image.LANCZOS if mode != 'F' else Image.BILINEAR)
rgb = np.asarray(kad(a, 'RGB'))
mk = np.asarray(kad((m * 255).astype(np.uint8), 'L')) > 127
dz = np.asarray(kad(D, 'F'))
h, w = mk.shape
vn = nd.distance_transform_edt(mk); vne = nd.distance_transform_edt(~mk)
sd = np.clip(np.where(mk, -vn, vne), -60, 60).round().astype(np.int8)
# глубина: от уровня края силуэта, сглаженная; вне — продолжение ближайшим значением
dmin = np.percentile(dz[mk], 3)
z = np.clip(dz - dmin, 0, None)
ind = nd.distance_transform_edt(~mk, return_distances=False, return_indices=True)
z = z[ind[0], ind[1]]
z = nd.gaussian_filter(z, 1.0)
z = (z / z.max() * 250).round().astype(np.uint8)
rgba = np.dstack([rgb, mk.astype(np.uint8) * 255])
cx = (222 - X0) * SX * 0.5; chin = (715 - Y0) * 0.5
d = {'w': w, 'h': h, 'rgba': base64.b64encode(rgba.tobytes()).decode(), 'sd': base64.b64encode(sd.tobytes()).decode(),
     'z': base64.b64encode(z.tobytes()).decode(), 'cx': cx, 'chin': chin}
js = ('/* Зверь с крупного плана (ref-zver-2): RGBA, поле расстояний до силуэта (int8) и глубина по рисунку (uint8,\n'
      '   нейросеть глубины Depth Anything V2) — для src/zver-golova.js. Лицо шире ×' + str(SX) + '. */\n'
      '(function (g) { g.PixelMechZverGlubina = ' + json.dumps(d) + '; })(typeof window !== "undefined" ? window : globalThis);\n')
open(OUT, 'w').write(js); print(len(js) // 1024, 'КБ', w, h, 'cx', cx, 'chin', chin)
pv = np.dstack([rgb * mk[..., None], np.zeros_like(mk)])[..., :3]
Image.fromarray(np.hstack([pv.astype(np.uint8), np.repeat(z[..., None], 3, 2)])).save(S + '/zv2/gd-prev.png')
