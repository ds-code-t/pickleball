package tools.dscode.common.variables;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;


import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitive;


public class RunVars extends NodeMap {

    public static final Pattern prefixed = Pattern.compile("(?i)^[a-z]{3}_\\S+$");

    private static final String ENV_PREFIX = "env_";
    private static final String SYS_PREFIX = "sys_";
    public static final String PKB_PREFIX = "pkb_";
    public static final String RUN_PREFIX = "run_";

    private static final String RUN_CONFIGS = "run-configs";
    private static final String PROFILES_DIR = "profiles";
    private static final String PROFILEProp = "profile";

    public final static RunVars RUN_VARS = new RunVars();

    static {
        System.out.println(GLOBALS);
        JsonNode runConfigs = buildJsonFromPath(RUN_CONFIGS);
        if (runConfigs instanceof ObjectNode runConfigsNode)
            RUN_VARS.merge(runConfigsNode);
        RUN_VARS.merge(new HashMap<>(collectPrefixedAndUnprefixedVars()));
    }

    private RunVars() {
        super(MapConfigurations.MapType.GLOBAL_NODE);
    }

    public static String getProfileName() {
        Object profile = getFromRunningParsingMapCaseInsensitive(RUN_PREFIX + PROFILEProp);
        if (profile == null)
            profile = RUN_VARS.getByNormalizedPath(PROFILEProp);
        if (profile == null || profile.toString().isBlank())
            return null;
        return profile.toString().trim();
    }

    public static ObjectNode getProfile() {
        String profileName = getProfileName();
        if (profileName == null || profileName.isBlank()) return null;
        Object profile = RUN_VARS.getByNormalizedPath(PROFILES_DIR + "." + profileName);
        if (profile == null)
            throw new RuntimeException("Profile '" + profileName + "' not found");
        if (profile instanceof ObjectNode profileNode)
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
        ObjectNode profileNode = getProfile();
        JsonNode runConfigs = null;
        try {
            runConfigs = buildJsonFromPath(RUN_CONFIGS);
        } catch (Exception e) {
            return result;
        }


        if (runConfigs instanceof ObjectNode runConfigsNode) {
            runConfigsNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (key.regionMatches(true, 0, "runconfig", 0, "runconfig".length())) {
                    if (value instanceof ObjectNode objectNode) {
                        HashMap<String, Object> map = MAPPER.convertValue(objectNode, new TypeReference<>() {
                        });
                        result.putAll(map);
                    }
                }
            });
        }
        if (profileNode != null)
            result.putAll(MAPPER.convertValue(profileNode, new TypeReference<>() {
            }));
        return result;
    }


//    public static Object resolveVarOrDefault(String varName, Object defaultValue) {
//        Object obj = resolveVar(varName);
//        if (obj == null) return defaultValue;
//        return obj;
//    }
//
//    public static Object resolveVar(String varName) {
//        String prefixedVarName = prefixed.matcher(varName).matches() ? varName : RUN_PREFIX + varName;
//        Object returnObj = getFromRunningParsingMapCaseInsensitive(prefixedVarName);
//        if (returnObj == null)
//            return RUN_VARS.getByNormalizedPath(varName);
//        return returnObj;
//    }

    public static Object resolveFromVars(String varName) {
        varName = varName.toLowerCase().startsWith(RUN_PREFIX) ? varName.substring(RUN_PREFIX.length()) : varName;
        return RUN_VARS.getByNormalizedPath(varName);
    }


}
