package tools.dscode.common.variables;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.queries.Tokenized;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;


import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitive;


public class RunVars extends NodeMap {

    public static final Pattern prefixed = Pattern.compile("(?i)^[a-z]{3}_[^\\s\\.]+$");

    private static final String ENV_PREFIX = "env_";
    private static final String SYS_PREFIX = "sys_";
    public static final String PKB_PREFIX = "pkb_";
    public static final String VAR_PREFIX = "var_";

    private static final String RUN_CONFIGS = "runconfigs";
    private static final String PROFILES_DIR = "profiles";
    private static final String PROFILEProp = "profile";

    public final static RunVars RUN_VARS = new RunVars();

    @Override
    public void put(String key, Object value) {
        root.set(key, MAPPER.valueToTree(value));
    }

    public void putAll(Object value) {
        if (value == null) {
            return;
        }
        root.setAll((ObjectNode) MAPPER.valueToTree(value));
    }

    public Object directGet(String key) {
        return root.get(key);
    }

    static {
        System.out.println(GLOBALS);
        RUN_VARS.merge(new HashMap<>(collectPrefixedAndUnprefixedVars()));
        JsonNode runConfigs = buildJsonFromPath(RUN_CONFIGS);
        if (runConfigs instanceof ObjectNode runConfigsNode) {
            RUN_VARS.put(RUN_CONFIGS, runConfigsNode);
            parseRunConfigs();
        }
    }

    private RunVars() {
        super(MapConfigurations.MapType.GLOBAL_NODE);
    }

    public static String getProfileName() {
        Object profile = getFromRunningParsingMapCaseInsensitive(VAR_PREFIX + PROFILEProp);
        if (profile == null)
            profile = RUN_VARS.getByNormalizedPath(PROFILEProp);
        if (profile == null || profile.toString().isBlank())
            return null;
        return profile.toString().trim();
    }

    public static ObjectNode getProfile() {
        String profileName = getProfileName();
        if (profileName == null || profileName.isBlank()) return null;
        Object returnObj = MAPPER.valueToTree(RUN_VARS.getByNormalizedPath(RUN_CONFIGS + "." + PROFILES_DIR + "." + profileName));
        if (returnObj instanceof ObjectNode profileNode)
            return profileNode;
        return null;
    }


    public static Map<String, Object> collectPrefixedAndUnprefixedVars() {
        Map<String, Object> result = new HashMap<>();
        System.getenv().forEach((k, v) ->
                result.put(prefixed.matcher(k).matches() ? k : ENV_PREFIX + k, v));

        Properties props = System.getProperties();
        props.forEach((k, v) -> {
            String key = String.valueOf(k);
            result.put(prefixed.matcher(key).matches() ? key : SYS_PREFIX + key, v);
        });
//
        Map<String, Object> snapshot = new HashMap<>(result);

        snapshot.forEach((k, v) -> {
            if (k.startsWith(ENV_PREFIX)) {
                result.put(k.substring(4), v);
            }
        });

        snapshot.forEach((k, v) -> {
            if (k.startsWith(SYS_PREFIX)) {
                result.put(k.substring(4), v);
            }
        });

        snapshot.forEach((k, v) -> {
            if (k.startsWith(PKB_PREFIX)) {
                result.put(k.substring(4), v);
            }
        });
        return result;
    }

    public static void parseRunConfigs() {
        Object runConfigs = RUN_VARS.directGet(RUN_CONFIGS);

        if (runConfigs instanceof ObjectNode runConfigsNode) {
            if (runConfigsNode.isEmpty())
                return;
            runConfigsNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (key.regionMatches(true, 0, "runconfig", 0, "runconfig".length())) {
                    if (value instanceof ObjectNode objectNode) {
                        RUN_VARS.putAll(objectNode);
                    }
                }
            });
        } else {
            return;
        }

        ObjectNode profileNode = getProfile();
        if (profileNode != null)
            RUN_VARS.putAll(profileNode);
    }

    public static Object resolveFromVars(String varName) {
        varName = varName.toLowerCase().startsWith(VAR_PREFIX) ? varName.substring(VAR_PREFIX.length()) : varName;
        return RUN_VARS.getByNormalizedPath(varName);
    }


}
