package zapusk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Запуск стенда: свой загрузчик классов; android.*, которых нет в подделке, — пустые классы (типы полей
 * Razvilka, Gosti… нужны рефлексии, их методы стенд не зовёт).
 */
public class Zapusk extends URLClassLoader {
    static { registerAsParallelCapable(); }

    Zapusk(URL[] urls) { super(urls, ClassLoader.getPlatformClassLoader()); }

    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException e) {
            if (!name.startsWith("android.") && !name.startsWith("dalvik.")) throw e;
            byte[] b = pustoy(name.replace('.', '/'));
            return defineClass(name, b, 0, b.length);
        }
    }

    static byte[] pustoy(String imya) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bo);
            d.writeInt(0xCAFEBABE); d.writeShort(0); d.writeShort(52);
            d.writeShort(5);                                            // пул: 1 имя, 2 класс, 3 Object, 4 класс
            d.writeByte(1); d.writeUTF(imya);
            d.writeByte(7); d.writeShort(1);
            d.writeByte(1); d.writeUTF("java/lang/Object");
            d.writeByte(7); d.writeShort(3);
            d.writeShort(0x0021); d.writeShort(2); d.writeShort(4);
            d.writeShort(0); d.writeShort(0); d.writeShort(0); d.writeShort(0);
            return bo.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public static void main(String[] a) throws Exception {
        List<URL> u = new ArrayList<>();
        for (String p : System.getProperty("java.class.path").split(File.pathSeparator)) u.add(new File(p).toURI().toURL());
        Zapusk z = new Zapusk(u.toArray(new URL[0]));
        Thread.currentThread().setContextClassLoader(z);
        z.loadClass("com.questclaude.bridge.KadrMaketKt").getMethod("main", String[].class).invoke(null, (Object) a);
    }
}
