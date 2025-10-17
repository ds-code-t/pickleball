package tools.dscode.modkit.blackbox;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Offline entry point for ByteBuddy weaving.
 *
 * Usage:
 *   java -Dweaver.classpath=<jar1{sep}jar2{sep}...>
 *        -Dweaver.targets=io.cucumber.core.,io.cucumber.messages.,io.cucumber.gherkin.,io.cucumber.plugin.
 *        tools.dscode.modkit.blackbox.OfflineWeaverMain <input-jar> <output-jar>
 *
 * Notes:
 * - {sep} is the platform path separator (';' on Windows, ':' on *nix).
 * - TARGET_PREFIXES defaults to "io.cucumber." (all cucumber packages).
 */
public final class OfflineWeaverMain {
    // Target prefixes to weave (normalized to end with '.')
    private static final String[] TARGET_PREFIXES = parseTargets(
            System.getProperty("weaver.targets", "io.cucumber.")
    );

    static {
        System.out.println("[WeaverMain][static] OfflineWeaverMain class loaded");
        System.out.println("[WeaverMain] TARGET_PREFIXES=" + java.util.Arrays.toString(TARGET_PREFIXES));
    }

    private OfflineWeaverMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: OfflineWeaverMain <input-jar> <output-jar>");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);

        System.out.println("[WeaverMain] Input JAR:  " + in.getAbsolutePath());
        System.out.println("[WeaverMain] Output JAR: " + out.getAbsolutePath());

        if (!in.isFile()) {
            throw new IllegalArgumentException("Input jar does not exist: " + in.getAbsolutePath());
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create output directory: " + parent);
        }

        // Class resolution: input jar + all jars from -Dweaver.classpath + JDK/system
        ClassFileLocator locatorForResolution = buildLocator(in);

        // Register DSL/plans
        OfflineWeaver.initialize();

        int totalClasses = 0;
        int targetedClasses = 0;
        int errors = 0;

        try (JarFile inJar = new JarFile(in);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(out))) {

            Enumeration<JarEntry> entries = inJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                JarEntry newEntry = new JarEntry(entry.getName());
                newEntry.setTime(entry.getTime()); // preserve timestamp
                jos.putNextEntry(newEntry);

                if (entry.isDirectory()) {
                    jos.closeEntry();
                    continue;
                }

                byte[] outBytes;
                try {
                    byte[] inBytes = inJar.getInputStream(entry).readAllBytes();

                    if (entry.getName().endsWith(".class")) {
                        totalClasses++;

                        String className = entry.getName()
                                .replace('/', '.')
                                .substring(0, entry.getName().length() - ".class".length());

                        if (shouldTarget(className)) {
                            targetedClasses++;
                            System.out.println("[WeaverMain] Targeting class: " + className);

                            // This class' bytes (for rebase)
                            ClassFileLocator thisClassBytes = ClassFileLocator.Simple.of(className, inBytes);
                            // Combine with broader resolver
                            ClassFileLocator compound = new ClassFileLocator.Compound(thisClassBytes, locatorForResolution);

                            TypePool pool = TypePool.Default.of(compound);
                            TypeDescription typeDesc = pool.describe(className).resolve();

                            // Byte Buddy returns Builder<?>; our OfflineWeaver expects Builder<Object>
                            @SuppressWarnings("unchecked")
                            DynamicType.Builder<Object> builder =
                                    (DynamicType.Builder<Object>) (DynamicType.Builder<?>) new ByteBuddy().rebase(typeDesc, compound);

                            System.out.println("[WeaverMain]  applying plans to: " + className);

                            // Cast the argument/return to match your existing OfflineWeaver signature
                            @SuppressWarnings("unchecked")
                            DynamicType.Builder<Object> outBuilder =
                                    (DynamicType.Builder<Object>) OfflineWeaver.applyTo(builder, typeDesc, pool);

                            outBytes = outBuilder.make().getBytes();
                        } else {
                            // Not targeted – copy through
                            outBytes = inBytes;
                        }
                    } else {
                        // Non-class resource – copy through
                        outBytes = inBytes;
                    }
                } catch (Throwable t) {
                    errors++;
                    System.out.println("[WeaverMain][warn] Failed processing entry: " + entry.getName() + " -> " + t);
                    // fall back to original bytes for this entry
                    outBytes = inJar.getInputStream(entry).readAllBytes();
                }

                jos.write(outBytes);
                jos.closeEntry();
            }
        }

        System.out.println("[WeaverMain] Summary: totalClasses=" + totalClasses
                + ", targeted=" + targetedClasses
                + ", errors=" + errors);
        System.out.println("[WeaverMain] Wrote output: " + out.getAbsolutePath()
                + " (exists=" + out.isFile() + ", size=" + out.length() + ")");
    }

    // ---------- helpers ----------

    private static String[] parseTargets(String raw) {
        String[] parts = raw.split("[,;]");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (!s.endsWith(".")) s = s + ".";
            out.add(s);
        }
        return out.toArray(new String[0]);
    }

    private static boolean shouldTarget(String className) {
        for (String p : TARGET_PREFIXES) {
            if (className.startsWith(p)) return true;
        }
        return false;
    }

    private static ClassFileLocator buildLocator(File inJar) throws IOException {
        List<ClassFileLocator> locators = new ArrayList<>();

        // 1) the current input jar
        locators.add(ClassFileLocator.ForJarFile.of(inJar));

        // 2) jars listed in -Dweaver.classpath
        String cp = System.getProperty("weaver.classpath", "");
        if (!cp.isEmpty()) {
            String[] parts = cp.split(File.pathSeparator);
            for (String p : parts) {
                if (p == null || p.isBlank()) continue;
                File f = new File(p);
                if (f.isFile() && p.endsWith(".jar")) {
                    locators.add(ClassFileLocator.ForJarFile.of(f));
                }
            }
        }

        // 3) JDK / system classes
        locators.add(ClassFileLocator.ForClassLoader.ofSystemLoader());

        return new ClassFileLocator.Compound(locators.toArray(new ClassFileLocator[0]));
    }
}
