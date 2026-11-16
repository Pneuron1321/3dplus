package android.graphics;

public class Path {
    final org.jetbrains.skia.Path sk = new org.jetbrains.skia.Path();
    public void moveTo(float x, float y) { sk.moveTo(x, y); }
    public void lineTo(float x, float y) { sk.lineTo(x, y); }
    public void close() { sk.closePath(); }
    public void reset() { sk.reset(); }
}
