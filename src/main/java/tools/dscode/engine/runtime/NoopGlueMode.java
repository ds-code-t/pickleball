package tools.dscode.engine.runtime;

import tools.dscode.control.protocol.Workspace;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Whether empty consumer glue fails the step. A system property wins, then
 * {@code pkb_runvars}, then the environment, then the CLI override file, then
 * the workspace runvars for the current run. {@code fail} is the only hard mode.
 */
public final class NoopGlueMode {
    public static final String KEY = "pkb_noop_glue";
    public static final String FILE = ".pickleball/workbench/runvar-overrides.properties";

    private NoopGlueMode() {
    }

    public static boolean fail() {
        String raw = first(
                System.getProperty(KEY),
                System.getProperty("pkb_runvars." + KEY),
                member(System.getProperty("pkb_runvars"), KEY),
                System.getenv("PKB_NOOP_GLUE"),
                overrideFile(),
                workspaceValue()
        );
        return raw != null && "fail".equalsIgnoreCase(raw.trim());
    }

    private static String overrideFile() {
        String project = System.getProperty("pkb_project");
        if (project != null && !project.isBlank()) return readKey(Path.of(project));
        return readKey(Path.of(System.getProperty("user.dir", ".")));
    }

    private static String readKey(Path project) {
        if (project == null) return null;
        Path file = project.resolve(FILE);
        if (!Files.isRegularFile(file)) return null;
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception failure) {
            return null;
        }
        String value = properties.getProperty(KEY);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String workspaceValue() {
        String project = System.getProperty("pkb_project");
        String runId = System.getProperty("pkb_run_id");
        if (project == null || project.isBlank() || runId == null || runId.isBlank()) return null;
        try {
            Path workspace = Workspace.directory(Path.of(project), runId.trim());
            if (!Workspace.exists(workspace)) return null;
            String value = Workspace.readRunVars(workspace).get(KEY);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    static String member(String compact, String key) {
        if (compact == null || compact.isBlank() || key == null) return null;
        for (String part : compact.split("[,;]")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            if (key.equals(item.substring(0, eq).trim())) return item.substring(eq + 1).trim();
        }
        return null;
    }

    private static String first(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
