"""Кожа зверя целиком (затылок, бока, шея) — без генератора текстуры. Данные для src/zver-golova.js (opts.zver4).

python3 tools/zver/kozha.py S src/zver-setka.js src/zver-kozha.js [треугольников=50000] [атлас=2048]

Берёт готовую сетку «Зверя-3» (src/zver-setka.js: сетка Hunyuan3D-2, совмещённая с рисунком) и:
1. облегчает её для Quest (fast_simplification);
2. разворачивает в атлас (xatlas);
3. запекает цвет в атлас: лицо и всё, что видно на рисунке, — сам рисунок; затылок, бока и скрытые места —
   «кожа», собранная из кусочков того же рисунка (плечо, тело, лоб) в бесшовную плитку и наложенная с трёх
   сторон (triplanar). Шов между ними мягкий: вес рисунка сглажен по сетке и гаснет на скатах от зрителя;
4. плитку кожи кладёт в данные отдельно — ею же покрыта шея-трапеции.
Проверка: S/hy/kozha-atlas.png, S/hy/kozha-plitka.png.
"""
import sys, os, json, base64, io, re
import numpy as np
import fast_simplification as fs, xatlas
from PIL import Image
from scipy import ndimage as nd
from scipy.sparse import coo_matrix
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from maska import maska
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории

S, IN, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
N = int(sys.argv[4]) if len(sys.argv) > 4 else 50000
A = int(sys.argv[5]) if len(sys.argv) > 5 else 2048
rng = np.random.default_rng(7)

# ---------- сетка «Зверя-3» ----------
Z = json.loads(re.search(r'PixelMechZverSetka = (\{.*\});', open(IN).read()).group(1))
dek = lambda k, t: np.frombuffer(base64.b64decode(Z[k]), t)
n = Z['n']; lo, hi = np.array(Z['lo']), np.array(Z['hi'])
q = lo + dek('pos', np.uint16).reshape(n, 3) / 65535 * (hi - lo)
uv0 = dek('uv', np.uint16).reshape(n, 2) / 65535
vis0 = dek('ten', np.uint8) == 255
f0 = dek('idx', np.uint32).reshape(-1, 3).astype(np.int64)
tex = dek('tex', np.uint8).reshape(Z['th'], Z['tw'], 3).astype(np.float32) / 255   # строки снизу вверх
TH, TW = tex.shape[:2]
# камера рисунка: u, v текстуры линейны по x, y головы (ортографическая проекция вдоль z)
ax, bx = np.polyfit(q[vis0, 0], uv0[vis0, 0], 1); ay, by = np.polyfit(q[vis0, 1], uv0[vis0, 1], 1)
print('камера: невязка u %.4f v %.4f' % (np.abs(np.polyval([ax, bx], q[vis0, 0]) - uv0[vis0, 0]).max(),
                                          np.abs(np.polyval([ay, by], q[vis0, 1]) - uv0[vis0, 1]).max()))

# маска головы на рисунке → в координатах текстуры (та же рамка, что в hy-setka.py)
im = Image.open(MAKET + '/ref-zver-2.jpg').convert('RGB'); W, H = im.size
M = maska(np.load(S + '/zv2/glubina.npy'), W, H, 5.0)
ys, xs = np.where(M); X0, Y0 = max(0, xs.min() - 8), max(0, ys.min() - 8)
Mt = nd.binary_erosion(M, iterations=3)[Y0:Y0 + TH, X0:X0 + TW][::-1]
assert Mt.shape == (TH, TW), (Mt.shape, TH, TW)

# ---------- облегчение ----------
v, f = fs.simplify(np.ascontiguousarray(q, dtype=np.float32), np.ascontiguousarray(f0, dtype=np.int32), target_reduction=max(0, 1 - N / len(f0)))
v = v.astype(np.float64); f = f.astype(np.int64)
print('сетка', len(v), 'вершин', len(f), 'треугольников (было %d)' % len(f0))

def normali(v, f):
    fn = np.cross(v[f[:, 1]] - v[f[:, 0]], v[f[:, 2]] - v[f[:, 0]])
    vn = np.zeros_like(v)
    for k in range(3): np.add.at(vn, f[:, k], fn)
    return vn / (np.linalg.norm(vn, axis=1, keepdims=True) + 1e-12)
vn = normali(v, f)

def rastr(P, f, w, h, val):
    """растр треугольников P (в пикселях) с интерполяцией val (по вершинам); возвращает (пиксели, треугольник, барицентры)"""
    pix, tri, bar = [], [], []
    for i, t in enumerate(f):
        a, b, c = P[t[0]], P[t[1]], P[t[2]]
        x0, x1 = int(max(0, np.floor(min(a[0], b[0], c[0])))), int(min(w - 1, np.ceil(max(a[0], b[0], c[0]))))
        y0, y1 = int(max(0, np.floor(min(a[1], b[1], c[1])))), int(min(h - 1, np.ceil(max(a[1], b[1], c[1]))))
        if x1 < x0 or y1 < y0: continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(d) < 1e-12: continue
        l0 = ((b[1] - c[1]) * (gx - c[0]) + (c[0] - b[0]) * (gy - c[1])) / d
        l1 = ((c[1] - a[1]) * (gx - c[0]) + (a[0] - c[0]) * (gy - c[1])) / d
        l2 = 1 - l0 - l1
        ok = (l0 >= -1e-4) & (l1 >= -1e-4) & (l2 >= -1e-4)
        if not ok.any(): continue
        pix.append((gy[ok].astype(int) * w + gx[ok].astype(int))); tri.append(np.full(ok.sum(), i)); bar.append(np.stack([l0[ok], l1[ok], l2[ok]], 1))
    return np.concatenate(pix), np.concatenate(tri), np.concatenate(bar)

# ---------- видимость из камеры рисунка: z-буфер в координатах текстуры ----------
Pt = np.stack([(ax * v[:, 0] + bx) * TW, (ay * v[:, 1] + by) * TH], 1)
pix, tri, bar = rastr(Pt, f, TW, TH, None)
zz = (bar * v[f[tri], 2]).sum(1)
ZB = np.full(TW * TH, -1e9); np.maximum.at(ZB, pix, zz); ZB = ZB.reshape(TH, TW)

def proba(img, u, w):
    """билинейная выборка img (строки снизу вверх) в точках u, w ∈ [0,1]"""
    hh, ww = img.shape[:2]
    x = np.clip(u * ww - 0.5, 0, ww - 1.001); y = np.clip(w * hh - 0.5, 0, hh - 1.001)
    x0, y0 = x.astype(int), y.astype(int); fx, fy = (x - x0)[:, None], (y - y0)[:, None]
    I = img if img.ndim == 3 else img[..., None]
    r = (I[y0, x0] * (1 - fx) * (1 - fy) + I[y0, x0 + 1] * fx * (1 - fy) + I[y0 + 1, x0] * (1 - fx) * fy + I[y0 + 1, x0 + 1] * fx * fy)
    return r if img.ndim == 3 else r[:, 0]

# вес рисунка по вершинам: видна, смотрит на зрителя, внутри маски; потом сглаживание по сетке (мягкий шов)
pu, pv = ax * v[:, 0] + bx, ay * v[:, 1] + by
zb = proba(ZB, pu, pv)
vis = v[:, 2] >= zb - 1.0                                     # и чуть за видимым: пасть за зубами, складки — тоже с рисунка
smooth = lambda e0, e1, x: np.clip((x - e0) / (e1 - e0), 0, 1) ** 2 * (3 - 2 * np.clip((x - e0) / (e1 - e0), 0, 1))
wv = vis * smooth(-0.15, 0.3, vn[:, 2]) * (proba(Mt.astype(np.float32), pu, pv) > 0.5)
# пасть (красное и чёрное на рисунке) глубже за зубами — тоже с рисунка, без условия «смотрит на зрителя»
rc = proba(tex, pu, pv); lum = rc @ np.array([0.3, 0.59, 0.11])
past = ((rc[:, 0] > rc[:, 1] * 1.1) | (lum < 0.16)) & (proba(Mt.astype(np.float32), pu, pv) > 0.5)
wv = np.maximum(wv, past * smooth(3.0, 1.2, zb - v[:, 2]))
e = np.concatenate([f[:, [0, 1]], f[:, [1, 2]], f[:, [2, 0]]]); e = np.concatenate([e, e[:, ::-1]])
G = coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(len(v), len(v))).tocsr()
deg = np.asarray(G.sum(1)).ravel() + 1e-9
ws = wv.astype(np.float64)
for _ in range(6): ws = (G @ ws) / deg
wv = np.minimum(wv, ws) ** 0.8
print('вершин с рисунка %.0f%%' % (100 * (wv > 0.5).mean()))

# ---------- плитка кожи из кусочков рисунка (бесшовная) ----------
src = np.asarray(im).astype(np.float32) / 255
KUSKI = [(540, 330, 640, 470), (10, 700, 130, 830), (200, 250, 330, 320), (470, 620, 640, 720), (400, 760, 560, 830)]
PL, PS = 512, 56
pl = np.zeros((PL, PL, 3), np.float32); pw = np.zeros((PL, PL), np.float32)
yy, xx = np.mgrid[0:PS, 0:PS]; rr = np.hypot(yy - PS / 2 + 0.5, xx - PS / 2 + 0.5) / (PS / 2)
al = np.clip((1 - rr) / 0.35, 0, 1)
for _ in range(1400):                                         # заплатки поверх друг друга, края мягкие, по тору
    x0, y0, x1, y1 = KUSKI[rng.integers(len(KUSKI))]
    sx, sy = rng.integers(x0, x1 - PS), rng.integers(y0, y1 - PS)
    p = src[sy:sy + PS, sx:sx + PS]
    if rng.random() < 0.5: p = p[:, ::-1]
    k = rng.integers(4); p = np.rot90(p, k)
    tx, ty = rng.integers(PL), rng.integers(PL)
    iy, ix = (np.arange(PS) + ty) % PL, (np.arange(PS) + tx) % PL
    a = al[..., None]
    pl[np.ix_(iy, ix)] = pl[np.ix_(iy, ix)] * (1 - a) + p * a
    pw[np.ix_(iy, ix)] = pw[np.ix_(iy, ix)] * (1 - al) + al
pl = pl / np.maximum(pw, 1e-3)[..., None]
lin = lambda c: np.power(np.clip(c, 0, 1), 2.2); srgb = lambda c: np.power(np.clip(c, 0, 1), 1 / 2.2)

# ---------- развёртка ----------
# лицу — больше места в атласе: для развёртки лицевая часть раздута (до ×2.6 по площади), сетка та же
cen = v.mean(0); vr = cen + (v - cen) * (1 + 0.6 * wv)[:, None]
po = xatlas.PackOptions(); po.padding = 2; po.resolution = A
at_ = xatlas.Atlas(); at_.add_mesh(vr.astype(np.float32), f.astype(np.uint32)); at_.generate(pack_options=po)
vm, fa, uva = at_[0]
fa = fa.astype(np.int64); va, na = v[vm], vn[vm]; wa = wv[vm]
print('атлас: вершин', len(va))

# ---------- запекание ----------
pix, tri, bar = rastr(uva * A, fa, A, A, None)
P = (bar[..., None] * va[fa[tri]]).sum(1); Nn = (bar[..., None] * na[fa[tri]]).sum(1)
Nn /= np.linalg.norm(Nn, axis=1, keepdims=True) + 1e-12
Wt = (bar * wa[fa[tri]]).sum(1)
risunok = proba(tex, ax * P[:, 0] + bx, ay * P[:, 1] + by)
SC = 1 / 14.0                                                  # плитка ≈ 14 единиц головы: тот же масштаб, что на рисунке
bw = np.abs(Nn) ** 4; bw /= bw.sum(1, keepdims=True)
plf = pl[::-1]                                                 # строки снизу вверх, как текстура
tri3 = lambda a, b: proba(plf, (a * SC) % 1, (b * SC) % 1)
kozha = tri3(P[:, 2], P[:, 1]) * bw[:, :1] + tri3(P[:, 0], P[:, 2]) * bw[:, 1:2] + tri3(P[:, 0], P[:, 1]) * bw[:, 2:]
# подгонка тона кожи под рисунок у шва (линейный свет), затылок чуть темнее — тень от рисунка
sh = (Wt > 0.3) & (Wt < 0.9)
ton = lin(risunok[sh]).mean(0) / (lin(kozha[sh]).mean(0) + 1e-6) if sh.sum() > 100 else np.ones(3)
ton = np.clip(ton, 0.5, 2.0); print('тон кожи ×', np.round(ton, 3))
ten = 0.78 + 0.22 * np.clip(Nn @ np.array([0.3, 0.6, 0.74]), 0, 1)
kozha = srgb(lin(kozha) * ton * ten[:, None])
col = risunok * Wt[:, None] + kozha * (1 - Wt[:, None])
at = np.zeros((A * A, 3), np.float32); cnt = np.zeros(A * A)
np.add.at(at, pix, col); np.add.at(cnt, pix, 1)
fill = cnt > 0; at[fill] /= cnt[fill, None]
at = at.reshape(A, A, 3); fill = fill.reshape(A, A)
# поля вокруг островов: дотянуть ближайший цвет (против швов на мипмапах)
_, (iy, ix) = nd.distance_transform_edt(~fill, return_indices=True)
at = at[iy, ix]

def jpg(a, qual):
    b = io.BytesIO(); Image.fromarray((np.clip(a, 0, 1) * 255).round().astype(np.uint8)).save(b, 'JPEG', quality=qual); return base64.b64encode(b.getvalue()).decode()
Image.fromarray((np.clip(at[::-1], 0, 1) * 255).astype(np.uint8)).save(S + '/hy/kozha-atlas.png')
Image.fromarray((np.clip(pl, 0, 1) * 255).astype(np.uint8)).save(S + '/hy/kozha-plitka.png')

lo2, hi2 = va.min(0), va.max(0)
d = {'n': int(len(va)), 'lo': lo2.tolist(), 'hi': hi2.tolist(),
     'pos': base64.b64encode(np.round((va - lo2) / (hi2 - lo2) * 65535).astype(np.uint16).tobytes()).decode(),
     'uv': base64.b64encode(np.round(np.clip(uva, 0, 1) * 65535).astype(np.uint16).tobytes()).decode(),
     'idx': base64.b64encode(fa.astype(np.uint32).tobytes()).decode(),
     'atlas': jpg(at, 90), 'plitka': jpg(pl[::-1], 90), 'plitkaEd': 1 / SC, 'ton': ton.tolist()}
js = ('/* Голова зверя с кожей целиком: сетка «Зверя-3» (Hunyuan3D-2 по крупному плану), облегчённая для Quest, атлас:\n'
      '   лицо — рисунок Александра, затылок и бока — кожа из кусочков того же рисунка; плитка кожи — для шеи.\n'
      '   Картинки — JPEG: раскрываются в браузере (gotov), потом src/zver-golova.js (opts.zver4). tools/zver/kozha.py */\n'
      '(function (g) {\n  const d = g.PixelMechZverKozha = ' + json.dumps(d) + ';\n'
      '  d.gotov = typeof document === "undefined" ? Promise.resolve() : Promise.all(["atlas", "plitka"].map(k => new Promise((ok, err) => {\n'
      '    const im = new Image(); im.onerror = err;\n'
      '    im.onload = () => { const c = document.createElement("canvas"); c.width = im.width; c.height = im.height; const x = c.getContext("2d");\n'
      '      x.drawImage(im, 0, 0); d[k + "RGBA"] = x.getImageData(0, 0, im.width, im.height).data; d[k + "W"] = im.width; d[k + "H"] = im.height; ok(); };\n'
      '    im.src = "data:image/jpeg;base64," + d[k];\n  })));\n'
      '})(typeof window !== "undefined" ? window : globalThis);\n')
open(OUT, 'w').write(js); print(len(js) // 1024, 'КБ')
