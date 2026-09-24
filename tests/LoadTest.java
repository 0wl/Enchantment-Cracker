import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads (and links) every class in the built mod jar against the real Forge runtime
 * classes, which resolves superclasses, interfaces and signatures. Anything that would
 * throw NoClassDefFoundError when Forge loads the mod shows up here.
 *
 * Usage: LoadTest <modJar> <runtimeClassesDir>
 */
public class LoadTest {

    public static void main(String[] args) throws Exception {
        File jar = new File(args[0]);
        File runtime = new File(args[1]);

        URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toURI().toURL(), runtime.toURI().toURL()},
                LoadTest.class.getClassLoader());

        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar)) {
            for (ZipEntry entry : java.util.Collections.list(zf.entries())) {
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    names.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
        }

        int loaded = 0;
        int failed = 0;
        for (String name : names) {
            try {
                Class<?> type = Class.forName(name, false, loader);
                // Touch the members so the signatures are resolved too.
                type.getDeclaredMethods();
                type.getDeclaredFields();
                type.getDeclaredConstructors();
                if (type.getSuperclass() != null) {
                    type.getSuperclass().getName();
                }
                for (Class<?> itf : type.getInterfaces()) {
                    itf.getName();
                }
                loaded++;
            } catch (Throwable t) {
                failed++;
                System.out.println("  FAIL " + name + " -> " + t);
            }
        }
        loader.close();

        System.out.println("loaded: " + loaded + ", failed: " + failed);
        System.out.println(failed == 0 ? "LOAD OK" : "LOAD FAILED");
        if (failed != 0) {
            System.exit(1);
        }
    }
}
