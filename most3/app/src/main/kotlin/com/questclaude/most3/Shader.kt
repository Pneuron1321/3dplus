package com.questclaude.most3

// Шейдеры картинки (GLSL ES 3.00). Та же математика — в KartinkaCpu (Kartinka.kt), тесты сверяют.
// Сборка фрагментного: SHAPKA + YUV + выборка формата + GLAVNAYA. Выборка даёт rgbVzyat(uv) (0..1, полный диапазон,
// гамма-кодированный SDR BT.709) и shag() = 1/размер кадра в пикселях (через textureSize — передавать не нужно).
// P010 (HDR с PS5): в rgbVzyat уже входит PQ → нит → BT.709 → тон-маппинг → гамма 2.2, дальше общий конвейер.
object Shader {
    // Общие uniform-ы всех вариантов (все float; сэмплеры — отдельно, по формату). Объявлены во всех вариантах,
    // uHdr/uPik/uBelyj работают только в P010, uPolnyj — во всех YUV.
    val UNIFORMY = listOf("uYarkost", "uKontrast", "uNasyshch", "uGamma", "uTeni", "uAvtoTeni", "uRezkost", "uPolnyj", "uSyroe",
        "uLokalno", "uSuper", "uHdr", "uPik", "uBelyj")
    val SAMPLERY_YUY2 = listOf("uTekYuy2")          // блок 0: GL_RGBA8 W/2×H, (Y0,U,Y1,V)
    val SAMPLERY_NV12 = listOf("uTekY", "uTekUV")   // блок 0: GL_R8 W×H; блок 1: GL_RG8 W/2×H/2
    val SAMPLERY_RGBA = listOf("uTekRgba")          // блок 0: GL_RGBA8 W×H
    val SAMPLERY_P010 = listOf("uTekP010Y", "uTekP010UV")   // блок 0: GL_RG8 W×H (lo,hi); блок 1: GL_RGBA8 W/2×H/2 (Ulo,Uhi,Vlo,Vhi)
    const val SAMPLER_KARTA = "uKarta"              // блок 2 во ВСЕХ вариантах: GL_RGBA8 32×18 — сырой кадр из крошки
    const val BLOK_KARTY = 2

    // Вершинный: полноэкранный треугольник без буферов — glDrawArrays(GL_TRIANGLES, 0, 3) с пустым VAO.
    // vUv (0,0) = первая строка данных текстуры (верх кадра) — переворот уже учтён.
    val VERSHINNYJ = """
        #version 300 es
        out vec2 vUv;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            vUv = vec2(p.x, 1.0 - p.y);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent() + "\n"

    private val SHAPKA = """
        #version 300 es
        precision highp float;
        precision highp int;
        in vec2 vUv;
        out vec4 fragColor;
        uniform float uYarkost;   // -0.5..0.5, прибавка
        uniform float uKontrast;  // 0.5..2, S-кривая вокруг 0.5
        uniform float uNasyshch;  // 0..2, вокруг яркости Rec.709
        uniform float uGamma;     // 0.5..2.5, pow(x, 1/g)
        uniform float uTeni;      // 0..1, подъём теней
        uniform float uAvtoTeni;  // 0..1, авто-ночь (считает CPU), складывается с uTeni
        uniform float uRezkost;   // 0..2, нерезкая маска по яркости
        uniform float uPolnyj;    // 1 = YUV полный диапазон, 0 = ограниченный 16-235/16-240 (10 бит: 64-940/64-960)
        uniform float uSyroe;     // 1 = без обработки (замер средней яркости и карта): одна выборка, без местной ночи
        uniform float uLokalno;   // 0..1, местная авто-ночь по карте uKarta; 0 = ровно без изменений
        uniform float uSuper;     // текселей кадра на пиксель окна; > 1.2 — 4 выборки (уменьшение без ряби)
        uniform float uHdr;       // P010: 1 = PQ BT.2020 (HDR), 0 = SDR BT.709 10 бит
        uniform float uPik;       // P010 HDR: пик мастеринга, нит (1000)
        uniform float uBelyj;     // P010 HDR: белый SDR («бумага»), нит (203)
        uniform sampler2D uKarta; // блок 2: 32×18 RGBA8, сырой кадр (строки снизу вверх, как в FBO)
        const vec3 VES = vec3(0.2126, 0.7152, 0.0722);
        const float K_TENEJ = 3.0;
        const float PREDEL_REZKOSTI = 0.1;
        const float SUPER_POROG = 1.2;
        const float LOK_CEL = 0.3;
        const float LOK_MAX = 3.0;
        const float LOK_MIN = 0.8;
        const float LOK_RHO0 = 1.25;
        const float LOK_LN_RHO = 1.0986123;
        const float LOK_SHOZH = 24.0;
        const float LOK_EPS = 0.02;
    """.trimIndent() + "\n"

    // BT.709 YCbCr -> RGB; y,u,v — значения текстуры (байт/255).
    private val YUV = """
        vec3 yuvVRgb(float y, float u, float v) {
            if (uPolnyj > 0.5) {
                u -= 128.0 / 255.0; v -= 128.0 / 255.0;
            } else {
                y = (y - 16.0 / 255.0) * (255.0 / 219.0);
                u = (u - 128.0 / 255.0) * (255.0 / 224.0);
                v = (v - 128.0 / 255.0) * (255.0 / 224.0);
            }
            return clamp(vec3(y + 1.5748 * v, y - 0.18732 * u - 0.46812 * v, y + 1.8556 * u), 0.0, 1.0);
        }
    """.trimIndent() + "\n"

    // YUY2: точная выборка texelFetch, чётность пикселя x выбирает Y0/Y1. Фильтр текстуры не важен.
    private val VYBORKA_YUY2 = """
        uniform sampler2D uTekYuy2;
        vec2 shag() { ivec2 r = textureSize(uTekYuy2, 0); return 1.0 / vec2(float(r.x * 2), float(r.y)); }
        vec3 rgbVzyat(vec2 uv) {
            ivec2 r = textureSize(uTekYuy2, 0);
            int w = r.x * 2;
            ivec2 p = clamp(ivec2(floor(uv * vec2(float(w), float(r.y)))), ivec2(0), ivec2(w - 1, r.y - 1));
            vec4 t = texelFetch(uTekYuy2, ivec2(p.x / 2, p.y), 0);
            float y = (p.x % 2 == 0) ? t.r : t.b;
            return yuvVRgb(y, t.g, t.a);
        }
    """.trimIndent() + "\n"

    private val VYBORKA_NV12 = """
        uniform sampler2D uTekY;
        uniform sampler2D uTekUV;
        vec2 shag() { return 1.0 / vec2(textureSize(uTekY, 0)); }
        vec3 rgbVzyat(vec2 uv) {
            vec2 c = texture(uTekUV, uv).rg;
            return yuvVRgb(texture(uTekY, uv).r, c.x, c.y);
        }
    """.trimIndent() + "\n"

    private val VYBORKA_RGBA = """
        uniform sampler2D uTekRgba;
        vec2 shag() { return 1.0 / vec2(textureSize(uTekRgba, 0)); }
        vec3 rgbVzyat(vec2 uv) { return clamp(texture(uTekRgba, uv).rgb, 0.0, 1.0); }
    """.trimIndent() + "\n"

    // P010 → RGB. HDR: PQ (SMPTE ST 2084) → нит → BT.2020→BT.709 (линейно) → тон-маппинг яркости → гамма 2.2.
    // Кривая тона (по x = нит/uBelyj, 1 = белый SDR): до колена 0.8 — 1:1 (тени и середина не трогаются, ночь не
    // темнеет — в отличие от Reinhard/ACES), выше — «расширенный Reinhard» по остатку: g(a) = a(1+a/A²)/(1+a),
    // a = (x−0.8)/0.2, A = (uPik/uBelyj − 0.8)/0.2 (≥1). Гладко (C1) в колене, строго растёт, пик мастеринга → ровно 1.
    // BT.2390 EETF не взят: он рассчитан на дисплей-цель в сотни нит, а у нас цель = белый SDR, и колено EETF
    // уходит ниже 203 нит — белый бумаги темнеет до ~0.86 (здесь 203 нит → 0.90 линейно = 0.953 после гаммы).
    // Цвет: яркость тонируется, RGB масштабируется (оттенок и насыщенность сохраняются); если канал > 1 —
    // делим на максимум (оттенок цел) и чуть «выбеливаем» на 3%·(1−1/max) — без сдвига красного в оранжевый.
    // Цвета вне BT.709 (отрицательные каналы) — сводим к серому той же яркости до нуля минимального канала.
    private val HDR = """
        const float PQ_M1 = 0.1593017578125;
        const float PQ_M2 = 78.84375;
        const float PQ_C1 = 0.8359375;
        const float PQ_C2 = 18.8515625;
        const float PQ_C3 = 18.6875;
        const float TON_KOLENO = 0.8;
        const float BELENIE = 0.03;
        const float GAMMA_EKRANA = 2.2;
        const mat3 M2020_709 = mat3(1.660491, -0.124550, -0.018151, -0.587641, 1.132900, -0.100579, -0.072850, -0.008349, 1.118730);
        vec3 pqVNity(vec3 e) {
            vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / PQ_M2));
            return 10000.0 * pow(max(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p), vec3(1.0 / PQ_M1));
        }
        float tonKrivaya(float x, float xMax) {
            if (x <= TON_KOLENO) return x;
            float bA = max((xMax - TON_KOLENO) / (1.0 - TON_KOLENO), 1.0);
            float a = (x - TON_KOLENO) / (1.0 - TON_KOLENO);
            return min(TON_KOLENO + (1.0 - TON_KOLENO) * a * (1.0 + a / (bA * bA)) / (1.0 + a), 1.0);
        }
        vec3 hdrVSdr(vec3 e) {
            vec3 l = M2020_709 * (pqVNity(e) / uBelyj);
            float y = max(dot(l, VES), 0.0);
            float mn = min(l.r, min(l.g, l.b));
            if (mn < 0.0) l = y + (l - y) * (y / max(y - mn, 1e-6));
            float yt = tonKrivaya(y, uPik / uBelyj);
            vec3 c = l * (yt / max(y, 1e-6));
            float mx = max(c.r, max(c.g, c.b));
            if (mx > 1.0) c = mix(c / mx, vec3(1.0), BELENIE * (1.0 - 1.0 / mx));
            return pow(clamp(c, 0.0, 1.0), vec3(1.0 / GAMMA_EKRANA));
        }
        // y,u,v — 10-битные коды 0..1023
        vec3 p010VRgb(float y, float u, float v) {
            if (uPolnyj > 0.5) {
                y /= 1023.0; u = (u - 512.0) / 1023.0; v = (v - 512.0) / 1023.0;
            } else {
                y = (y - 64.0) / 876.0; u = (u - 512.0) / 896.0; v = (v - 512.0) / 896.0;
            }
            if (uHdr > 0.5) return hdrVSdr(clamp(vec3(y + 1.4746 * v, y - 0.16455 * u - 0.57135 * v, y + 1.8814 * u), 0.0, 1.0));
            return clamp(vec3(y + 1.5748 * v, y - 0.18732 * u - 0.46812 * v, y + 1.8556 * u), 0.0, 1.0);
        }
    """.trimIndent() + "\n"

    // P010: 16 бит little-endian в двух байтах текселя, значение — в старших 10 битах. Линейная фильтрация по
    // байтам неверна (перенос между lo/hi), поэтому texelFetch 2×2 и билинейно — по кодам, как GL_LINEAR у NV12.
    private val VYBORKA_P010 = """
        uniform sampler2D uTekP010Y;
        uniform sampler2D uTekP010UV;
        vec2 shag() { return 1.0 / vec2(textureSize(uTekP010Y, 0)); }
        float kod10(vec2 t) { return (floor(t.x * 255.0 + 0.5) + 256.0 * floor(t.y * 255.0 + 0.5)) / 64.0; }
        float p010Y(ivec2 p, ivec2 r) { return kod10(texelFetch(uTekP010Y, clamp(p, ivec2(0), r - 1), 0).rg); }
        vec2 p010UV(ivec2 p, ivec2 r) { vec4 t = texelFetch(uTekP010UV, clamp(p, ivec2(0), r - 1), 0); return vec2(kod10(t.rg), kod10(t.ba)); }
        vec3 rgbVzyat(vec2 uv) {
            ivec2 r = textureSize(uTekP010Y, 0);
            ivec2 rc = textureSize(uTekP010UV, 0);
            vec2 q = uv * vec2(r) - 0.5; vec2 i = floor(q); vec2 f = q - i; ivec2 p = ivec2(i);
            vec2 qc = uv * vec2(rc) - 0.5; vec2 ic = floor(qc); vec2 fc = qc - ic; ivec2 pc = ivec2(ic);
            float y0 = mix(p010Y(p, r), p010Y(p + ivec2(1, 0), r), f.x);
            float y1 = mix(p010Y(p + ivec2(0, 1), r), p010Y(p + ivec2(1, 1), r), f.x);
            vec2 c0 = mix(p010UV(pc, rc), p010UV(pc + ivec2(1, 0), rc), fc.x);
            vec2 c1 = mix(p010UV(pc + ivec2(0, 1), rc), p010UV(pc + ivec2(1, 1), rc), fc.x);
            vec2 c = mix(c0, c1, fc.y);
            return p010VRgb(mix(y0, y1, f.y), c.x, c.y);
        }
    """.trimIndent() + "\n"

    // Конвейер — строго как KartinkaCpu.obrabotat. Резкость: дельта по яркости ВХОДА, прибавляется в конце.
    //
    // Местная ночь (lokalno): m — средняя яркость окрестности из карты 32×18 (ячейка ≈ 60 px на 1080p).
    // Чтение карты — «совместная билатеральная» билинейка: 4 ячейки с весами билинейки × 1/(1+24·ln²(l/mᵢ)),
    // т.е. пиксель берёт среднее ПОХОЖИХ по яркости соседних ячеек (при весе похожести 1 это ровно texture()).
    // Цель: среднее тёмной области → 0.3: g = 0.3(1−m)/(m·0.7), зажим 1..3; ярких (m>0.5) — g до 0.8.
    // g = mix(1, g, uLokalno). Защита по своему l: ρ = l/m, полный g при ρ ≤ 1.25, ноль при ρ ≥ 3.75 (по логарифму):
    // G = g^w — светлые детали в тёмной области (окна, фонари) не трогаются.
    // Кривая: l' = G·l / (1 + (G−1)·l) — 1 остаётся 1, выход ≤ 1, наклон в нуле G; RGB × l'/l (цветность цела),
    // множитель ограничен 1/max(rgb). Монотонность при постоянном m: d ln l'/d ln l = (1 + (1−l)·d ln G/d ln l)/(…),
    // а |d ln G/d ln l| ≤ ln g / ln 3 ≤ 1 при g ≤ 3 — числитель ≥ l > 0.
    // Почему нет ореолов: ореол — когда светлые пиксели у края получают подъём от тёмной соседней ячейки.
    // (1) похожесть: светлый пиксель у края берёт m светлых ячеек (вес тёмной при отношении 7× ≈ 0.01), тёмный —
    // тёмных, поэтому и тёмная «кайма» у края не появляется (без похожести тёмная сторона у края не поднималась бы
    // вовсе на ширину ячейки); (2) ρ-защита гасит подъём того, что уже в 3.75 раза ярче окрестности; (3) кривая сама
    // сжимает светлое (l=0.5 при G=1.5 → 0.6, а не 0.75). Остаток — когда центр ячейки карты попал ровно на край
    // (±1 px из 60, ~2% положений) и ячейка хранит полусумму: тест перепада 0.04|0.4 во всех положениях — светлая
    // сторона у края не более +0.03 сверх светлой вдали; для 0.04|0.3 в худшем положении ≈ +0.1 (сила 1) на ширине
    // ≤ 1 ячейки. Цена похожести: на 1–2 px переходе между очень разными областями порядок яркостей может
    // нарушиться (до ~0.09) — внутри однородной области монотонность строгая (см. выше).
    private val GLAVNAYA = """
        vec3 obrabotat(vec3 c) {
            c = clamp(c + uYarkost, 0.0, 1.0);
            // контраст: S-кривая через 0.5 с наклоном uKontrast в середине; 0 и 1 на месте — тени не обрезаются
            vec3 niz = 0.5 * pow(max(2.0 * c, vec3(1e-7)), vec3(uKontrast));
            vec3 verh = 1.0 - 0.5 * pow(max(2.0 - 2.0 * c, vec3(1e-7)), vec3(uKontrast));
            c = mix(niz, verh, step(0.5, c));
            float l = dot(c, VES);
            c = l + (c - l) * uNasyshch;
            c = clamp(c, 0.0, 1.0);
            c = pow(max(c, vec3(1e-7)), vec3(1.0 / uGamma));
            float t = clamp(uTeni + uAvtoTeni, 0.0, 1.0);
            vec3 o = 1.0 - c; o *= o; o *= o;
            return c + t * K_TENEJ * c * o;
        }
        float kartaYa(ivec2 p, ivec2 r) { return dot(texelFetch(uKarta, clamp(p, ivec2(0), r - 1), 0).rgb, VES); }
        vec3 lokalno(vec3 c, vec2 uv) {
            float l = dot(c, VES);
            ivec2 r = textureSize(uKarta, 0);
            vec2 q = vec2(uv.x, 1.0 - uv.y) * vec2(r) - 0.5;   // строки FBO идут снизу вверх
            vec2 i = floor(q); vec2 f = q - i; ivec2 p = ivec2(i);
            vec4 m4 = vec4(kartaYa(p, r), kartaYa(p + ivec2(1, 0), r), kartaYa(p + ivec2(0, 1), r), kartaYa(p + ivec2(1, 1), r));
            vec4 wb = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
            vec4 dl = log(m4 + LOK_EPS) - log(l + LOK_EPS);
            vec4 w4 = wb / (1.0 + LOK_SHOZH * dl * dl);
            float m = dot(w4, m4) / dot(w4, vec4(1.0));
            float g = (m < LOK_CEL) ? min(LOK_CEL * (1.0 - m) / (max(m, 1e-3) * (1.0 - LOK_CEL)), LOK_MAX)
                                    : 1.0 - (1.0 - LOK_MIN) * smoothstep(0.5, 0.9, m);
            g = mix(1.0, g, uLokalno);
            float rho = max(l, 1e-4) / max(m, LOK_EPS);
            float w = 1.0 - clamp(log(rho / LOK_RHO0) / LOK_LN_RHO, 0.0, 1.0);
            float bG = pow(g, w);
            float lo = bG * l / (1.0 + (bG - 1.0) * l);
            float mx = max(c.r, max(c.g, c.b));
            return c * min(lo / max(l, 1e-5), 1.0 / max(mx, 1e-5));
        }
        // Уменьшение: 2×2 выборки на ±0.25·uSuper текселя — покрывают след пикселя окна (при 2× — ровно 2×2 текселя).
        vec3 rgbSuper(vec2 uv) {
            if (uSuper <= SUPER_POROG) return rgbVzyat(uv);
            vec2 d = 0.25 * uSuper * shag();
            return 0.25 * (rgbVzyat(uv + vec2(-d.x, -d.y)) + rgbVzyat(uv + vec2(d.x, -d.y))
                         + rgbVzyat(uv + vec2(-d.x, d.y)) + rgbVzyat(uv + vec2(d.x, d.y)));
        }
        float yarkostVzyat(vec2 uv) { return dot(rgbVzyat(uv), VES); }
        void main() {
            if (uSyroe > 0.5) { fragColor = vec4(rgbVzyat(vUv), 1.0); return; }
            vec3 c = rgbSuper(vUv);
            float d = 0.0;
            if (uRezkost > 0.0) {
                vec2 s = shag() * max(1.0, uSuper);   // резкость в пикселях окна
                float sr = 0.25 * (yarkostVzyat(vUv + vec2(s.x, 0.0)) + yarkostVzyat(vUv - vec2(s.x, 0.0))
                                 + yarkostVzyat(vUv + vec2(0.0, s.y)) + yarkostVzyat(vUv - vec2(0.0, s.y)));
                d = clamp(uRezkost * (dot(c, VES) - sr), -PREDEL_REZKOSTI, PREDEL_REZKOSTI);
            }
            if (uLokalno > 0.0) c = lokalno(c, vUv);
            fragColor = vec4(clamp(obrabotat(c) + d, 0.0, 1.0), 1.0);
        }
    """.trimIndent() + "\n"

    val FRAG_YUY2 = SHAPKA + YUV + VYBORKA_YUY2 + GLAVNAYA
    val FRAG_NV12 = SHAPKA + YUV + VYBORKA_NV12 + GLAVNAYA
    val FRAG_RGBA = SHAPKA + VYBORKA_RGBA + GLAVNAYA
    val FRAG_P010 = SHAPKA + HDR + VYBORKA_P010 + GLAVNAYA
}
