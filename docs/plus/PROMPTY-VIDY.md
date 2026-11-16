# Промпты: 5 ракурсов Плюса крупно — для генератора 3D (30.10)

Зачем: на листе тела каждый вид — около 270×490 точек, поэтому модель из генератора выходит нечёткой.
Нужно 5 отдельных картинок, каждая не меньше 1024 точек по высоте, все одинаковые по позе, масштабу и свету.

## Как генерировать (Александр)

1. Каждый ракурс — **отдельным запросом**. К каждому прикладывать исходник `plus-telo-list.png` (лист тела).
2. Начать с **вида спереди**. Когда он получится, прикладывать его вторым файлом ко всем следующим запросам: так
   генератор держит те же цвета и пропорции.
3. Если в ответе есть подписи, линейки, второй робот или тень на полу — повторить с той же фразой.
4. Готовые 5 картинок прислать сюда: `front`, `left`, `back`, `right`, `v34`.

## Общие правила (уже внутри каждого промпта)

- одна фигура, во весь рост, от макушки пришельца до подошвы, по центру, занимает ~90 % высоты кадра;
- ортографическая камера (без перспективы), на уровне груди, одна и та же высота камеры и масштаб во всех ракурсах;
- поза одна: стоит прямо, руки опущены и **чуть отведены от тела** (видна щель между рукой и корпусом), ноги на ширине таза;
- фон ровный нейтрально-серый `#B8BCC2` — не белый, чтобы белые детали не сливались с фоном; без пола, без тени, без
  отражений;
- ровный студийный свет, без бликов и цветных рефлексов, без глубины резкости — всё резкое;
- никакого текста, подписей, линеек, рамок, водяных знаков;
- **рот — улыбка** (полоса и уголки вверх), а не уголки вниз, как на листе;
- **тапочки «Пикколо» на 20 % меньше**, чем на листе, одинаковые во всех ракурсах.

## Общая часть (она уже вставлена в каждый промпт ниже)

```
Character: the toy robot "Plus" from the attached reference sheet — brick-built collectible figure. Keep EXACTLY its design,
proportions and colors: purple 8-bit alien crest on top of the head; white brick helmet with grey round headphones and
orange rims; wide glossy red visor with two black square eyes; yellow brick face; white chest box with grey panel; dark
grey mechanical joints; white and grey arm plates with orange accents; dark grey legs with orange accents; light mint-green
"Piccolo" slip-on slippers with wavy ribbed sides. Colors: white #E6E6E4, light grey #BDBEBE, dark grey #3A3B3D,
orange #F27400, yellow #F6CD10, purple #8656EC, red visor #E0281E, mint #A9D68E.
Changes from the sheet: the mouth is a friendly SMILE (black line with both corners turned UP), not a frown; the mint
slippers are 20% smaller than on the sheet.
Pose: standing straight, arms down and slightly away from the body (clear gap between arms and torso), feet hip-width apart.
Camera: orthographic, no perspective distortion, camera at chest height, whole figure from the top of the alien crest to
the soles, centered, filling 90% of the image height. Same scale, pose and lighting as the other views of this set.
Background: flat solid neutral grey #B8BCC2, no floor, no shadow, no reflections, no gradient.
Lighting: soft even studio light, no harsh highlights, no colored reflections, everything in sharp focus.
Output: one single figure only, high resolution 1536x2048 portrait, crisp brick edges and studs.
No text, no labels, no rulers, no arrows, no frames, no watermark, no second figure, no turntable.
```

## 1. Спереди (`front`) — первым

```
Generate ONE image: FRONT VIEW of the robot from the attached sheet (same as the "ВИД СПЕРЕДИ" panel, top left).
The robot faces the camera directly, perfectly symmetric, both headphones equally visible, visor and smile centered.
Character: the toy robot "Plus" from the attached reference sheet — brick-built collectible figure. Keep EXACTLY its design,
proportions and colors: purple 8-bit alien crest on top of the head; white brick helmet with grey round headphones and
orange rims; wide glossy red visor with two black square eyes; yellow brick face; white chest box with grey panel; dark
grey mechanical joints; white and grey arm plates with orange accents; dark grey legs with orange accents; light mint-green
"Piccolo" slip-on slippers with wavy ribbed sides. Colors: white #E6E6E4, light grey #BDBEBE, dark grey #3A3B3D,
orange #F27400, yellow #F6CD10, purple #8656EC, red visor #E0281E, mint #A9D68E.
Changes from the sheet: the mouth is a friendly SMILE (black line with both corners turned UP), not a frown; the mint
slippers are 20% smaller than on the sheet.
Pose: standing straight, arms down and slightly away from the body (clear gap between arms and torso), feet hip-width apart.
Camera: orthographic, no perspective distortion, camera at chest height, whole figure from the top of the alien crest to
the soles, centered, filling 90% of the image height.
Background: flat solid neutral grey #B8BCC2, no floor, no shadow, no reflections, no gradient.
Lighting: soft even studio light, no harsh highlights, no colored reflections, everything in sharp focus.
Output: one single figure only, high resolution 1536x2048 portrait, crisp brick edges and studs.
No text, no labels, no rulers, no arrows, no frames, no watermark, no second figure.
```

## 2. Слева (`left`)

```
Generate ONE image: LEFT SIDE VIEW (pure 90° profile) of the robot from the attached sheet — same angle as the
"ВИД СЛЕВА" panel: the robot faces the viewer's LEFT, the visor is at the left edge of the head, only one headphone
(the round grey disc with orange rim) is visible in the middle of the head side. The second image is the front view of
the same robot — match its colors, proportions, smile and slipper size exactly.
Character: the toy robot "Plus" — brick-built collectible figure. Keep EXACTLY its design, proportions and colors:
purple 8-bit alien crest on top of the head; white brick helmet with grey round headphones and orange rims; wide glossy
red visor with two black square eyes; yellow brick face; white chest box with grey panel; dark grey mechanical joints;
white and grey arm plates with orange accents; dark grey legs with orange accents; light mint-green "Piccolo" slip-on
slippers with wavy ribbed sides. Colors: white #E6E6E4, light grey #BDBEBE, dark grey #3A3B3D, orange #F27400,
yellow #F6CD10, purple #8656EC, red visor #E0281E, mint #A9D68E. Slippers 20% smaller than on the sheet.
Pose: standing straight, arm down along the side with a small gap to the torso, legs straight.
Camera: orthographic, no perspective distortion, camera at chest height, whole figure from the top of the alien crest to
the soles, centered, filling 90% of the image height, same scale as the front view.
Background: flat solid neutral grey #B8BCC2, no floor, no shadow, no reflections, no gradient.
Lighting: soft even studio light, no harsh highlights, everything in sharp focus.
Output: one single figure only, high resolution 1536x2048 portrait, crisp brick edges and studs.
No text, no labels, no rulers, no arrows, no frames, no watermark, no second figure.
```

## 3. Сзади (`back`)

```
Generate ONE image: BACK VIEW of the robot from the attached sheet — same as the "ВИД СЗАДИ" panel: we see the back of
the white brick helmet with the round grey disc in the middle, the back of the white chest box with the grey square panel
and black connector, cables and the back of the legs; both headphones equally visible at the sides; the face and visor are
NOT visible. Perfectly symmetric. The second image is the front view of the same robot — match its colors, proportions
and slipper size exactly.
Character: the toy robot "Plus" — brick-built collectible figure. Keep EXACTLY its design, proportions and colors:
purple 8-bit alien crest on top of the head; white brick helmet with grey round headphones and orange rims; white chest box;
dark grey mechanical joints; white and grey arm plates with orange accents; dark grey legs with orange accents; light
mint-green "Piccolo" slip-on slippers with wavy ribbed sides (back heel visible). Colors: white #E6E6E4, light grey #BDBEBE,
dark grey #3A3B3D, orange #F27400, yellow #F6CD10, purple #8656EC, mint #A9D68E. Slippers 20% smaller than on the sheet.
Pose: standing straight, arms down and slightly away from the body (clear gap), feet hip-width apart.
Camera: orthographic, no perspective distortion, camera at chest height, whole figure from the top of the alien crest to
the soles, centered, filling 90% of the image height, same scale as the front view.
Background: flat solid neutral grey #B8BCC2, no floor, no shadow, no reflections, no gradient.
Lighting: soft even studio light, no harsh highlights, everything in sharp focus.
Output: one single figure only, high resolution 1536x2048 portrait, crisp brick edges and studs.
No text, no labels, no rulers, no arrows, no frames, no watermark, no second figure.
```

## 4. Справа (`right`)

```
Generate ONE image: RIGHT SIDE VIEW (pure 90° profile) of the robot from the attached sheet — same angle as the
"ВИД СПРАВА" panel: the robot faces the viewer's RIGHT, the visor is at the right edge of the head, only one headphone
(the round grey disc with orange rim) is visible in the middle of the head side. This is the mirror counterpart of the
left view. The second image is the front view of the same robot — match its colors, proportions, smile and slipper size.
Character: the toy robot "Plus" — brick-built collectible figure. Keep EXACTLY its design, proportions and colors:
purple 8-bit alien crest on top of the head; white brick helmet with grey round headphones and orange rims; wide glossy
red visor with two black square eyes; yellow brick face; white chest box with grey panel; dark grey mechanical joints;
white and grey arm plates with orange accents; dark grey legs with orange accents; light mint-green "Piccolo" slip-on
slippers with wavy ribbed sides. Colors: white #E6E6E4, light grey #BDBEBE, dark grey #3A3B3D, orange #F27400,
yellow #F6CD10, purple #8656EC, red visor #E0281E, mint #A9D68E. Slippers 20% smaller than on the sheet.
Pose: standing straight, arm down along the side with a small gap to the torso, legs straight.
Camera: orthographic, no perspective distortion, camera at chest height, whole figure from the top of the alien crest to
the soles, centered, filling 90% of the image height, same scale as the front view.
Background: flat solid neutral grey #B8BCC2, no floor, no shadow, no reflections, no gradient.
Lighting: soft even studio light, no harsh highlights, everything in sharp focus.
Output: one single figure only, high resolution 1536x2048 portrait, crisp brick edges and studs.
No text, no labels, no rulers, no arrows, no frames, no watermark, no second figure.
```

## 5. Три четверти (`v34`)

```
Generate ONE image: THREE-QUARTER VIEW of the robot from the attached sheet — the same angle as the "3/4 ВИД" panel
(top right): the robot is turned about 35–45° so we see its front and one side; the visor, yellow face and smile are
clearly visible, the near headphone is fully visible, the far one partly. The second image is the front view of the same
robot — match its colors, proportions, smile and slipper size exactly.
Character: the toy robot "Plus" — brick-built collectible figure. Keep EXACTLY its design, proportions and colors:
purple 8-bit alien crest on top of the head; white brick helmet with grey round headphones and orange rims; wide glossy
red visor with two black square eyes; yellow brick face; white chest box with grey panel; dark grey mechanical joints;
white and grey arm plates with orange accents; dark grey legs with orange accents; light mint-green "Piccolo" slip-on
slippers with wavy ribbed sides. Colors: white #E6E6E4, light grey #BDBEBE, dark grey #3A3B3D, orange #F27400,
yellow #F6CD10, purple #8656EC, red visor #E0281E, mint #A9D68E.
The mouth is a friendly SMILE (black line with both corners turned UP). Slippers 20% smaller than on the sheet.
Pose: standing straight, arms down and slightly away from the body (clear gap between arms and torso), feet hip-width apart.
Camera: orthographic (no perspective distortion), camera at chest height, whole figure from the top of the alien crest to
the soles, centered, filling 90% of the image height, same scale as the front view.
Background: flat solid neutral grey #B8BCC2, no floor, no shadow, no reflections, no gradient.
Lighting: soft even studio light, no harsh highlights, everything in sharp focus.
Output: one single figure only, high resolution 1536x2048 portrait, crisp brick edges and studs.
No text, no labels, no rulers, no arrows, no frames, no watermark, no second figure.
```

## Что проверить в каждой картинке, прежде чем прислать

1. Одна фигура, целиком, ничего не обрезано (пришелец сверху и подошвы снизу в кадре).
2. Фон ровный серый, без пола и тени.
3. Улыбка уголками вверх (на спине — не видно, это нормально).
4. Тапочки одного размера на всех ракурсах.
5. Цвета те же, что на виде спереди: белое — белое, не серебро.
