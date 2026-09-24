import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*; import java.nio.file.*; import java.util.*; import java.util.zip.*;
public class ApplyAT {
  // args: at.cfg outDir jar...
  public static void main(String[] a) throws Exception {
    Map<String, List<String[]>> rules = new HashMap<>();
    for (String line : Files.readAllLines(Paths.get(a[0]))) {
      int h = line.indexOf('#'); if (h >= 0) line = line.substring(0, h); line = line.trim();
      if (line.isEmpty()) continue;
      String[] p = line.split("[ 	]+");
      rules.computeIfAbsent(p[1].replace('.', '/'), k -> new ArrayList<>()).add(p);
    }
    Path out = Paths.get(a[1]); int n = 0;
    Set<String> done = new HashSet<>();
    for (int i = 2; i < a.length; i++) try (ZipFile z = new ZipFile(a[i])) {
      for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
        ZipEntry en = e.nextElement(); String name = en.getName();
        if (!name.endsWith(".class")) continue;
        String cls = name.substring(0, name.length() - 6);
        if (!rules.containsKey(cls) || !done.add(cls)) continue;
        ClassNode cn = new ClassNode(); new ClassReader(z.getInputStream(en)).accept(cn, 0);
        for (String[] r : rules.get(cls)) {
          int acc = acc(r[0]); boolean unfinal = r[0].endsWith("-f");
          if (r.length == 2) { cn.access = fix(cn.access, acc, unfinal); continue; }
          String m = r[2];
          if (m.contains("(")) { String mn = m.substring(0, m.indexOf('(')), md = m.substring(m.indexOf('('));
            for (MethodNode mm : cn.methods) if (mm.name.equals(mn) && mm.desc.equals(md)) mm.access = fix(mm.access, acc, unfinal);
          } else for (FieldNode f : cn.fields) if (m.equals("*") || f.name.equals(m)) f.access = fix(f.access, acc, unfinal);
        }
        for (InnerClassNode ic : cn.innerClasses) if (rules.containsKey(ic.name)) for (String[] r : rules.get(ic.name)) if (r.length == 2) ic.access = fix(ic.access, acc(r[0]), false);
        ClassWriter cw = new ClassWriter(0); cn.accept(cw);
        Path t = out.resolve(name); Files.createDirectories(t.getParent()); Files.write(t, cw.toByteArray()); n++;
      }
    }
    System.out.println("transformed " + n);
  }
  static int acc(String s) { return s.startsWith("public") ? Opcodes.ACC_PUBLIC : s.startsWith("protected") ? Opcodes.ACC_PROTECTED : 0; }
  static int fix(int access, int want, boolean unfinal) {
    int cur = access & 7; int rank = cur == Opcodes.ACC_PUBLIC ? 3 : cur == Opcodes.ACC_PROTECTED ? 2 : cur == 0 ? 1 : 0;
    int wr = want == Opcodes.ACC_PUBLIC ? 3 : want == Opcodes.ACC_PROTECTED ? 2 : 1;
    if (wr > rank) access = (access & ~7) | want;
    if (unfinal) access &= ~Opcodes.ACC_FINAL;
    return access;
  }
}
