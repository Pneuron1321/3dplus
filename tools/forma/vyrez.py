"""Вырезка детали из фона нейросетью isnet-general-use (rembg, ONNX, процессор) — для белых деталей на светлом листе.

python3 tools/forma/vyrez.py модель.onnx картинка.png маска.png
"""
import sys
import numpy as np
import onnxruntime as ort
from PIL import Image
mod, src, out = sys.argv[1:4]
im = Image.open(src).convert('RGB'); W, H = im.size
s = ort.InferenceSession(mod, providers=['CPUExecutionProvider']); i = s.get_inputs()[0]
x = np.asarray(im.resize((1024, 1024), Image.BILINEAR)).astype(np.float32) / 255
x = ((x - [0.5, 0.5, 0.5]) / [1.0, 1.0, 1.0]).transpose(2, 0, 1)[None].astype(np.float32)
o = np.squeeze(s.run(None, {i.name: x})[0]); o = (o - o.min()) / (o.max() - o.min() + 1e-6)
Image.fromarray((o * 255).astype(np.uint8)).resize((W, H), Image.BILINEAR).save(out)
