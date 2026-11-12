package tools.dscode.common.composite;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Launcher for the in-repo Java engine. Explicit {@code pkb_composite_bin} wins
 * before this. {@code pkb_composite_engine=rust} skips it.
 */
final class JavaEngineLocator {
    private JavaEngineLocator() {
    }

    static Optional<Path> script(ClassLoader loader) {
        ClassLoader source = loader == null ? ClassLoader.getSystemClassLoader() : loader;
        try {
            Class<?> main = Class.forName("dev.domengine.cli.DomEngineMain", false, source);
            List<String> classpath = classpath(source, main);
            if (classpath.isEmpty()) return Optional.empty();
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pickleball-domengine", "3.0.0");
            Files.createDirectories(dir);
            Path script = dir.resolve("domengine");
            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String body = "#!/bin/sh\nexec "
                    + quote(javaBin)
                    + " -cp "
                    + quote(String.join(java.io.File.pathSeparator, classpath))
                    + " dev.domengine.cli.DomEngineMain \"$@\"\n";
            Files.writeString(script, body);
            if (!script.toFile().setExecutable(true)) {
                CompositeLog.debug("location", "could not mark executable " + script);
            }
            return Optional.of(script.toAbsolutePath().normalize());
        } catch (ClassNotFoundException missing) {
            return Optional.empty();
        } catch (IOException failure) {
            CompositeLog.debug("location", "java launcher: " + failure.getMessage());
            return Optional.empty();
        }
    }

    private static List<String> classpath(ClassLoader loader, Class<?> main) {
        Set<String> entries = new LinkedHashSet<>();
        add(entries, main);
        for (String name : List.of(
                "com.fasterxml.jackson.databind.ObjectMapper",
                "com.fasterxml.jackson.core.JsonFactory",
                "com.fasterxml.jackson.annotation.JsonProperty"
        )) {
            try {
                add(entries, Class.forName(name, false, loader));
            } catch (ClassNotFoundException ignored) {
                // Databind may already live beside the engine classes.
            }
        }
        return new ArrayList<>(entries);
    }

    private static void add(Set<String> entries, Class<?> type) {
        if (type == null || type.getProtectionDomain() == null || type.getProtectionDomain().getCodeSource() == null) return;
        if (type.getProtectionDomain().getCodeSource().getLocation() == null) return;
        try {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // A non-file location cannot be put on a child classpath.
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
