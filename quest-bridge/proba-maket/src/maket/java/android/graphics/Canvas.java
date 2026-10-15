package android.graphics;

import org.jetbrains.skia.FilterMipmap;
import org.jetbrains.skia.FilterMode;
import org.jetbrains.skia.MipmapMode;
import org.jetbrains.skia.RRect;
import org.jetbrains.skia.SurfaceProps;

/** Холст Android поверх холста Skia (Android сам рисует Skia — геометрия и сглаживание те же). */
public class Canvas {
    private final org.jetbrains.skia.Canvas c;
    public Canvas(Bitmap b) { c = new org.jetbrains.skia.Canvas(b.sk, new SurfaceProps()); }

    public void drawColor(int color) { c.clear(color); }
    public void drawColor(int color, PorterDuff.Mode mode) {
        if (mode == PorterDuff.Mode.CLEAR) { c.clear(0); return; }
        org.jetbrains.skia.Paint p = new org.jetbrains.skia.Paint();
        p.setColor(color);
        c.drawPaint(p);
    }
    public void drawRoundRect(RectF r, float rx, float ry, Paint p) {
        c.drawRRect(RRect.Companion.makeLTRB(r.left, r.top, r.right, r.bottom, rx, ry), p.sk());
    }
    public void drawRect(float l, float t, float r, float b, Paint p) { c.drawRect(org.jetbrains.skia.Rect.Companion.makeLTRB(l, t, r, b), p.sk()); }
    public void drawRect(RectF r, Paint p) { c.drawRect(r.sk(), p.sk()); }
    public void drawCircle(float x, float y, float r, Paint p) { c.drawCircle(x, y, r, p.sk()); }
    public void drawLine(float x0, float y0, float x1, float y1, Paint p) { c.drawLine(x0, y0, x1, y1, p.sk()); }
    public void drawPath(Path path, Paint p) { c.drawPath(path.sk, p.sk()); }
    public void drawText(String s, float x, float y, Paint p) {
        float w = p.getTextAlign() == Paint.Align.LEFT ? 0f : p.measureText(s);
        if (p.getTextAlign() == Paint.Align.CENTER) x -= w / 2f;
        else if (p.getTextAlign() == Paint.Align.RIGHT) x -= w;
        c.drawString(s, x, y, p.font(), p.sk());
    }
    public void drawBitmap(Bitmap b, Rect src, RectF dst, Paint p) {
        org.jetbrains.skia.Rect s = src == null ? org.jetbrains.skia.Rect.Companion.makeWH(b.getWidth(), b.getHeight())
            : org.jetbrains.skia.Rect.Companion.makeLTRB(src.left, src.top, src.right, src.bottom);
        c.drawImageRect(b.image(), s, dst.sk(), new FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            p == null ? null : p.sk(), true);
    }
    public void drawBitmap(Bitmap b, float x, float y, Paint p) {
        drawBitmap(b, null, new RectF(x, y, x + b.getWidth(), y + b.getHeight()), p);
    }
    public int save() { return c.save(); }
    public int saveLayer(float l, float t, float r, float b, Paint p) { return c.saveLayer(l, t, r, b, p == null ? null : p.sk()); }
    public void restore() { c.restore(); }
    public void restoreToCount(int n) { c.restoreToCount(n); }
    public void translate(float dx, float dy) { c.translate(dx, dy); }
    public void scale(float sx, float sy) { c.scale(sx, sy); }
    public void rotate(float deg) { c.rotate(deg); }
    public void rotate(float deg, float px, float py) { c.translate(px, py); c.rotate(deg); c.translate(-px, -py); }
    public void clipRect(float l, float t, float r, float b) { c.clipRect(org.jetbrains.skia.Rect.Companion.makeLTRB(l, t, r, b)); }
    /** Только для стенда: холст Skia. */
    public org.jetbrains.skia.Canvas sk() { return c; }
}
