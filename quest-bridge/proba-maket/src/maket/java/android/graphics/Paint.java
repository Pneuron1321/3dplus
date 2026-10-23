package android.graphics;

import org.jetbrains.skia.BlendMode;
import org.jetbrains.skia.Font;
import org.jetbrains.skia.FontEdging;
import org.jetbrains.skia.PaintMode;
import org.jetbrains.skia.PaintStrokeCap;
import org.jetbrains.skia.PaintStrokeJoin;

/** Кисть: состояние как у Android, в Skia переводится при каждом рисовании. */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1, FILTER_BITMAP_FLAG = 2;
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Align { LEFT, CENTER, RIGHT }
    public enum Join { MITER, ROUND, BEVEL }
    public enum Cap { BUTT, ROUND, SQUARE }

    private int color = 0xFF000000;
    private Style style = Style.FILL;
    private float strokeWidth = 0f, textSize = 12f;
    private Typeface typeface = Typeface.DEFAULT;
    private Align textAlign = Align.LEFT;
    private Join join = Join.MITER;
    private Cap cap = Cap.BUTT;
    private Xfermode xfermode;
    private Shader shader;
    private MaskFilter maskFilter;
    private float letterSpacing = 0f;
    final boolean aa;

    public Paint() { aa = false; }
    public Paint(int flags) { aa = (flags & ANTI_ALIAS_FLAG) != 0; }

    public int getColor() { return color; }
    public void setColor(int c) { color = c; }
    public int getAlpha() { return color >>> 24; }
    public void setAlpha(int a) { color = (color & 0x00FFFFFF) | ((a & 255) << 24); }
    public void setShadowLayer(float r, float dx, float dy, int c) { }   // тень на стенде не рисуется
    public Style getStyle() { return style; }
    public void setStyle(Style s) { style = s; }
    public float getStrokeWidth() { return strokeWidth; }
    public void setStrokeWidth(float w) { strokeWidth = w; }
    public float getTextSize() { return textSize; }
    public void setTextSize(float s) { textSize = s; }
    public Typeface getTypeface() { return typeface; }
    public Typeface setTypeface(Typeface t) { typeface = t == null ? Typeface.DEFAULT : t; return t; }
    public Align getTextAlign() { return textAlign; }
    public void setTextAlign(Align a) { textAlign = a; }
    public Join getStrokeJoin() { return join; }
    public void setStrokeJoin(Join j) { join = j; }
    public Cap getStrokeCap() { return cap; }
    public void setStrokeCap(Cap c) { cap = c; }
    public Xfermode getXfermode() { return xfermode; }
    public Xfermode setXfermode(Xfermode x) { xfermode = x; return x; }
    public Shader getShader() { return shader; }
    public Shader setShader(Shader s) { shader = s; return s; }

    public MaskFilter getMaskFilter() { return maskFilter; }
    public MaskFilter setMaskFilter(MaskFilter m) { maskFilter = m; return m; }
    public float getLetterSpacing() { return letterSpacing; }
    public void setLetterSpacing(float ls) { letterSpacing = ls; }

    Font font() {
        Font f = new Font(typeface.sk, textSize);
        f.setEdging(FontEdging.ANTI_ALIAS);
        f.setSubpixel(true);
        return f;
    }

    public float measureText(String s) {
        float w = font().measureTextWidth(s);
        return letterSpacing == 0f ? w : w + letterSpacing * textSize * s.length();
    }
    public float measureText(String s, int a, int b) { return measureText(s.substring(a, b)); }
    public float measureText(CharSequence s, int a, int b) { return measureText(s.toString().substring(a, b)); }

    org.jetbrains.skia.Paint sk() {
        org.jetbrains.skia.Paint p = new org.jetbrains.skia.Paint();
        p.setAntiAlias(aa);
        p.setColor(color);
        p.setMode(style == Style.FILL ? PaintMode.FILL : style == Style.STROKE ? PaintMode.STROKE : PaintMode.STROKE_AND_FILL);
        p.setStrokeWidth(strokeWidth);
        p.setStrokeJoin(join == Join.ROUND ? PaintStrokeJoin.ROUND : join == Join.BEVEL ? PaintStrokeJoin.BEVEL : PaintStrokeJoin.MITER);
        p.setStrokeCap(cap == Cap.ROUND ? PaintStrokeCap.ROUND : cap == Cap.SQUARE ? PaintStrokeCap.SQUARE : PaintStrokeCap.BUTT);
        if (xfermode instanceof PorterDuffXfermode) p.setBlendMode(BlendMode.valueOf(((PorterDuffXfermode) xfermode).mode.name()
            .replace("ADD", "PLUS")));
        if (shader != null) p.setShader(shader.sk());
        if (maskFilter != null) p.setMaskFilter(maskFilter.sk());
        return p;
    }
}
