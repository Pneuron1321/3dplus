"""Плюс целиком из генератора 3D сразу с текстурой (30.10): Hunyuan3D-2.1 /generation_all по 5 видам листа тела.

python3.13 tools/forma/hy21.py лист.png папка/        (HF_TOKEN — в окружении; лист — quest-claude docs/maket/plus-telo-list.png)
python3.13 tools/forma/hy21.py виды/ папка/ [--imya plus-hy21-v2]   (виды/ — front, left, back, right, v34 .png крупно,
                                                                   quest-claude docs/maket/vidy-30-10/)

Виды вырезаются из листа (спереди, слева, сзади, справа, 3/4), вписываются в квадрат 1024 на белом. Главная картинка —
3/4, остальные — виды по сторонам. Генератор сам совмещает цвет с формой (текстура 2048, 40 тыс. треугольников, ~2 мин).
Материал генератора металлический (белое выходит серебром) — ставим матовый пластик: metallic 0, roughness 0.85.
Выход: папка/plus-hy21.glb.
"""
import os, sys, shutil
from PIL import Image
from gradio_client import Client, handle_file
import trimesh

LIST, D = sys.argv[1], sys.argv[2]
IMYA = sys.argv[sys.argv.index('--imya') + 1] if '--imya' in sys.argv else 'plus-hy21'
os.makedirs(D, exist_ok=True)
VIDY = {'front': (15, 42, 288, 530), 'left': (286, 42, 460, 530), 'back': (462, 42, 718, 530), 'right': (722, 42, 905, 530),
        'v34': (915, 42, 1212, 530)}                      # рамки видов на листе 1215×1295

def kvadrat(c, fon):
    s = 1024 / max(c.size)
    c = c.resize((int(c.width * s), int(c.height * s)), Image.LANCZOS)
    o = Image.new('RGB', (1024, 1024), fon); o.paste(c, ((1024 - c.width) // 2, (1024 - c.height) // 2))
    return o

if os.path.isdir(LIST):
    # крупные виды (30.10): фигура по рамке «не фон» (фон ровный серый — цвет угла), поле 3 %
    import numpy as np
    for k in VIDY:
        im = Image.open(os.path.join(LIST, k + '.png')).convert('RGB'); a = np.asarray(im).astype(int)
        fon = a[:20, :20].reshape(-1, 3).mean(0)
        ys, xs = np.nonzero(np.abs(a - fon).sum(2) > 40)
        pole = int(0.03 * (ys.max() - ys.min()))
        c = im.crop((max(xs.min() - pole, 0), max(ys.min() - pole, 0), min(xs.max() + pole, im.width), min(ys.max() + pole, im.height)))
        kvadrat(c, tuple(int(x) for x in fon)).save(os.path.join(D, k + '.png'))
else:
    L = Image.open(LIST).convert('RGB')
    for k, b in VIDY.items():
        kvadrat(L.crop(b), (255, 255, 255)).save(os.path.join(D, k + '.png'))
p = lambda k: handle_file(os.path.join(D, k + '.png'))
r = Client('tencent/Hunyuan3D-2.1', token=os.environ['HF_TOKEN'], verbose=False).predict(
    image=p('v34'), mv_image_front=p('front'), mv_image_back=p('back'), mv_image_left=p('left'), mv_image_right=p('right'),
    steps=30, guidance_scale=5.0, seed=1234, octree_resolution=256, check_box_rembg=True, num_chunks=8000,
    randomize_seed=False, api_name='/generation_all')
glb = next(x['value'] for x in r if isinstance(x, dict) and str(x.get('value', '')).endswith('.glb'))
s = trimesh.load(glb)
for g in s.geometry.values():
    m = g.visual.material
    m.metallicFactor, m.roughnessFactor, m.metallicRoughnessTexture = 0.0, 0.85, None
s.export(os.path.join(D, IMYA + '.glb'))
print(os.path.join(D, IMYA + '.glb'))
