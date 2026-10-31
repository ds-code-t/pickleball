package tools.dscode.testengine.fixtures;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Hook matrix for a real launch and for {@code resolve-runvars}.
 * {@code pickleball.parity.mode} is not a RunVar. It only chooses which hook writes.
 */
public final class ParityHookRunner extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        mark("defaults");
        values.put(PKB_props.PKB_GLUE, "tools.dscode.testengine.parity");
        values.put(PKB_props.PKB_FEATURES, "classpath:launch-parity");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_PARALLEL, "1");
        values.put(PKB_props.PKB_LOGLEVEL, "error");
        values.put(PKB_props.PKB_PLUGINS, "pretty");
        values.put(PKB_props.PKB_TAGS, "@launch-parity");
        if ("defaults".equals(mode()) || "both".equals(mode()) || "file".equals(mode())) {
            values.put(PKB_props.PKB_BROWSER, "from-defaults");
            values.put("pkb_hook_marker", "from-defaults");
        }
    }

    @Override
    public void globalTestProperties() {
        mark("properties");
        if ("properties".equals(mode()) || "both".equals(mode()) || "file-and-properties".equals(mode())) {
            values.put(PKB_props.PKB_BROWSER, "from-properties");
            values.put("pkb_hook_prop", "from-properties");
        }
    }

    private static String mode() {
        String mode = System.getProperty("pickleball.parity.mode", "none");
        return mode == null || mode.isBlank() ? "none" : mode.trim();
    }

    private static void mark(String which) {
        String file = System.getProperty("pickleball.parity.hookfile");
        if (file == null || file.isBlank()) return;
        try {
            Path path = Path.of(file);
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            String prior = Files.isRegularFile(path) ? Files.readString(path) : "";
            Files.writeString(path, prior + which + "\n", StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not record hook " + which, failure);
        }
    }
}
