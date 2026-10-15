package android.graphics;

import org.jetbrains.skia.FontMgr;

/** Roboto, как у Android: обычный и жирный; моноширинный — DejaVu Sans Mono. Папка шрифтов — -Dshrift. */
public class Typeface {
    public static final int NORMAL = 0, BOLD = 1, ITALIC = 2, BOLD_ITALIC = 3;
    final org.jetbrains.skia.Typeface sk;
    private Typeface(String put) { sk = FontMgr.Companion.getDefault().makeFromFile(put, 0); }
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
}
