package android.graphics;

public class PorterDuffXfermode extends Xfermode {
    final PorterDuff.Mode mode;
    public PorterDuffXfermode(PorterDuff.Mode mode) { this.mode = mode; }
}
