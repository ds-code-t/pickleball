package tools.dscode.testengine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static tools.dscode.testengine.PickleballRunner.CUCUMBER_FEATURES;
import static tools.dscode.testengine.PickleballRunner.CUCUMBER_GLUE;
import static tools.dscode.testengine.PickleballRunner.CUCUMBER_TAGS;
import static tools.dscode.testengine.PickleballRunner.PKB_FEATURES;
import static tools.dscode.testengine.PickleballRunner.PKB_GLUE;
import static tools.dscode.testengine.PickleballRunner.PKB_PREFIX;
import static tools.dscode.testengine.PickleballRunner.PKB_TAGS;

public final class DynamicSuiteConfigUtils {



    private DynamicSuiteConfigUtils() {
    }

    public static List<String> getGluePaths() {
        return splitCommaSeparated(getFirstNonBlank(PKB_GLUE, CUCUMBER_GLUE));
    }

    public static List<String> getFeaturePaths() {
        return splitCommaSeparated(getFirstNonBlank(PKB_FEATURES, CUCUMBER_FEATURES));
    }

    public static String getTags() {
        return getFirstNonBlank(PKB_TAGS, CUCUMBER_TAGS);
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