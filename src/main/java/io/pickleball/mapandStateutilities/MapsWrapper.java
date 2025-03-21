package io.pickleball.mapandStateutilities;

import java.util.*;

public class MapsWrapper extends HashMap<String, Object> {
    final public List<Map<String, ?>> mapList;

    @SafeVarargs
    public MapsWrapper(Map<String, ?>... maps) {
        this(Arrays.stream(maps).toList());
    }

    public MapsWrapper(List<? extends Map<String, ?>> maps) {
        mapList = new ArrayList<>(maps);
        mapList.removeIf(Objects::isNull);
    }


    @SafeVarargs
    public final void addMaps(Map<String, ?>... maps) {
        for (Map<String, ?> map : maps) {
            if (map != null) {  // Optional: null check
                mapList.add(map);
            }
        }
    }


    @Override
    public String get(Object key) {
//        System.out.println("@@MapsWrapper-get: " + key);
        if (key == null)
            return null;
        String stringKey = key.toString().trim();
        if (stringKey.isEmpty())
            return null;
        Object value;
        for (Map<?, ?> map : mapList) {
            value = map.get(stringKey);
//            System.out.println("@@map== "+ map);
//            System.out.println("@@value== "+ value);
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


    @Override
    public String toString() {
        // Create a new map to hold the combined results
        Map<String, Object> combinedMap = new HashMap<>();

        // Iterate through all maps in the list
        for (Map<String, ?> map : mapList) {
            // Add all entries from current map to combined map
            combinedMap.putAll(map);
        }

        // Return the string representation of the combined map
        return combinedMap.toString();
    }

}