package android.graphics;

import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.EncodedImageFormat;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;

public class Bitmap {
    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888, RGBA_F16, HARDWARE }
    public final org.jetbrains.skia.Bitmap sk;
    Bitmap(org.jetbrains.skia.Bitmap sk) { this.sk = sk; }
    public static Bitmap createBitmap(int w, int h, Config c) {
        org.jetbrains.skia.Bitmap b = new org.jetbrains.skia.Bitmap();
        b.allocPixels(ImageInfo.Companion.makeN32(w, h, ColorAlphaType.PREMUL));
        b.erase(0);
        return new Bitmap(b);
    }
    public int getWidth() { return sk.getWidth(); }
    public int getHeight() { return sk.getHeight(); }
    public void recycle() { }
    public boolean isRecycled() { return false; }
    /** Только для стенда: PNG. */
    public byte[] png() { return Image.Companion.makeFromBitmap(sk).encodeToData(EncodedImageFormat.PNG, 100).getBytes(); }
    Image image() { return Image.Companion.makeFromBitmap(sk); }
}
