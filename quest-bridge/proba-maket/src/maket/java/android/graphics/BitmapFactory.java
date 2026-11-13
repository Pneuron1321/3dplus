package android.graphics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.Image;
import org.jetbrains.skia.ImageInfo;

public class BitmapFactory {
    public static Bitmap decodeByteArray(byte[] d, int off, int len) {
        byte[] b = java.util.Arrays.copyOfRange(d, off, off + len);
        Image im = Image.Companion.makeFromEncoded(b);
        Bitmap out = Bitmap.createBitmap(im.getWidth(), im.getHeight(), Bitmap.Config.ARGB_8888);
        new org.jetbrains.skia.Canvas(out.sk, new org.jetbrains.skia.SurfaceProps()).drawImage(im, 0f, 0f);
        return out;
    }
    public static Bitmap decodeStream(InputStream in) {
        try { byte[] b = in.readAllBytes(); return decodeByteArray(b, 0, b.length); } catch (IOException e) { return null; }
    }
    public static Bitmap decodeFile(String put) {
        try { byte[] b = Files.readAllBytes(Paths.get(put)); return decodeByteArray(b, 0, b.length); } catch (IOException e) { return null; }
    }
}
