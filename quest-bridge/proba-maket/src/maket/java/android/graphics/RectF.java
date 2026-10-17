package android.graphics;

public class RectF {
    public float left, top, right, bottom;
    public RectF() { }
    public RectF(float l, float t, float r, float b) { left = l; top = t; right = r; bottom = b; }
    public final float width() { return right - left; }
    public final float height() { return bottom - top; }
    public final float centerX() { return (left + right) / 2f; }
    public final float centerY() { return (top + bottom) / 2f; }
    org.jetbrains.skia.Rect sk() { return org.jetbrains.skia.Rect.Companion.makeLTRB(left, top, right, bottom); }
}
