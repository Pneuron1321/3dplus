"""Плюс целиком из генератора 3D сразу с текстурой (30.10): Hunyuan3D-2.1 /generation_all по 5 видам листа тела.

python3.13 tools/forma/hy21.py лист.png папка/        (HF_TOKEN — в окружении; лист — quest-claude docs/maket/plus-telo-list.png)

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
os.makedirs(D, exist_ok=True)
L = Image.open(LIST).convert('RGB')
VIDY = {'front': (15, 42, 288, 530), 'left': (286, 42, 460, 530), 'back': (462, 42, 718, 530), 'right': (722, 42, 905, 530),
        'v34': (915, 42, 1212, 530)}                      # рамки видов на листе 1215×1295
for k, b in VIDY.items():
    c = L.crop(b); s = 1024 / max(c.size)
    c = c.resize((int(c.width * s), int(c.height * s)), Image.LANCZOS)
    o = Image.new('RGB', (1024, 1024), (255, 255, 255)); o.paste(c, ((1024 - c.width) // 2, (1024 - c.height) // 2))
    o.save(os.path.join(D, k + '.png'))
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
s.export(os.path.join(D, 'plus-hy21.glb'))
print(os.path.join(D, 'plus-hy21.glb'))
