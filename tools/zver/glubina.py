import sys
import numpy as np
import onnxruntime as ort
from PIL import Image
import os
MAKET = os.environ.get('ZV_MAKET', '/home/user/quest-claude/docs/maket')   # рисунки Александра — только в закрытом репозитории
S = sys.argv[1]
src = Image.open(MAKET + "/ref-zver-2.jpg").convert("RGB")
W, H = src.size
a = np.asarray(src).astype(np.float32) / 255
# глубина: Depth Anything V2 (относительная обратная глубина: больше — ближе)
d = ort.InferenceSession(S + "/modeli/depth_anything_v2_vitb.onnx", providers=["CPUExecutionProvider"])
inp = d.get_inputs()[0]; print('depth in', inp.name, inp.shape)
sz = 518
x = np.asarray(src.resize((sz // 14 * 14 if False else 518, 672), Image.BICUBIC)).astype(np.float32) / 255
x = (x - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
x = x.transpose(2, 0, 1)[None].astype(np.float32)
try:
    o = d.run(None, {inp.name: x})[0]
except Exception as e:
    print('нужен квадрат:', e); x2 = np.asarray(src.resize((518, 518), Image.BICUBIC)).astype(np.float32) / 255
    x2 = ((x2 - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]).transpose(2, 0, 1)[None].astype(np.float32); o = d.run(None, {inp.name: x2})[0]
o = np.squeeze(o); print('depth out', o.shape, o.min(), o.max())
dep = np.asarray(Image.fromarray(o.astype(np.float32)).resize((W, H), Image.BICUBIC))
np.save(S + "/zv2/glubina.npy", dep)
v = (dep - dep.min()) / (dep.max() - dep.min()); Image.fromarray((v * 255).astype(np.uint8)).save(S + "/zv2/glubina.png")
# маска персонажа: isnet-anime
m = ort.InferenceSession(S + "/modeli/isnet-anime.onnx", providers=["CPUExecutionProvider"])
mi = m.get_inputs()[0]; print('mask in', mi.name, mi.shape)
y = np.asarray(src.resize((1024, 1024), Image.BILINEAR)).astype(np.float32) / 255
y = (y - [0.485, 0.456, 0.406]) / [1.0, 1.0, 1.0]
y = y.transpose(2, 0, 1)[None].astype(np.float32)
mo = np.squeeze(m.run(None, {mi.name: y})[0]); mo = (mo - mo.min()) / (mo.max() - mo.min())
mk = np.asarray(Image.fromarray((mo * 255).astype(np.uint8)).resize((W, H), Image.BILINEAR))
Image.fromarray(mk).save(S + "/zv2/maska-isnet.png")
