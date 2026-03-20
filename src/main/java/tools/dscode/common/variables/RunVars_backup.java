package tools.dscode.common.variables;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.mappings.FileAndDataParsing;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import static io.cucumber.core.runner.GlobalState.getFromRunningParsingMapCaseInsensitive;


public class RunVars extends NodeMap {


    private static final String ENV_PREFIX = "env_";
    private static final String SYS_PREFIX = "sys_";


    private static final String RUN_CONFIGS = "run-configs";
    private static final String PROFILES = RUN_CONFIGS + ".profiles";
    private static final String PROFILEProp = "pkb_profile";

    private static ObjectNode startingGlobalConfig = MAPPER.createObjectNode();

    public final static RunVars RUN_VARS = new RunVars();

    private RunVars() {
        super(MapConfigurations.MapType.GLOBAL_NODE);
        merge(new HashMap<>(getPkbVariables()));
    }

    static {
        JsonNode runConfigNode = FileAndDataParsing.buildJsonFromPath(RUN_CONFIGS);
        for (Map.Entry<String, JsonNode> entry : runConfigNode.properties()) {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if(value.isMissingNode() || value.isNull())
                continue;
//            evaluate(value, RUN_VARS.root);
        }
        String profileName = RUN_VARS.getStringValue(PROFILEProp);

        if (profileName != null && !profileName.isBlank()) {
            JsonNode profilesNode = FileAndDataParsing.buildJsonFromPath(PROFILES);
            JsonNode profileNode = getFieldIgnoreCaseSpaceNormalized((ObjectNode) profilesNode, profileName.trim());
//            evaluate(profileNode, RUN_VARS.root);
        }

    }

    public static final String PickleballVarPrefix = "pkb_";
    public static final String RunVarPrefix = "run_";

    public static Object resolveVarOrDefault(String varName, Object defaultValue) {
        System.out.println("@@resolveVarOrDefault: " + varName + ", default: " + defaultValue + "");
        Object obj = resolveVar(varName);
        System.out.println("@@obj: " + obj + "");
        if (obj == null) return defaultValue;
        return obj;
    }

    public static Object resolveVar(String varName) {
        varName = varName.trim();
        if (varName.startsWith(RunVarPrefix))
            return resolveVarWithParsingMap(varName);
        if (varName.startsWith(PickleballVarPrefix))
            return resolveSetupVars(varName);
        Object returnObj = resolveVarWithParsingMap(RunVarPrefix + varName);
        if (returnObj == null || (returnObj instanceof String returnString && returnString.isBlank()))
            returnObj = resolveSetupVars(PickleballVarPrefix + varName);
        return returnObj;
    }

    public static Object resolveVarWithParsingMap(String varName) {
        return getFromRunningParsingMapCaseInsensitive(varName);
    }

    public static Object resolveSetupVars(String varName) {
        return RUN_VARS.getByNormalizedPath(varName);
    }

    public static Map<String, Object> getPkbVariables() {

        Map<String, Object> result = new HashMap<>();

        // Env (lower priority)
        System.getenv().forEach((k, v) -> {
            String key = k.toLowerCase(Locale.ROOT);
            if (key.startsWith(PickleballVarPrefix)) {
                result.put(key, v);
            }
        });

        // System properties (override env)
        System.getProperties().forEach((k, v) -> {
            String key = k.toString().toLowerCase(Locale.ROOT);
            if (key.startsWith(PickleballVarPrefix)) {
                result.put(key, v);
            }
        });
        return Map.copyOf(result);
    }

    /**
     * Adds all environment variables and JVM system properties to the given object node.
     * Values are stored as JSON strings; nulls become JSON nulls.
     */
    public static void putEnvAndSysInto(ObjectNode target) {
        Objects.requireNonNull(target, "target");

        // Environment variables
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String key = ENV_PREFIX + e.getKey();
            String val = e.getValue();
            if (val == null) target.set(key, NullNode.getInstance());
            else target.put(key, val);
        }

        // System properties
        Properties props = System.getProperties();
        for (String name : props.stringPropertyNames()) {
            String key = SYS_PREFIX + name;
            String val = props.getProperty(name); // may be null
            if (val == null) target.set(key, NullNode.getInstance());
            else target.put(key, val);
        }
    }

    public static void applySysFromObjectNode(ObjectNode source) {
        applySysFromObjectNode(source, true);
    }

    public static void applySysFromObjectNode(ObjectNode source, boolean alwaysSet) {
        Objects.requireNonNull(source, "source");

        Iterator<Map.Entry<String, JsonNode>> it = source.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> field = it.next();

            String jsonKey = field.getKey();
            if (!jsonKey.startsWith(SYS_PREFIX) || jsonKey.length() == SYS_PREFIX.length()) {
                continue;
            }

            String sysKey = jsonKey.substring(SYS_PREFIX.length());
            JsonNode valueNode = field.getValue();

            String current = System.getProperty(sysKey);
            boolean currentUnsetOrBlank = (current == null) || current.isBlank();

            if (!alwaysSet && !currentUnsetOrBlank) {
                continue; // leave existing value alone
            }
            if (isNullOrBlank(valueNode)) {
                System.clearProperty(sysKey);
            } else {
                System.setProperty(sysKey, valueNode.asText());
            }
        }
    }

    private static boolean isNullOrBlank(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return true;
        // treat blank strings as "blank"; other types become text via asText()
        return n.isTextual() && n.asText().isBlank();
    }


}
