package tools.dscode.launcher;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Writes {@code --runvar} values where a worker JVM can read them. */
final class RunVarOverrides {
    private RunVarOverrides() {
    }

    /** Replaces the project override file with this command's {@code pkb_} assignments. */
    static void publish(Path project, List<String> runVars) {
        if (project == null || runVars == null || runVars.isEmpty()) return;
        Properties properties = new Properties();
        for (String assignment : runVars) {
            if (assignment == null) continue;
            int eq = assignment.indexOf('=');
            if (eq <= 0) continue;
            String key = assignment.substring(0, eq).trim();
            String value = assignment.substring(eq + 1).trim();
            if (key.startsWith("pkb_")) properties.setProperty(key, value);
        }
        if (properties.isEmpty()) return;
        Path file = project.toAbsolutePath().normalize().resolve(tools.dscode.engine.runtime.NoopGlueMode.FILE);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8), "pickleball --runvar");
            }
        } catch (Exception failure) {
            System.err.println("WARNING: could not write runvar overrides: " + failure.getMessage());
        }
        String noop = properties.getProperty(tools.dscode.engine.runtime.NoopGlueMode.KEY);
        if (noop != null && !noop.isBlank()) System.setProperty(tools.dscode.engine.runtime.NoopGlueMode.KEY, noop);
    }
}
