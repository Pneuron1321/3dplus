package android.graphics;

import org.jetbrains.skia.GradientStyle;

/** Радиальный градиент Android поверх Skia (фон сцен визора). */
public class RadialGradient extends Shader {
    private final float x, y, r;
    private final int c0, c1;
    public RadialGradient(float x, float y, float r, int c0, int c1, TileMode tile) { this.x = x; this.y = y; this.r = r; this.c0 = c0; this.c1 = c1; }
    @Override org.jetbrains.skia.Shader sk() {
        return org.jetbrains.skia.Shader.Companion.makeRadialGradient(x, y, r, new int[] { c0, c1 }, null, GradientStyle.Companion.getDEFAULT());
    }
}
