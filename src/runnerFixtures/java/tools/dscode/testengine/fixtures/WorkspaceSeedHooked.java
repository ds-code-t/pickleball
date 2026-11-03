package tools.dscode.testengine.fixtures;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

import java.util.Locale;

/** Consumer-style hooks used by workspace seed tests. Not a scanned test-output runner. */
public final class WorkspaceSeedHooked extends PickleballRunner {
    @Override
    public void globalTestDefaults() {
        values.put(PKB_props.PKB_GLUE, "com.example.steps");
        values.put(PKB_props.PKB_FEATURES, "classpath:features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_PARALLEL, "auto");
        values.put(PKB_props.PKB_BROWSER, "firefox");
    }

    @Override
    public void globalTestProperties() {
        values.put(PKB_props.PKB_ENVIRONMENT, "qa");
        values.put("pkb_hook_marker", "from-hook");
        values.put("pkb_description", hookDescription());
    }

    /** User, machine, locale, and Java vendor. The value changes every seed, so it is a hook. */
    public static String hookDescription() {
        return String.join(" ",
                System.getProperty("user.name", "unknown"),
                machineName(),
                Locale.getDefault().toString(),
                System.getProperty("java.vendor", "unknown"));
    }

    private static String machineName() {
        String env = System.getenv("HOSTNAME");
        if (env == null || env.isBlank()) env = System.getenv("COMPUTERNAME");
        if (env != null && !env.isBlank()) return env.trim();
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isBlank()) return host.trim();
        } catch (Exception ignored) {
            // A missing host name is still a hook value.
        }
        return "unknown";
    }
}