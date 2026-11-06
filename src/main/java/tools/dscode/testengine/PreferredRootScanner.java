package tools.dscode.testengine;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds the one concrete {@link PickleballRunner} in a preferred test-output
 * directory by reading class-file headers. Directory roots used to
 * {@code Class.forName} every class. Jars stay on that loader path.
 */
final class PreferredRootScanner {
    static final String RUNNER = "tools/dscode/testengine/PickleballRunner";
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_MODULE = 0x8000;

    private PreferredRootScanner() {
    }

    record Header(String name, String superName, boolean concrete) {
    }

    static List<String> concreteRunnerNames(Path root) throws IOException {
        Path start = realDirectory(root);
        Map<String, Header> local = new HashMap<>();
        try (var paths = Files.walk(start)) {
            for (Path file : paths.toList()) {
                if (!isClassFile(file)) continue;
                Header header = readHeader(file);
                if (header == null || header.name == null) continue;
                local.put(header.name, header);
            }
        }
        List<Path> classpath = classpathEntries();
        Map<String, String> externalSuper = new HashMap<>();
        Map<Path, JarFile> jars = new HashMap<>();
        try {
            List<String> found = new ArrayList<>();
            for (Header header : local.values()) {
                if (!header.concrete) continue;
                if (extendsRunner(header, local, externalSuper, classpath, jars, new java.util.HashSet<>())) {
                    found.add(header.name);
                }
            }
            found.sort(Comparator.naturalOrder());
            return List.copyOf(found);
        } finally {
            for (JarFile jar : jars.values()) {
                if (jar != null) jar.close();
            }
        }
    }

    static Class<?> loadConcreteRunner(Path root) throws IOException {
        List<String> names = concreteRunnerNames(root);
        if (names.isEmpty()) return null;
        if (names.size() > 1) {
            throw multiple(root, names);
        }
        return loadIfRunner(names.get(0));
    }

    static IllegalStateException multiple(Path root, List<String> binaryNames) {
        return new IllegalStateException(
                "Expected exactly one concrete subclass of " + PickleballRunner.class.getName()
                        + " in classpath root " + root.toUri() + " but found " + dotted(binaryNames)
        );
    }

    static String dotted(List<String> binaryNames) {
        List<String> names = new ArrayList<>();
        for (String binary : binaryNames) {
            names.add(binary.replace('/', '.'));
        }
        return names.toString();
    }

    static Set<String> stepPackages(Path root) throws IOException {
        Path start = realDirectory(root);
        Set<String> packages = new LinkedHashSet<>();
        try (var paths = Files.walk(start)) {
            for (Path file : paths.toList()) {
                if (!isClassFile(file)) continue;
                Path relative = start.relativize(file);
                if (relative.getParent() == null) continue;
                String packageName = relative.getParent().toString().replace('\\', '.').replace('/', '.');
                if (DynamicSuiteBootstrap.isStepPackage(packageName)) {
                    packages.add(packageName);
                }
            }
        }
        return packages;
    }

    private static boolean extendsRunner(
            Header header,
            Map<String, Header> local,
            Map<String, String> externalSuper,
            List<Path> classpath,
            Map<Path, JarFile> jars,
            Set<String> seen
    ) {
        if (header == null || header.name == null || !seen.add(header.name)) return false;
        String superName = header.superName;
        if (superName == null || superName.isBlank()) return false;
        if (RUNNER.equals(superName)) return true;
        if (isPlatformSuper(superName)) return false;
        Header next = local.get(superName);
        if (next != null) {
            return extendsRunner(next, local, externalSuper, classpath, jars, seen);
        }
        String resolved = externalSuper.computeIfAbsent(
                superName, name -> superFromClasspath(name, classpath, jars)
        );
        if (resolved == null) return false;
        return extendsRunner(new Header(superName, resolved, true), local, externalSuper, classpath, jars, seen);
    }

    private static boolean isPlatformSuper(String superName) {
        return "java/lang/Object".equals(superName)
                || superName.startsWith("java/")
                || superName.startsWith("jdk/")
                || superName.startsWith("sun/");
    }

    private static String superFromClasspath(String binaryName, List<Path> classpath, Map<Path, JarFile> jars) {
        String relative = binaryName + ".class";
        for (Path entry : classpath) {
            if (!Files.isDirectory(entry)) continue;
            Path file = entry.resolve(relative);
            if (!Files.isRegularFile(file)) continue;
            Header header = readHeader(file);
            return header == null ? null : header.superName;
        }
        for (Path entry : classpath) {
            if (!isJar(entry)) continue;
            JarFile jar = jars.computeIfAbsent(entry, PreferredRootScanner::openJar);
            if (jar == null) continue;
            JarEntry item = jar.getJarEntry(relative);
            if (item == null) continue;
            try (InputStream input = jar.getInputStream(item)) {
                Header header = parse(input.readAllBytes());
                return header == null ? null : header.superName;
            } catch (IOException failure) {
                return null;
            }
        }
        return null;
    }

    private static JarFile openJar(Path path) {
        try {
            return new JarFile(path.toFile());
        } catch (IOException failure) {
            return null;
        }
    }

    private static boolean isJar(Path entry) {
        Path name = entry.getFileName();
        return name != null && name.toString().endsWith(".jar") && Files.isRegularFile(entry);
    }

    private static List<Path> classpathEntries() {
        List<Path> entries = new ArrayList<>();
        String raw = System.getProperty("java.class.path", "");
        for (String entry : raw.split(java.io.File.pathSeparator, -1)) {
            if (entry.isBlank()) continue;
            entries.add(Path.of(entry).toAbsolutePath().normalize());
        }
        return entries;
    }

    static Class<?> loadNamed(String binaryName) {
        return loadIfRunner(binaryName);
    }

    private static Class<?> loadIfRunner(String binaryName) {
        try {
            Class<?> type = Class.forName(binaryName.replace('/', '.'), false, loader());
            if (type == null
                    || !PickleballRunner.class.isAssignableFrom(type)
                    || type == PickleballRunner.class
                    || type.isInterface()
                    || Modifier.isAbstract(type.getModifiers())) {
                return null;
            }
            return type;
        } catch (ClassNotFoundException | LinkageError failure) {
            return null;
        }
    }

    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) return context;
        ClassLoader own = PreferredRootScanner.class.getClassLoader();
        return own == null ? ClassLoader.getSystemClassLoader() : own;
    }

    private static boolean isClassFile(Path file) {
        if (!Files.isRegularFile(file)) return false;
        String name = file.getFileName().toString();
        if (!name.endsWith(".class")) return false;
        return !name.equals("module-info.class") && !name.equals("package-info.class");
    }

    private static Path realDirectory(Path root) throws IOException {
        return root.toRealPath();
    }

    static Header readHeader(Path file) {
        try {
            return parse(Files.readAllBytes(file));
        } catch (IOException failure) {
            return null;
        }
    }

    static Header parse(byte[] data) {
        try {
            if (data == null || data.length < 8) return null;
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            if (buffer.getInt() != 0xCAFEBABE) return null;
            buffer.getShort();
            buffer.getShort();
            int count = Short.toUnsignedInt(buffer.getShort());
            String[] utf = new String[count];
            int[] classUtf = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = Byte.toUnsignedInt(buffer.get());
                switch (tag) {
                    case 1 -> {
                        int length = Short.toUnsignedInt(buffer.getShort());
                        byte[] raw = new byte[length];
                        buffer.get(raw);
                        utf[index] = new String(raw, StandardCharsets.UTF_8);
                    }
                    case 7 -> classUtf[index] = Short.toUnsignedInt(buffer.getShort());
                    case 8, 16, 19, 20 -> buffer.getShort();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> buffer.getInt();
                    case 5, 6 -> {
                        buffer.getLong();
                        index++;
                    }
                    case 15 -> {
                        buffer.get();
                        buffer.getShort();
                    }
                    default -> {
                        return null;
                    }
                }
            }
            int access = Short.toUnsignedInt(buffer.getShort());
            if ((access & ACC_MODULE) != 0) return null;
            int thisClass = Short.toUnsignedInt(buffer.getShort());
            int superClass = Short.toUnsignedInt(buffer.getShort());
            String name = utfAt(utf, classUtf, thisClass);
            String superName = superClass == 0 ? null : utfAt(utf, classUtf, superClass);
            boolean concrete = (access & (ACC_INTERFACE | ACC_ABSTRACT)) == 0;
            return new Header(name, superName, concrete);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String utfAt(String[] utf, int[] classUtf, int classIndex) {
        if (classIndex <= 0 || classIndex >= classUtf.length) return null;
        int utfIndex = classUtf[classIndex];
        if (utfIndex <= 0 || utfIndex >= utf.length) return null;
        return utf[utfIndex];
    }
}
