package android.graphics;

import android.content.res.AssetManager;
import org.jetbrains.skia.FontMgr;
import org.jetbrains.skia.FontVariation;

/** Roboto, как у Android: обычный и жирный; моноширинный — DejaVu Sans Mono. Папка шрифтов — -Dshrift.
 *  Шрифты из assets (визор): createFromAsset и Builder с осью wght (вариативные TTF). */
public class Typeface {
    public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;
    final org.jetbrains.skia.Typeface sk;
    private Typeface(String put) { sk = FontMgr.Companion.getDefault().makeFromFile(put, 0); }
    private Typeface(org.jetbrains.skia.Typeface sk) { this.sk = sk; }
    private static String sh(String f) { return System.getProperty("shrift", ".") + "/" + f; }
    public static final Typeface DEFAULT = new Typeface(sh("Roboto-Regular.ttf"));
    public static final Typeface DEFAULT_BOLD = new Typeface(sh("Roboto-Bold.ttf"));
    public static final Typeface MONOSPACE = new Typeface("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf");
    static final Typeface MONOSPACE_BOLD = new Typeface("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf");
    public static Typeface create(Typeface t, int style) {
        boolean b = (style & BOLD) != 0;
        if (t == MONOSPACE || t == MONOSPACE_BOLD) return b ? MONOSPACE_BOLD : MONOSPACE;
        return b ? DEFAULT_BOLD : DEFAULT;
    }
    public static Typeface createFromAsset(AssetManager a, String put) {
        org.jetbrains.skia.Typeface t = FontMgr.Companion.getDefault().makeFromFile(a.put(put), 0);
        if (t == null) throw new RuntimeException("нет шрифта " + put);
        return new Typeface(t);
    }

    public static class Builder {
        private final String put;
        private String var;
        public Builder(AssetManager a, String put) { this.put = a.put(put); }
        public Builder setFontVariationSettings(String s) { var = s; return this; }
        public Builder setWeight(int w) { return this; }
        public Typeface build() {
            org.jetbrains.skia.Typeface t = FontMgr.Companion.getDefault().makeFromFile(put, 0);
            if (t == null) return null;
            if (var != null) {                                       // «'wght' 800»
                String[] p = var.replace("'", "").trim().split("\\s+");
                t = t.makeClone(new FontVariation(p[0], Float.parseFloat(p[1])));
            }
            return new Typeface(t);
        }
    }
}
