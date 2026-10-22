package com.questclaude.most3

// Шейдеры картинки (GLSL ES 3.00). Та же математика — в KartinkaCpu (Kartinka.kt), тесты сверяют.
// Сборка фрагментного: SHAPKA + YUV + выборка формата + GLAVNAYA. Выборка даёт rgbVzyat(uv) (0..1, полный диапазон)
// и shag() = 1/размер кадра в пикселях (через textureSize — передавать не нужно).
object Shader {
    // Общие uniform-ы всех трёх вариантов (сэмплеры — отдельно, по формату).
    val UNIFORMY = listOf("uYarkost", "uKontrast", "uNasyshch", "uGamma", "uTeni", "uAvtoTeni", "uRezkost", "uPolnyj", "uSyroe")
    val SAMPLERY_YUY2 = listOf("uTekYuy2")          // блок 0: GL_RGBA8 W/2×H, (Y0,U,Y1,V)
    val SAMPLERY_NV12 = listOf("uTekY", "uTekUV")   // блок 0: GL_R8 W×H; блок 1: GL_RG8 W/2×H/2
    val SAMPLERY_RGBA = listOf("uTekRgba")          // блок 0: GL_RGBA8 W×H

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
        uniform float uPolnyj;    // 1 = YUV полный диапазон, 0 = ограниченный 16-235/16-240
        uniform float uSyroe;     // 1 = без обработки (замер средней яркости)
        const vec3 VES = vec3(0.2126, 0.7152, 0.0722);
        const float K_TENEJ = 3.0;
        const float PREDEL_REZKOSTI = 0.1;
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

    // Конвейер — строго как KartinkaCpu.obrabotat. Резкость: дельта по яркости ВХОДА, прибавляется в конце.
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
        float yarkostVzyat(vec2 uv) { return dot(rgbVzyat(uv), VES); }
        void main() {
            vec3 c = rgbVzyat(vUv);
            if (uSyroe > 0.5) { fragColor = vec4(c, 1.0); return; }
            float d = 0.0;
            if (uRezkost > 0.0) {
                vec2 s = shag();
                float sr = 0.25 * (yarkostVzyat(vUv + vec2(s.x, 0.0)) + yarkostVzyat(vUv - vec2(s.x, 0.0))
                                 + yarkostVzyat(vUv + vec2(0.0, s.y)) + yarkostVzyat(vUv - vec2(0.0, s.y)));
                d = clamp(uRezkost * (dot(c, VES) - sr), -PREDEL_REZKOSTI, PREDEL_REZKOSTI);
            }
            fragColor = vec4(clamp(obrabotat(c) + d, 0.0, 1.0), 1.0);
        }
    """.trimIndent() + "\n"

    val FRAG_YUY2 = SHAPKA + YUV + VYBORKA_YUY2 + GLAVNAYA
    val FRAG_NV12 = SHAPKA + YUV + VYBORKA_NV12 + GLAVNAYA
    val FRAG_RGBA = SHAPKA + VYBORKA_RGBA + GLAVNAYA
}
