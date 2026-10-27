package tools.dscode.testengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.chrome.ChromeOptions;
import tools.dscode.common.driver.DriverConstruction;
import tools.dscode.common.mappings.GlobalMappings;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.testengine.fixtures.ProbeRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Forked launch probe. Constructs {@link PickleballRunner} so {@code pkb_workspace}
 * loads, then builds the {@link ChromeOptions} {@code newChromiumDriver} builds
 * from the workspace config. Does not start a browser. {@code localDriver} is false
 * so the live-scenario debug-port branch is not entered; arguments and
 * {@code goog:loggingPrefs} are applied before that branch.
 */
public final class WorkspaceDriverProbe {
    private WorkspaceDriverProbe() {
    }

    public static void main(String[] args) {
        int code = 1;
        try {
            ProbeRunner runner = new ProbeRunner();
            String browserKey = runner.get(PKB_props.PKB_BROWSER);
            JsonNode configsNode = GlobalMappings.GLOBALS.getRoot().get(ParsingMap.CONFIGS_MAP_ROOT);
            ObjectNode configs = configsNode instanceof ObjectNode object ? object : null;
            ObjectNode config = find(configs, browserKey);
            List<String> keys = fieldNames(configs);
            System.out.println("PKB_PROBE keys=" + String.join(",", keys));
            if (config == null) {
                System.out.println("PKB_PROBE missing-config=" + browserKey);
                code = 2;
            } else {
                ChromeOptions options = DriverConstruction.buildChromeOptions(
                        DriverConstruction.getDriverSection(config),
                        config,
                        false
                );
                System.out.println("PKB_PROBE browser=" + browserKey);
                System.out.println("PKB_PROBE resolvedBrowser=" + DriverConstruction.resolveBrowserName(config));
                System.out.println("PKB_PROBE chromeOptions=" + options.getCapability("goog:chromeOptions"));
                System.out.println("PKB_PROBE logging=" + options.getCapability("goog:loggingPrefs"));
                System.out.println("PKB_PROBE hook=" + runner.get("pkb_hook_marker"));
                System.out.println("PKB_PROBE secret=" + runner.get(PKB_props.PKB_RP_API_KEY));
                System.out.println("PKB_PROBE parallel=" + runner.get(PKB_props.PKB_PARALLEL));
                System.out.println("PKB_PROBE tags=" + runner.get(PKB_props.PKB_TAGS));
                System.out.println("PKB_PROBE sysBrowser=" + System.getProperty(PKB_props.PKB_BROWSER));
                code = 0;
            }
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            code = 1;
        }
        System.exit(code);
    }

    private static ObjectNode find(ObjectNode configs, String browserKey) {
        if (configs == null || browserKey == null) return null;
        JsonNode exact = configs.get(browserKey);
        if (exact instanceof ObjectNode object) return object;
        var names = configs.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.equalsIgnoreCase(browserKey) && configs.get(name) instanceof ObjectNode object) {
                return object;
            }
        }
        return null;
    }

    private static List<String> fieldNames(ObjectNode configs) {
        List<String> names = new ArrayList<>();
        if (configs == null) return names;
        configs.fieldNames().forEachRemaining(names::add);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
