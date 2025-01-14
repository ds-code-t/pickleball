package io.pickleball.mapandStateutilities;

import java.util.*;

public class MapsWrapper extends HashMap<String, Object> {
    final public List<Map<String, String>> mapList;

    @SafeVarargs
    public MapsWrapper(Map<String, String>... maps) {
        this(Arrays.stream(maps).toList());
    }

    public MapsWrapper(List<Map<String, String>> maps) {
        mapList = new ArrayList<>(maps);
        mapList.removeIf(Objects::isNull);
    }

    @Override
    public String get(Object key) {
        if (key == null)
            return null;
        String stringKey = key.toString().trim();
        if (stringKey.isEmpty())
            return null;
        Object value;
        for (Map<?, ?> map : mapList) {
            value = map.get(stringKey);
            if (value != null) {
                String stringValue = value.toString();
                if (stringValue.isBlank()) {
                    if (!stringKey.startsWith("?"))
                        stringKey = "?" + stringKey;
                } else
                    return stringValue;
            }
        }
        if (stringKey.startsWith("?"))
            return "";
        return null;
    }



    @Override
    public Object getOrDefault(Object key, Object defaultValue) {
        String returnString = get(key);
        if (returnString == null)
            return defaultValue;
        return returnString;
    }
}
