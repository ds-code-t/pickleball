package tools.dscode.common.variables;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.regex.Pattern;

import static io.cucumber.core.runner.GlobalState.getFromRunningParsingMapCaseInsensitive;
import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.custommappings.TildeReader.tildeReader;


public class RunVars extends NodeMap {

    private static final Pattern prefixed = Pattern.compile("(?i)^[a-z]{3}_.*");

    private static final String ENV_PREFIX = "env_";
    private static final String SYS_PREFIX = "sys_";
    public static final String PKB_PREFIX = "pkb_";
    public static final String RUN_PREFIX = "run_";

    private static final String RUN_CONFIGS = "run-configs";
    private static final String PROFILES_DIR = "profiles";
    private static final String PROFILEProp = "profile";

    public final static RunVars RUN_VARS = new RunVars();

    private RunVars() {
        super(MapConfigurations.MapType.GLOBAL_NODE);
        merge(new HashMap<>(collectPrefixedAndUnprefixedVars()));
    }

    public static String getProfile() {
        Object profile = getFromRunningParsingMapCaseInsensitive(RUN_PREFIX + PROFILEProp);
        if (profile == null)
            profile = RUN_VARS.getByNormalizedPath(PROFILEProp);
        if (profile == null || profile.toString().isBlank())
            return null;
        return profile.toString().trim();
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


//        JsonNode jsonNode =  buildJsonFromPath(profilePath);

        JsonNode runConfigs = buildJsonFromPath(RUN_CONFIGS);
        if (runConfigs == null || runConfigs.isNull() || runConfigs.isMissingNode() || runConfigs.isEmpty())
            return result;
        if (runConfigs instanceof ObjectNode runConfigsNode) {
            if (runConfigsNode.isEmpty())
                return result;

            runConfigsNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (key.regionMatches(true, 0, "runconfig", 0, "runconfig".length())) {
                    System.out.println("@@value: " + value);
                    System.out.println("@@value.getClass(): " + value.getClass());
                    if (value instanceof ObjectNode objectNode) {
                        HashMap<String, Object> map = tildeReader.convertValue(objectNode, new TypeReference<>() {
                        });
                        result.putAll(map);
                    } else {
                        if (!(value.isNull() || value.isMissingNode() || value.isEmpty()))
                            throw new RuntimeException("RunConfig '" + key + "' is not a valid ObjectNode");
                    }
                }
            });
            String profileName = getProfile();
            if (profileName == null)
                return result;
            JsonNode jsonNode = runConfigs.get(PROFILES_DIR);

            if (jsonNode instanceof ObjectNode profilesNode) {
                JsonNode profile = profilesNode.get(profileName);
                if (profile == null || profile.isNull() || profile.isMissingNode() || profile.isEmpty())
                    return result;
                if (profile instanceof ObjectNode profileNode) {
                    HashMap<String, Object> map = tildeReader.convertValue(profileNode, new TypeReference<>() {
                    });
                    result.putAll(map);
                } else {
                    throw new RuntimeException(profileName + " is not a valid ObjectNode");
                }
            } else {
                throw new RuntimeException("PROFILES_DIR is not a valid ObjectNode");
            }
        } else {
            throw new RuntimeException(RUN_CONFIGS + "  is not a valid ObjectNode");
        }


        return result;
    }


    public static Object resolveVarOrDefault(String varName, Object defaultValue) {
        System.out.println("@@resolveVarOrDefault: " + varName + ", default: " + defaultValue + "");
        Object obj = resolveVar(varName);
        System.out.println("@@obj: " + obj + "");
        if (obj == null) return defaultValue;
        return obj;
    }

    public static Object resolveVar(String varName) {
        System.out.println("@@resolveVar: " + varName);
        String prefixedVarName = prefixed.matcher(varName).matches() ? varName : RUN_PREFIX + varName;
        Object returnObj = getFromRunningParsingMapCaseInsensitive(prefixedVarName);
        ;
        if (returnObj == null)
            return RUN_VARS.getByNormalizedPath(varName);
        return returnObj;
    }


}
