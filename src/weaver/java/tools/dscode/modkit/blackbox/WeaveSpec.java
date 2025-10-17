package tools.dscode.modkit.blackbox;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.*;
import java.util.regex.Pattern;

import static net.bytebuddy.matcher.ElementMatchers.*;

public final class WeaveSpec {

    private WeaveSpec() {}

    // ------------------------------
    // Debug & reproducibility toggles
    // ------------------------------
    private static final boolean DEBUG = Boolean.getBoolean("weaver.debug");
    private static final boolean FIXED_TIME = Boolean.parseBoolean(System.getProperty("weaver.fixedTime", "true"));
    private static final long EPOCH = 0L;

    private static void dbg(String msg) {
        if (DEBUG) System.out.println("[weave] " + msg);
    }

    private static String delta(byte[] before, byte[] after) {
        if (before == null || after == null) return "-";
        int d = after.length - before.length;
        return (d >= 0 ? "+" : "") + d + "B";
    }

    // ------------------------------
    // CLI
    // ------------------------------
    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        File inDir  = new File(req(a, "--in"));
        File outDir = new File(req(a, "--out"));
        String includesCsv = a.getOrDefault("--includes", "io.cucumber.");
        List<String> includes = normalizePrefixes(includesCsv);

        if (!inDir.isDirectory()) {
            throw new IllegalArgumentException("--in must be a directory of jars: " + inDir.getAbsolutePath());
        }
        outDir.mkdirs();

        List<File> jars = new ArrayList<>();
        try (var paths = Files.list(inDir.toPath())) {
            paths.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(p -> jars.add(p.toFile()));
        }
        jars.sort(Comparator.comparing(File::getName));

        // Optional resolver classpath for TypePool (often empty/unused)
        URLClassLoader resolverCl = buildResolverClassLoader(System.getProperty("weaver.classpath", ""));

        for (File jar : jars) {
            weaveOneJar(jar, new File(outDir, jar.getName()), includes, resolverCl);
        }
    }

    // ------------------------------
    // Weave one jar
    // ------------------------------
    private static void weaveOneJar(File in, File out, List<String> includes, ClassLoader resolverCl) throws Exception {
        int classes = 0, matched = 0, transformed = 0;

        try (JarInputStream jis = new JarInputStream(new BufferedInputStream(new FileInputStream(in)));
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {

            JarEntry e;
            while ((e = jis.getNextJarEntry()) != null) {
                String name = e.getName();

                // Strip broken/irrelevant signature artifacts early
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".DSA") || name.endsWith(".RSA"))) {
                    continue;
                }

                // Only transform .class entries; copy others as-is
                if (!name.endsWith(".class")) {
                    copyRawEntry(jos, e, jis);
                    continue;
                }

                classes++;

                // Derive the binary type name from the path
                String typeName = name.substring(0, name.length() - ".class".length()).replace('/', '.');

                // Filter by includes (package prefixes)
                if (!matchesAnyPrefix(typeName, includes)) {
                    byte[] orig = readAllBytes(jis);
                    put(jos, name, orig, eTime(e));
                    dbg("class=" + typeName + " matched=N transformed=N");
                    continue;
                }

                matched++;

                byte[] original = readAllBytes(jis);

                // Build a locator and type pool over this single class
                Map<String, byte[]> map = Collections.singletonMap(typeName, original);
                ClassFileLocator locator = new ClassFileLocator.Simple(map);

                TypePool typePool = new TypePool.Default.WithLazyResolution(
                        new TypePool.CacheProvider.Simple(),
                        locator,
                        TypePool.Default.ReaderMode.FAST
                );
                TypePool.Resolution res = typePool.describe(typeName);
                if (!res.isResolved()) {
                    // Copy through if we couldn't resolve (very rare)
                    put(jos, name, original, eTime(e));
                    dbg("class=" + typeName + " matched=Y transformed=N (unresolved)");
                    continue;
                }
                TypeDescription td = res.resolve();

                DynamicType.Builder<?> builder = new ByteBuddy().redefine(td, locator);

                // ------------------------------
                // Your transformations go here
                // 1) No-op constructor marker (safe)
                builder = builder.visit(Advice.to(CtorSimple.class).on(isConstructor()));

                // 2) Post-return list normalizer on tags() / getTags() style methods
                // Build this as a METHOD matcher (fixes your Junction<?> type error)
                ElementMatcher<? super MethodDescription> returnsListOrCollection =
                        returns(isSubTypeOf(java.util.List.class).or(isSubTypeOf(java.util.Collection.class)));

                builder = builder.visit(
                        Advice.to(ReturnListAfter.class)
                                .on( (named("tags").or(named("getTags"))).and(returnsListOrCollection) )
                );

                // (Add more visits above; keep them guarded by precise matchers)

                DynamicType.Unloaded<?> unloaded = builder.make();
                byte[] after = unloaded.getBytes();

                put(jos, name, after, eTime(e));
                transformed++;
                dbg("class=" + typeName + " matched=Y transformed=Y delta=" + delta(original, after));
            }
        }

        System.out.println("[weave:summary] jar=" + in.getName()
                + " classes=" + classes
                + " matched=" + matched
                + " transformed=" + transformed);
    }

    // ------------------------------
    // Advice snippets (safe defaults)
    // ------------------------------

    /** Safe no-op marker for constructors. */
    public static class CtorSimple {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        static void enter() { /* no-op marker */ }
    }

    /**
     * Safe handler for methods returning List-like values.
     * Currently does nothing (zero-alloc fast paths); uncomment if you want immutable wrapping.
     */
    public static class ReturnListAfter {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit(@Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret) {
            if (!(ret instanceof java.util.List<?> list)) return;
            if (list.isEmpty()) return; // fast path; leave as-is

            // Example: enforce immutability (uncomment to apply)
            // ret = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(list));
        }
    }

    // ------------------------------
    // Utilities
    // ------------------------------
    private static List<String> normalizePrefixes(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static boolean matchesAnyPrefix(String typeName, List<String> prefixes) {
        for (String p : prefixes) {
            if (typeName.startsWith(p)) return true;
        }
        return false;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (k.startsWith("--")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    m.put(k, args[++i]);
                } else {
                    m.put(k, "true");
                }
            }
        }
        return m;
    }

    private static String req(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Missing required arg: " + key);
        }
        return v;
    }

    private static URLClassLoader buildResolverClassLoader(String cp) {
        if (cp == null || cp.isBlank()) return new URLClassLoader(new URL[0], WeaveSpec.class.getClassLoader());
        String pathSep = File.pathSeparator;
        String[] parts = cp.split(Pattern.quote(pathSep));
        List<URL> urls = new ArrayList<>();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p);
            if (!f.exists()) continue;
            try { urls.add(f.toURI().toURL()); } catch (Exception ignored) {}
        }
        return new URLClassLoader(urls.toArray(new URL[0]), WeaveSpec.class.getClassLoader());
    }

    private static long eTime(JarEntry e) {
        return FIXED_TIME ? EPOCH : (e == null ? System.currentTimeMillis() : e.getTime());
    }

    private static void put(JarOutputStream jos, String name, byte[] bytes, long time) throws IOException {
        JarEntry out = new JarEntry(name);
        out.setTime(time);
        jos.putNextEntry(out);
        jos.write(bytes);
        jos.closeEntry();
    }

    private static void copyRawEntry(JarOutputStream jos, JarEntry e, InputStream in) throws IOException {
        JarEntry out = new JarEntry(e.getName());
        out.setTime(eTime(e));
        jos.putNextEntry(out);
        in.transferTo(jos);
        jos.closeEntry();
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, in.available()));
        in.transferTo(bos);
        return bos.toByteArray();
    }
}
