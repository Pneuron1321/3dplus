package android.graphics;

import org.jetbrains.skia.FilterBlurMode;

/** Размытие (тени карточек визора): радиус Android ≈ 2 сигмы Skia. */
public class BlurMaskFilter extends MaskFilter {
    public enum Blur { NORMAL, SOLID, OUTER, INNER }
    private final float r;
    public BlurMaskFilter(float radius, Blur style) { r = radius; }
    @Override org.jetbrains.skia.MaskFilter sk() {
        return org.jetbrains.skia.MaskFilter.Companion.makeBlur(FilterBlurMode.NORMAL, r / 2f, true);
    }
}
