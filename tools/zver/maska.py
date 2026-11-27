import sys
from PIL import Image, ImageDraw
import numpy as np
from scipy import ndimage as nd
import os
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории
def maska(D, W, H, thr=5.0):
    """Голова целиком: глубина отделяет от фона, ломаная — от плеч и тела (снизу и справа)."""
    poly = [(60,0),(600,0),(600,330),(560,430),(510,520),(450,600),(400,660),(345,722),(260,735),(170,715),(120,680),(60,600)]
    pm = Image.new('L', (W, H), 0); ImageDraw.Draw(pm).polygon(poly, fill=255); pm = np.asarray(pm) > 0
    m = pm & (D > thr)
    m = nd.binary_opening(m, iterations=2)
    lab, n = nd.label(m); sz = nd.sum(m, lab, range(1, n + 1)); m = lab == (1 + int(np.argmax(sz)))
    return nd.binary_fill_holes(m)
if __name__ == '__main__':
    S = sys.argv[1]
    im = np.asarray(Image.open(MAKET + "/ref-zver-2.jpg").convert("RGB")).astype(float)
    D = np.load(S + "/zv2/glubina.npy")
    outs = []
    for t in [4.6, 5.0, 5.4]:
        m = maska(D, im.shape[1], im.shape[0], t)[..., None]; outs.append((im * (0.2 + 0.8 * m)).astype(np.uint8))
    Image.fromarray(np.hstack(outs)).resize((3 * 655 * 2 // 3, 840 * 2 // 3)).save(S + '/zv2/maska-prev.png')
