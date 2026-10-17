package android.content.res;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class AssetManager {
    private final String dir;
    public AssetManager(String dir) { this.dir = dir; }
    public InputStream open(String imya) throws IOException { return new FileInputStream(dir + "/" + imya); }
}
