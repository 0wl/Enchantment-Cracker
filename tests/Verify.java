import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Link-checks a built mod jar against the real Forge runtime classes.
 *
 * <p>javac already validates everything it compiled against, but this repeats the check
 * against the exact jars the game will load, walking the class hierarchy the way the JVM
 * does. Anything reported here would be a NoSuchMethodError at runtime.
 *
 * Usage: Verify <modJar> <runtimeClassesDir>
 */
public class Verify {

    static Path runtimeRoot;
    static final Map<String, ClassInfo> cache = new HashMap<>();
    static final List<String> problems = new ArrayList<>();

    static final class ClassInfo {
        String name;
        String superName;
        String[] interfaces;
        Set<String> methods = new HashSet<>();  // name + desc
        Set<String> fields = new HashSet<>();   // name + ':' + desc
    }

    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(args[0]);
        runtimeRoot = Paths.get(args[1]);

        int checkedMethods = 0;
        int checkedFields = 0;
        Set<String> ownClasses = new LinkedHashSet<>();

        try (ZipFile zf = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zf.entries())) {
                if (entry.getName().endsWith(".class")) {
                    ownClasses.add(entry.getName().substring(0, entry.getName().length() - 6));
                }
            }
            for (ZipEntry entry : java.util.Collections.list(zf.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zf.getInputStream(entry)) {
                    ClassReader cr = new ClassReader(in);
                    int[] counts = check(cr, ownClasses);
                    checkedMethods += counts[0];
                    checkedFields += counts[1];
                }
            }
        }

        System.out.println("classes in jar: " + ownClasses.size());
        System.out.println("method references checked: " + checkedMethods);
        System.out.println("field references checked:  " + checkedFields);
        if (problems.isEmpty()) {
            System.out.println("LINK OK");
        } else {
            System.out.println("PROBLEMS: " + problems.size());
            for (String problem : problems) {
                System.out.println("  " + problem);
            }
            System.exit(1);
        }
    }

    static int[] check(ClassReader cr, Set<String> ownClasses) {
        final int[] counts = new int[2];
        final String owner = cr.getClassName();
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String target, String mname, String mdesc, boolean itf) {
                        if (skip(target, ownClasses)) {
                            return;
                        }
                        counts[0]++;
                        if (!hasMethod(target, mname, mdesc)) {
                            problems.add(owner + "." + name + " -> missing method "
                                    + target + "." + mname + mdesc);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int op, String target, String fname, String fdesc) {
                        if (skip(target, ownClasses)) {
                            return;
                        }
                        counts[1]++;
                        if (!hasField(target, fname, fdesc)) {
                            problems.add(owner + "." + name + " -> missing field "
                                    + target + "." + fname + " : " + fdesc);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return counts;
    }

    /** Only Minecraft and Forge names are interesting; the JDK and our own classes are fine. */
    static boolean skip(String internalName, Set<String> ownClasses) {
        if (internalName.startsWith("[")) {
            return true;
        }
        if (ownClasses.contains(internalName)) {
            return true;
        }
        return !(internalName.startsWith("net/minecraft/")
                || internalName.startsWith("net/minecraftforge/")
                || internalName.startsWith("com/mojang/blaze3d/"));
    }

    static boolean hasMethod(String owner, String name, String desc) {
        ClassInfo info = load(owner);
        if (info == null) {
            problems.add("missing class " + owner);
            return true; // already reported
        }
        if (info.methods.contains(name + desc)) {
            return true;
        }
        if (info.superName != null && hasMethodQuiet(info.superName, name, desc)) {
            return true;
        }
        for (String itf : info.interfaces) {
            if (hasMethodQuiet(itf, name, desc)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasMethodQuiet(String owner, String name, String desc) {
        ClassInfo info = load(owner);
        if (info == null) {
            return false;
        }
        if (info.methods.contains(name + desc)) {
            return true;
        }
        if (info.superName != null && hasMethodQuiet(info.superName, name, desc)) {
            return true;
        }
        for (String itf : info.interfaces) {
            if (hasMethodQuiet(itf, name, desc)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasField(String owner, String name, String desc) {
        ClassInfo info = load(owner);
        if (info == null) {
            problems.add("missing class " + owner);
            return true;
        }
        return hasFieldQuiet(owner, name, desc);
    }

    static boolean hasFieldQuiet(String owner, String name, String desc) {
        ClassInfo info = load(owner);
        if (info == null) {
            return false;
        }
        if (info.fields.contains(name + ":" + desc)) {
            return true;
        }
        if (info.superName != null && hasFieldQuiet(info.superName, name, desc)) {
            return true;
        }
        for (String itf : info.interfaces) {
            if (hasFieldQuiet(itf, name, desc)) {
                return true;
            }
        }
        return false;
    }

    static ClassInfo load(String internalName) {
        if (cache.containsKey(internalName)) {
            return cache.get(internalName);
        }
        cache.put(internalName, null);
        Path file = runtimeRoot.resolve(internalName.replace('/', File.separatorChar) + ".class");
        byte[] bytes;
        try {
            if (Files.isRegularFile(file)) {
                bytes = Files.readAllBytes(file);
            } else {
                // JDK classes (java.lang.Iterable and friends) come from the running JVM.
                try (InputStream in = ClassLoader.getSystemResourceAsStream(internalName + ".class")) {
                    if (in == null) {
                        return null;
                    }
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        out.write(buffer, 0, n);
                    }
                    bytes = out.toByteArray();
                }
            }
        } catch (IOException e) {
            return null;
        }
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassInfo info = new ClassInfo();
            info.name = cr.getClassName();
            info.superName = cr.getSuperName();
            info.interfaces = cr.getInterfaces();
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                    info.methods.add(n + d);
                    return null;
                }

                @Override
                public org.objectweb.asm.FieldVisitor visitField(int a, String n, String d, String s, Object v) {
                    info.fields.add(n + ":" + d);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            cache.put(internalName, info);
            return info;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
