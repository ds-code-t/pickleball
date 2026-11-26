package tools.dscode.store;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Warns when a Java glue class keeps a static field that a warm reload would not reset.
 * A compile-time constant ({@code static final} primitive or {@code String}, the
 * {@code ConstantValue} attribute) is quiet. The warning does not fail hydrate.
 * JDK 21 does not ship {@code java.lang.classfile} (final in JDK 24), so this reads
 * the class-file field table itself.
 */
public final class GlueStaticScan {
    static final String TEXT = "warn: static mutable field %s.%s will survive a warm reload; move it to scenario state";
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private GlueStaticScan() {
    }

    public static void scan(Path project, String sourcePath) {
        if (project == null || sourcePath == null || sourcePath.isBlank()) return;
        try {
            Path file = classFile(project, sourcePath);
            if (file == null) return;
            ClassFields parsed = read(Files.readAllBytes(file));
            for (Field field : parsed.fields()) {
                if ((field.access & ACC_STATIC) == 0) continue;
                if ((field.access & ACC_SYNTHETIC) != 0) continue;
                if (field.constant) continue;
                if (!WARNED.add(parsed.name() + "." + field.name)) continue;
                String message = TEXT.formatted(parsed.name(), field.name);
                System.err.println(message);
                StoreLog.log(project, "hydrate", "glue", message);
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing or unreadable class file does not fail hydrate.
        }
    }

    static Path classFile(Path project, String sourcePath) {
        String norm = sourcePath.replace('\\', '/');
        String marker;
        String kind;
        if (norm.contains("src/main/java/")) {
            marker = "src/main/java/";
            kind = "main";
        } else if (norm.contains("src/test/java/")) {
            marker = "src/test/java/";
            kind = "test";
        } else {
            return null;
        }
        String relative = norm.substring(norm.indexOf(marker) + marker.length());
        if (!relative.endsWith(".java")) return null;
        String classRelative = relative.substring(0, relative.length() - ".java".length()) + ".class";
        String sandbox = StoreScope.label(StoreScope.current());
        Path[] candidates = "main".equals(kind)
                ? new Path[] {
                        project.resolve("build/classes/java/main").resolve(classRelative),
                        project.resolve("target/classes").resolve(classRelative),
                        project.resolve(".pickleball/store/sandboxes").resolve(sandbox).resolve("classes").resolve(classRelative)
                }
                : new Path[] {
                        project.resolve("build/classes/java/test").resolve(classRelative),
                        project.resolve("target/test-classes").resolve(classRelative),
                        project.resolve(".pickleball/store/sandboxes").resolve(sandbox).resolve("classes").resolve(classRelative)
                };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static ClassFields read(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] className = new int[count];
        int index = 1;
        while (index < count) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> {
                    utf8[index] = in.readUTF();
                    index++;
                }
                case 7 -> {
                    className[index] = in.readUnsignedShort();
                    index++;
                }
                case 8, 16, 19, 20 -> {
                    in.readUnsignedShort();
                    index++;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> {
                    in.readInt();
                    index++;
                }
                case 5, 6 -> {
                    in.readLong();
                    index += 2;
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    index++;
                }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        int thisClass = in.readUnsignedShort();
        String binary = utf8[className[thisClass]];
        if (binary == null) throw new IOException("class name missing");
        String name = binary.replace('/', '.');
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) in.readUnsignedShort();
        int fields = in.readUnsignedShort();
        Field[] found = new Field[fields];
        for (int i = 0; i < fields; i++) {
            int access = in.readUnsignedShort();
            String fieldName = utf8[in.readUnsignedShort()];
            in.readUnsignedShort();
            int attributes = in.readUnsignedShort();
            boolean constant = false;
            for (int attribute = 0; attribute < attributes; attribute++) {
                String attributeName = utf8[in.readUnsignedShort()];
                int length = in.readInt();
                if ("ConstantValue".equals(attributeName) && (access & ACC_FINAL) != 0) constant = true;
                in.skipNBytes(length);
            }
            found[i] = new Field(access, fieldName == null ? "" : fieldName, constant);
        }
        return new ClassFields(name, found);
    }

    private record ClassFields(String name, Field[] fields) {
    }

    private record Field(int access, String name, boolean constant) {
    }
}
