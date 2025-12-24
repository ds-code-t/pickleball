package tools.dscode.common.treeparsing;

import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class NamedGroupMap extends java.util.HashMap<String, String> {

    private static final String START_INDEX_PREFIX = "_indexOf_";

    public NamedGroupMap(Pattern pattern, MatchResult result) {
        super();

        // 1. Store numbered groups: "0", "1", "2", ...
        int count = result.groupCount();
        for (int i = 0; i <= count; i++) {
            String value = result.group(i);
            if (value != null) {
                this.put(String.valueOf(i), value);
            }
        }

        // 2. Store named groups: "name" => value
        try {
            Map<String, Integer> named = pattern.namedGroups();
            for (var entry : named.entrySet()) {
                String name = entry.getKey();
                int groupIndex = entry.getValue();
                String value = result.group(groupIndex);
                if (value != null) {
                    this.put(name, value);
                    this.put(START_INDEX_PREFIX + name, String.valueOf(result.start(groupIndex)));
                }
            }
        } catch (UnsupportedOperationException | NoSuchMethodError e) {
            // Named groups not supported — ignore silently
        }
    }

    /** Convenience method: get value by group number */
    public String get(int groupNumber) {
        return this.get(String.valueOf(groupNumber));
    }

    @Override
    public String get(Object key) {
        String value = super.get(key);
        return (value == null || value.isBlank()) ? null : value;
    }

    public Integer start(String key) {
        String value = super.get(START_INDEX_PREFIX +  key);
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }


}
