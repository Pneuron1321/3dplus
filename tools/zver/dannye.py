import sys, base64, json
from PIL import Image
import numpy as np
from scipy import ndimage as nd
S, OUT = sys.argv[1], sys.argv[2]
def vid(path, cut=None):
    a = np.asarray(Image.open(path).convert('RGBA')).copy()
    m = a[..., 3] > 128
    if cut: cut(m)
    m = nd.binary_fill_holes(m)
    a[..., 3] = np.where(m, a[..., 3], 0)
    vn = nd.distance_transform_edt(m); vne = nd.distance_transform_edt(~m)
    sd = np.clip(np.where(m, -vn, vne), -60, 60).round().astype(np.int8)       # знак: внутри минус, в точках листа
    h, w = m.shape
    return {'w': w, 'h': h, 'rgba': base64.b64encode(a.tobytes()).decode(), 'sd': base64.b64encode(sd.tobytes()).decode()}
def bez_shei(m):
    h, w = m.shape
    yy, xx = np.mgrid[0:h, 0:w]
    m[(xx > 92) & (yy > 205 - (xx - 92) * 0.82)] = False                      # шея и плечо — не голова: срез наискось к затылку
    m[yy > 205] = False
import os
FRONT = os.environ.get('ZV_FRONT', S + '/zlico/vyrez.png')
front = vid(FRONT)
side = vid(S + '/zlico/bok.png', bez_shei)
d = {'front': front, 'side': side,
     # привязка: фронт — центр x 105, подбородок y 205, глаз 111; профиль — нос x 5, подбородок 198, глаз 113 (×1.106)
     'F': {'cx': float(os.environ.get('ZV_CX', 105)), 'chin': float(os.environ.get('ZV_CHIN', 205))},
     'B': {'nos': 5, 'chin': 198, 'k': float(os.environ.get('ZV_EYECHIN', 94)) / 85},
     'U': float(os.environ.get('ZV_U', 0.062))}
js = ('/* Рисунок зверя с листа Александра (вырезка спереди и сбоку) — данные для src/zver-golova.js: RGBA и\n'
      '   поле расстояний до силуэта (точки листа, int8). Сгенерировано из docs/maket/ref-zver-list.jpg. */\n'
      '(function (g) { g.PixelMechZverRisunok = ' + json.dumps(d) + '; })(typeof window !== "undefined" ? window : globalThis);\n')
open(OUT, 'w').write(js); print(len(js) // 1024, 'КБ')
