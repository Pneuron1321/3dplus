package android.content;

import android.content.res.AssetManager;

/** Только то, что берёт Nalozhenie: картинки из assets (папка — -Dassets). */
public class Context {
    public AssetManager getAssets() { return new AssetManager(System.getProperty("assets", ".")); }
}
