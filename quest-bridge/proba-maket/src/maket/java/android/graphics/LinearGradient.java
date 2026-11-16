package android.graphics;

import org.jetbrains.skia.GradientStyle;

/** Линейный градиент Android поверх Skia (эквалайзер полоски «сейчас играет»). */
public class LinearGradient extends Shader {
    private final float x0, y0, x1, y1;
    private final int[] colors;
    private final float[] pos;

    public LinearGradient(float x0, float y0, float x1, float y1, int[] colors, float[] positions, TileMode tile) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.colors = colors; this.pos = positions;
    }

    public LinearGradient(float x0, float y0, float x1, float y1, int c0, int c1, TileMode tile) {
        this(x0, y0, x1, y1, new int[] { c0, c1 }, null, tile);
    }

    @Override org.jetbrains.skia.Shader sk() {
        return org.jetbrains.skia.Shader.Companion.makeLinearGradient(x0, y0, x1, y1, colors, pos, GradientStyle.Companion.getDEFAULT());
    }
}
