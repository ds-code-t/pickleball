package tools.dscode.common.variables;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.queries.Tokenized;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;


import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.mappings.MappingProcessor.getSingletonMap;
import static tools.dscode.common.mappings.ParsingMap.getFromRunningParsingMapCaseInsensitive;


public class RunVars extends NodeMap {
    private static final String ENV_PREFIX = "env_";
    private static final String SYS_PREFIX = "sys_";


    public static final String PKB_PREFIX = "pkb_";


    private static final String RUN_CONFIGS = "runconfigs";
    private static final String PROFILES_DIR = "profiles";
    private static final String PROFILEProp = PKB_PREFIX + "profile";

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


    static {
        RUN_VARS.merge(collectPickleBallVars());
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
        Object profile = getFromRunningParsingMapCaseInsensitive(PROFILEProp);
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

    public static Map<String, Object> collectPickleBallVars() {
        Map<String, Object> result = new HashMap<>();

        System.getenv().forEach((key, value) -> {
            if (key != null
                    && key.length() >= PKB_PREFIX.length()
                    && key.regionMatches(true, 0, PKB_PREFIX, 0, PKB_PREFIX.length())) {
                result.put(key.toLowerCase(Locale.ROOT), value);
            }
        });

        System.getProperties().forEach((key, value) -> {
            String k = String.valueOf(key);
            if (k.length() >= PKB_PREFIX.length()
                    && k.regionMatches(true, 0, PKB_PREFIX, 0, PKB_PREFIX.length())) {
                result.put(k.toLowerCase(Locale.ROOT), value); // overwrites env entry if same normalized key
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

    public static Object resolveFromVarsOrDefault(String varName, Object defaultValue) {
        Object returnObj = resolveFromVars(varName);
        return returnObj == null ? defaultValue : returnObj;
    }

    public static Object resolveFromVars(String varName) {
        if (varName == null || varName.isBlank()) {
            return null;
        }
        String key = hasRecognizedPrefix(varName) ? varName : PKB_PREFIX + varName;
        Object returnObj = getSingletonMap().getByNormalizedPath(key);
        if (returnObj == null) return resolveProperty(key);
        if (returnObj instanceof List list) {
            if (list.isEmpty()) return resolveProperty(key);
            return list.getLast();
        }
        return returnObj;
    }

    public static boolean hasRecognizedPrefix(String s) {
        return hasPrefixIgnoreCase(s, PKB_PREFIX)
                || hasPrefixIgnoreCase(s, ENV_PREFIX)
                || hasPrefixIgnoreCase(s, SYS_PREFIX);
    }

    public static boolean hasPkbPrefix(String s) {
        return s != null
                && s.length() >= PKB_PREFIX.length()
                && s.regionMatches(true, 0, PKB_PREFIX, 0, PKB_PREFIX.length());
    }


    public static String ensurePkbPrefix(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        return hasPkbPrefix(s) ? s : PKB_PREFIX + s;
    }


    private static Object resolveProperty(String propertyName) {
        if (propertyName == null || propertyName.isBlank()) {
            return null;
        }

        if (hasPkbPrefix(propertyName)) {
            return RUN_VARS.root.get(propertyName);
        }

        String key = stripPrefixIfPresent(propertyName, SYS_PREFIX);
        if (key != null) {
            return System.getProperty(key);
        }

        key = stripPrefixIfPresent(propertyName, ENV_PREFIX);
        if (key != null) {
            return System.getenv(key);
        }

        Object value = RUN_VARS.root.get(PKB_PREFIX + propertyName);
        if (value != null) {
            return value;
        }

        value = System.getProperty(propertyName);
        if (value != null) {
            return value;
        }

        return System.getenv(propertyName);
    }

    private static boolean hasPrefixIgnoreCase(String s, String prefix) {
        return s != null
                && prefix != null
                && s.length() >= prefix.length()
                && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String stripPrefixIfPresent(String s, String prefix) {
        return hasPrefixIgnoreCase(s, prefix)
                ? s.substring(prefix.length())
                : null;
    }


}
