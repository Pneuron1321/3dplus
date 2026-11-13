"""Плюс из деталей, как настоящая фигурка (30.10, Александр: «генератор — пудинг, картинки точёные»; «собрать из деталей
разумнее, если справишься досконально»).

python3.13 tools/forma/sborka.py выход.glb

Размеры каждой детали — в точках крупных видов (quest-claude docs/maket/vidy-30-10/, 1086×1448):
- x и высота — с вида спереди (front): x картинки 540 — середина фигуры, низ подошвы — строка 1383;
- глубина — с вида слева (left, лицо влево): x картинки 520 — середина корпуса, меньше — к лицу.
  Вид сбоку на 2,7 % крупнее (рост 1366 против 1330) и по высоте с видом спереди не согласован (голень ниже),
  поэтому с бока берётся только глубина детали, высота — всегда с вида спереди.
Единица: 1 точка вида спереди = 1 мм (рост 1,33 м). Оси: x — влево фигуры (вправо на картинке спереди), y — вверх,
z — к лицу. Детали отдельными объектами с именами (потом — к костям), материал — цвет палитры, матовый пластик.
"""
import sys, math
import bpy, bmesh

OUT = sys.argv[1]
CX, NIZ, CZ, KL = 540, 1383, 520, 1330 / 1366
K = 0.001

PAL = {'bel': 'E6E6E4', 'sv': 'BDBEBE', 'ser': '7A7C80', 'tm': '3A3B3D', 'or': 'F27400', 'zh': 'F6CD10',
       'fi': '8656EC', 'kr': 'E0281E', 'mt': 'A9D68E', 'ch': '151515'}

def X(xf): return (xf - CX) * K
def Y(yf): return (NIZ - yf) * K
def Z(xl): return (CZ - xl) * KL * K

bpy.ops.wm.read_factory_settings(use_empty=True)
MAT = {}
def mat(c):
    if c not in MAT:
        m = bpy.data.materials.new(c); m.use_nodes = True
        h = PAL[c]; lin = lambda v: (v / 255 / 12.92) if v / 255 <= 0.04045 else ((v / 255 + 0.055) / 1.055) ** 2.4
        b = m.node_tree.nodes['Principled BSDF']
        b.inputs['Base Color'].default_value = (*[lin(int(h[i:i + 2], 16)) for i in (0, 2, 4)], 1)
        b.inputs['Metallic'].default_value = 0.0; b.inputs['Roughness'].default_value = 0.6
        MAT[c] = m
    return MAT[c]

def gotovo(o, c, bevel, seg=3):
    o.data.materials.append(mat(c))
    if bevel > 0:
        b = o.modifiers.new('bevel', 'BEVEL'); b.width = bevel * K; b.segments = seg; b.limit_method = 'ANGLE'
        b.harden_normals = True
    for p in o.data.polygons:
        p.use_smooth = True
    return o

def mesto(o, x0, x1, y0, y1, l0, l1):
    """в Blender вверх — Z, к зрителю — −Y (glTF при выгрузке: x, z, −y → наши x, y, z)."""
    o.location = ((X(x0) + X(x1)) / 2, -(Z(l0) + Z(l1)) / 2, (Y(y0) + Y(y1)) / 2)
    o.scale = (abs(X(x1) - X(x0)), abs(Z(l1) - Z(l0)), abs(Y(y1) - Y(y0)))
    bpy.ops.object.transform_apply(scale=True)

def box(imya, x0, x1, y0, y1, l0, l1, c, bevel=4, seg=3):
    """рамка: x0..x1 и y0..y1 — вид спереди (y — строки, сверху вниз), l0..l1 — вид сбоку (l0 — ближе к лицу)."""
    bpy.ops.mesh.primitive_cube_add(size=1)
    o = bpy.context.object; o.name = imya
    mesto(o, x0, x1, y0, y1, l0, l1)
    return gotovo(o, c, bevel, seg)

def cyl(imya, os_, x0, x1, y0, y1, l0, l1, c, bevel=3, n=32):
    """цилиндр, вписанный в рамку; os_ — ось: 'x' (вбок, как наушник), 'y' (вверх, как шея), 'z' (к лицу)."""
    bpy.ops.mesh.primitive_cylinder_add(vertices=n, radius=0.5, depth=1)
    o = bpy.context.object; o.name = imya
    if os_ == 'x':
        o.rotation_euler = (0, math.pi / 2, 0)
    elif os_ == 'z':
        o.rotation_euler = (math.pi / 2, 0, 0)                  # ось цилиндра Blender — Z (у нас вверх)
    bpy.ops.object.transform_apply(rotation=True)
    mesto(o, x0, x1, y0, y1, l0, l1)
    return gotovo(o, c, bevel, 2)

def para(f, imya, x0, x1, *a, **kw):
    """деталь и её зеркальная пара: x0..x1 — та, что слева на картинке спереди (правая рука/нога фигуры)."""
    f(imya + 'R', *([a[0]] if f is cyl else []), x0, x1, *a[1 if f is cyl else 0:], **kw)
    f(imya + 'L', *([a[0]] if f is cyl else []), 2 * CX - x1, 2 * CX - x0, *a[1 if f is cyl else 0:], **kw)

# ---------- голова ----------
box('shlem', 362, 718, 112, 330, 398, 700, 'bel', bevel=46, seg=5)           # белый шлем, скруглён
box('lico', 382, 698, 300, 440, 392, 540, 'zh', bevel=18, seg=4)             # жёлтое лицо (ниже визора)
box('vizor', 350, 730, 232, 338, 358, 520, 'kr', bevel=8)                    # красный визор, выступает вперёд
for x0, x1 in ((450, 497), (583, 634)):                                       # глаза — чёрные квадраты на визоре
    box('glaz', x0, x1, 262, 322, 354, 362, 'ch', bevel=1, seg=1)
box('rot', 464, 621, 381, 396, 388, 396, 'ch', bevel=1, seg=1)                # рот — улыбка: полоса и уголки вверх
for x0, x1 in ((451, 466), (619, 634)):
    box('rot_ugol', x0, x1, 367, 383, 388, 396, 'ch', bevel=1, seg=1)
# гребень пришельца: основание и два рожка
box('greben', 418, 662, 92, 160, 410, 610, 'fi', bevel=6)
para(box, 'rozhok', 424, 476, 50, 100, 486, 548, 'fi', bevel=5)
box('greben_lob', 440, 640, 140, 178, 400, 470, 'fi', bevel=5)                # спуск гребня ко лбу
# наушники: серая чашка, оранжевый обод, серый диск
para(cyl, 'naushnik', 290, 368, 'x', 222, 362, 466, 606, 'sv', bevel=4)
para(cyl, 'obod', 300, 352, 'x', 210, 374, 452, 620, 'or', bevel=3)
para(cyl, 'disk', 284, 300, 'x', 250, 334, 494, 578, 'ser', bevel=2)

# ---------- корпус ----------
cyl('sheya', 'y', 452, 628, 432, 482, 466, 574, 'tm', bevel=4)
box('grud', 404, 682, 468, 712, 412, 628, 'bel', bevel=14, seg=4)             # белая грудь
box('grud_niz', 418, 668, 690, 742, 420, 620, 'sv', bevel=8)                  # серый пояс под грудью
box('taz', 458, 622, 738, 828, 440, 600, 'ser', bevel=10)                    # серый таз
para(cyl, 'bedro_sharnir', 418, 468, 'x', 752, 808, 492, 600, 'tm', bevel=4)  # тазобедренные шарниры
para(box, 'bok_grudi', 392, 412, 480, 700, 430, 610, 'or', bevel=4)            # оранжевые вставки по бокам груди

# ---------- руки ----------
para(box, 'plecho', 238, 396, 468, 628, 452, 622, 'bel', bevel=30, seg=4)     # белая наплечная скоба
para(cyl, 'plecho_sharnir', 380, 412, 'x', 480, 560, 470, 590, 'tm', bevel=4)
para(box, 'ruka_verh', 262, 372, 610, 668, 470, 600, 'tm', bevel=8)            # тёмное плечо
para(box, 'predplechye', 172, 346, 640, 802, 458, 620, 'bel', bevel=18, seg=4) # предплечье: белое с серым
para(box, 'predplechye_or', 168, 198, 650, 790, 470, 610, 'or', bevel=6)       # оранжевая накладка снаружи
para(box, 'kist', 190, 300, 796, 896, 470, 592, 'tm', bevel=16, seg=4)         # кулак

# ---------- ноги ----------
para(box, 'bedro', 362, 486, 776, 912, 480, 622, 'tm', bevel=12)
para(box, 'bedro_or', 344, 372, 790, 900, 490, 610, 'or', bevel=6)
para(cyl, 'koleno', 368, 476, 'x', 908, 976, 496, 604, 'tm', bevel=4)
para(box, 'golen', 326, 468, 970, 1142, 508, 630, 'tm', bevel=12)
para(box, 'golen_or', 322, 352, 984, 1130, 512, 624, 'or', bevel=8)
para(box, 'golen_or2', 440, 470, 984, 1130, 512, 624, 'or', bevel=8)
para(cyl, 'lodyzhka', 330, 452, 'x', 1134, 1200, 530, 622, 'zh', bevel=3)     # жёлтые «колёса» лодыжки
# тапочки «Пикколо» — отдельные объекты (снимаются): скруглённое тело и воротник у щиколотки; волны — краской с видов
para(box, 'tapok', 240, 496, 1206, 1383, 366, 676, 'mt', bevel=64, seg=6)
para(box, 'tapok_vorot', 296, 474, 1180, 1232, 420, 650, 'mt', bevel=20, seg=4)

# ---------- выход ----------
for o in bpy.data.objects:
    o.select_set(True)
bpy.ops.export_scene.gltf(filepath=OUT, export_format='GLB', use_selection=False, export_apply=True)
tri = 0
dg = bpy.context.evaluated_depsgraph_get()
for o in bpy.data.objects:
    me = o.evaluated_get(dg).to_mesh(); me.calc_loop_triangles(); tri += len(me.loop_triangles)
print('деталей %d, треугольников %d' % (len(bpy.data.objects), tri))
print(OUT)
