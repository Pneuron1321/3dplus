package android.graphics;

/** Заливка кисти (стенд): только то, что нужно плашкам. */
public class Shader {
    public enum TileMode { CLAMP, REPEAT, MIRROR }
    org.jetbrains.skia.Shader sk() { return null; }
}
