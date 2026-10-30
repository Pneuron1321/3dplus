# Инструменты для головы зверя

Скрипты, которыми рисунки Александра превращаются в данные для `src/zver-golova.js`. Сами рисунки лежат только
в закрытом репозитории (`docs/maket`). Путь к ним задаёт `ZV_MAKET`, по умолчанию `/home/user/quest-claude/docs/maket`.
Первый аргумент каждого скрипта — рабочая папка `S`. В ней лежат `zlico/`, `zv2/` (промежуточные файлы) и `modeli/` (нейросети).

## Нейросети (CPU, onnxruntime)

Скачать в `S/modeli/`. Hugging Face из контейнера закрыт, поэтому всё берётся из релизов GitHub:

| Файл | Откуда | Что даёт |
|---|---|---|
| `depth_anything_v2_vitb.onnx` (389 МБ) | https://github.com/fabio-sim/Depth-Anything-ONNX/releases/download/v2.0.0/depth_anything_v2_vitb.onnx | глубина по одной картинке; вход 518×518 |
| `depth_anything_v2_vits.onnx` | …/v2.0.0/depth_anything_v2_vits.onnx | то же, быстрее и грубее |
| `isnet-anime.onnx` (176 МБ) | https://github.com/danielgatis/rembg/releases/download/v0.0.0/isnet-anime.onnx | вырезка рисованного персонажа (на `ref-zver-2` не годится: ловит всю текстуру) |
| `model-small.onnx` | https://github.com/isl-org/MiDaS/releases/download/v2_1/model-small.onnx | запасная глубина |

```
pip install onnxruntime scipy pillow numpy
```

## Крупный план из комикса (`ref-zver-2`): форма по глубине

1. `glubina.py S` строит карту глубины `S/zv2/glubina.npy` (и пробную маску isnet).
2. `maska.py S` показывает превью маски головы при трёх порогах глубины. Функция `maska()` отделяет голову от фона
   по глубине, а от плеч и тела отсекает ломаной снизу и справа.
3. `gdannye.py S src/zver-glubina.js [ширина=1.25] [порог=5.0]` собирает данные для `buildGlubina`:
   RGBA, поле расстояний до силуэта, глубину.
4. Рендер пробы: `node tools/build.mjs --lico=zver --view=zlist --opt=glubina=1`. Параметры формы:
   - `--opt=gz=` — глубина рельефа;
   - `gb=`, `gt=` — толщина затылка;
   - `gr=` — скругление краёв;
   - `gu=` — масштаб.
5. `sheet.py out.png zfront-zver-dbg.png …` собирает лист сравнения «рисунок | рендеры».

`front2.py S [ширина]` — прежняя вырезка фронта полигоном и по цвету (для `buildRisunok`).

## Лист HEAD DETAIL (`ref-zver-list`): силуэты спереди и сбоку

1. `vyrez.py`, `bok.py` вырезают фронт и профиль с листа.
2. `sloi.py` режет голову и челюсть на слои для живого лица в эфире (`zver-lico.png`, `zver-chelyust.png`).
3. `dannye.py S src/zver-risunok.js` собирает данные для `buildRisunok`. Переменные окружения: `ZV_FRONT`,
   `ZV_CX`, `ZV_CHIN`, `ZV_EYECHIN`, `ZV_U`.

## Сетка генератора (Hunyuan3D-2) и кожа целиком

1. `hy-setka.py S вход.glb src/zver-setka.js` — сетка генератора → «Зверь-3» (кожа — рисунок из его камеры).
2. `kozha.py S src/zver-setka.js src/zver-kozha.js [треугольников=50000] [атлас=2048]` — «Зверь-4»: облегчение,
   атлас (xatlas), лицо — рисунок, затылок и бока — кожа из кусочков рисунка; плитка кожи — для шеи.
   Нужно: `pip install fast_simplification xatlas trimesh scipy pillow`, а также `S/zv2/glubina.npy` (`glubina.py`).
3. `glb-vid.mjs вход.glb выход.png [ракурсы] [размер]` — осмотр GLB со всех сторон.

Результаты (голова по умолчанию, рендеры в `renders/`, GLB) меняются только после «одобряю» Александра.
Пробы лежат в `docs/zver/proba/`.
