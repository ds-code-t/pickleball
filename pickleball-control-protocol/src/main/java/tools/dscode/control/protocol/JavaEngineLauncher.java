package tools.dscode.control.protocol;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Launcher for the in-repo Java DOM engine ({@code dev.domengine.cli.DomEngineMain}).
 *
 * <p>JDK-only and reflective: this module never compiles against
 * {@code :dom-engine}. When the engine classes are on the given class loader
 * (the Pickleball jar, or the thin Workbench jar plus its Jackson runtime libs),
 * {@link #script} writes a small launcher that runs
 * {@code java -cp <engine + jackson> DomEngineMain "$@"} and returns its path.
 * Callers treat that path exactly like the old {@code composite} binary.</p>
 *
 * <p>Shared by {@link DirectControlProvider} (Workbench direct-control handoff)
 * and the root {@code CompositeBinaryLocator}. An explicit
 * {@code pkb_composite_bin} or {@code COMPOSITE_BIN} wins before this;
 * {@code pkb_composite_engine=rust} / {@code PKB_COMPOSITE_ENGINE=rust} skips it.</p>
 */
public final class JavaEngineLauncher {
    public static final String MAIN_CLASS = "dev.domengine.cli.DomEngineMain";
    public static final String ENGINE_KEY = "pkb_composite_engine";
    public static final String ENGINE_ENV = "PKB_COMPOSITE_ENGINE";
    public static final String BIN_KEY = "pkb_composite_bin";
    private static final List<String> JACKSON = List.of(
            "com.fasterxml.jackson.databind.ObjectMapper",
            "com.fasterxml.jackson.core.JsonFactory",
            "com.fasterxml.jackson.annotation.JsonProperty"
    );

    private JavaEngineLauncher() {
    }

    /** True when the engine main class is visible to {@code loader}. */
    public static boolean available(ClassLoader loader) {
        try {
            Class.forName(MAIN_CLASS, false, loader(loader));
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    /**
     * True when {@code pkb_composite_engine} (system property, then
     * {@code pkb_runvars.} property) or {@code PKB_COMPOSITE_ENGINE} /
     * {@code pkb_composite_engine} in {@code env} is {@code rust}.
     */
    public static boolean rustRequested(Map<String, String> env) {
        String engine = first(
                text(System.getProperty(ENGINE_KEY)),
                text(System.getProperty("pkb_runvars." + ENGINE_KEY)),
                env == null ? null : text(env.get(ENGINE_ENV)),
                env == null ? null : text(env.get(ENGINE_KEY))
        );
        return engine != null && "rust".equalsIgnoreCase(engine);
    }

    /**
     * Writes (or reuses) the launcher for the engine visible to {@code loader}.
     * Empty when the engine is not on that class loader or its classes have no
     * file location a child JVM can use.
     */
    public static Optional<Path> script(ClassLoader loader) {
        ClassLoader source = loader(loader);
        Class<?> main;
        try {
            main = Class.forName(MAIN_CLASS, false, source);
        } catch (ClassNotFoundException | LinkageError missing) {
            return Optional.empty();
        }
        List<String> classpath = classpath(source, main);
        if (classpath.isEmpty()) return Optional.empty();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String body = windows ? windowsBody(classpath) : shellBody(classpath);
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pickleball-domengine", "3.0.0");
            Files.createDirectories(dir);
            // One file per distinct classpath so concurrent JVMs with different
            // classpaths never overwrite each other's launcher.
            Path script = dir.resolve("domengine-" + digest(body) + (windows ? ".cmd" : ""));
            if (!Files.isRegularFile(script) || !body.equals(Files.readString(script, StandardCharsets.UTF_8))) {
                Path tmp = Files.createTempFile(dir, "domengine-", ".part");
                try {
                    Files.writeString(tmp, body, StandardCharsets.UTF_8);
                    try {
                        Files.move(tmp, script, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(tmp, script, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            if (!Files.isExecutable(script) && !script.toFile().setExecutable(true)) {
                return Optional.empty();
            }
            return Optional.of(script.toAbsolutePath().normalize());
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    /** Classpath entries for the engine main class and the Jackson jars it needs. */
    static List<String> classpath(ClassLoader loader, Class<?> main) {
        Set<String> entries = new LinkedHashSet<>();
        add(entries, main);
        for (String name : JACKSON) {
            try {
                add(entries, Class.forName(name, false, loader));
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Databind may already live beside the engine classes.
            }
        }
        return new ArrayList<>(entries);
    }

    private static String shellBody(List<String> classpath) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return "#!/bin/sh\nexec "
                + quote(javaBin)
                + " -cp "
                + quote(String.join(File.pathSeparator, classpath))
                + " " + MAIN_CLASS + " \"$@\"\n";
    }

    private static String windowsBody(List<String> classpath) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        return "@echo off\r\n\""
                + javaBin
                + "\" -cp \""
                + String.join(File.pathSeparator, classpath)
                + "\" " + MAIN_CLASS + " %*\r\n";
    }

    private static void add(Set<String> entries, Class<?> type) {
        if (type == null || type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null) return;
        if (type.getProtectionDomain().getCodeSource().getLocation() == null) return;
        try {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException ignored) {
            // A non-file location cannot be put on a child classpath.
        }
    }

    private static ClassLoader loader(ClassLoader loader) {
        if (loader != null) return loader;
        ClassLoader own = JavaEngineLauncher.class.getClassLoader();
        return own != null ? own : ClassLoader.getSystemClassLoader();
    }

    private static String digest(String body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(body.hashCode());
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String text(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
