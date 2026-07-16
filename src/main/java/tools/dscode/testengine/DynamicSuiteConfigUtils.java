package tools.dscode.testengine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.cucumber.core.options.Constants.FEATURES_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.GLUE_PROPERTY_NAME;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_GLUE;
import static tools.dscode.testengine.PKB_props.PKB_PREFIX;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

/**
 * Resolves suite configuration by preferring {@link PKB_props} aliases and falling back to
 * Cucumber {@link io.cucumber.core.options.Constants} property names.
 */
public final class DynamicSuiteConfigUtils {



    private DynamicSuiteConfigUtils() {
    }

    public static List<String> getGluePaths() {
        return splitCommaSeparated(getFirstNonBlank(PKB_GLUE, GLUE_PROPERTY_NAME));
    }

    public static List<String> getFeaturePaths() {
        return splitCommaSeparated(getFirstNonBlank(PKB_FEATURES, FEATURES_PROPERTY_NAME));
    }

    public static String getTags() {
        return getFirstNonBlank(PKB_TAGS, FILTER_TAGS_PROPERTY_NAME);
    }

    public static HashMap<String, Object> getPkbValues() {
        HashMap<String, Object> out = new HashMap<>();
        for (Map.Entry<String, String> entry : PickleballRunner.getInstance().values().entrySet()) {
            String key = entry.getKey();
            if (key != null && key.startsWith(PKB_PREFIX)) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }

    private static String getFirstNonBlank(String... keys) {
        Map<String, String> values = PickleballRunner.getInstance().values();
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static List<String> splitCommaSeparated(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (String part : Arrays.asList(value.split(","))) {
            String trimmed = part == null ? null : part.trim();
            if (trimmed != null && !trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return List.copyOf(out);
    }
}