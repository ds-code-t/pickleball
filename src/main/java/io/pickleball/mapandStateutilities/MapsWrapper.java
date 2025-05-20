package io.pickleball.mapandStateutilities;

import io.pickleball.datafunctions.TypeConverter;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.pickleball.mapandStateutilities.LinkedMultiMap.configFlag;

public class MapsWrapper extends HashMap<String, Object> implements TypeConverter {
    public static final String mapPriority = configFlag + "priority";
    final public List<Map<?, ?>> mapList;
    private final LinkedMultiMap firstMap = new LinkedMultiMap();
    private final LinkedMultiMap lastMap = new LinkedMultiMap();
    

//    private LinkedMultiMap<String, LinkedMultiMap> keyedMaps = new LinkedMultiMap();
//    private Map<String, LinkedMultiMap> keyedMaps = new HashMap<>();
//    private Map<String, Integer> keyCount = new HashMap<>();


    private static final AtomicInteger instanceCounter = new AtomicInteger(0);

    @SafeVarargs
    public MapsWrapper(Map<?, ?>... maps) {
        mapList = Arrays.stream(maps).filter(Objects::nonNull)
                .collect(Collectors.toList());
        mapList.add(0, firstMap);
        mapList.add(lastMap);
        instanceCounter.incrementAndGet();
        sortMaps();
    }

    public MapsWrapper(List<? extends Map<?, ?>> maps) {
        mapList = maps.stream().filter(Objects::nonNull)
                .collect(Collectors.toList());
        mapList.add(0, firstMap);
        mapList.add(lastMap);
        instanceCounter.incrementAndGet();
        sortMaps();
    }


    @SafeVarargs
    public final void addMaps(Map<?, ?>... maps) {
        Arrays.stream(maps)
                .filter(Objects::nonNull)
                .forEach(mapList::add);
        sortMaps();
    }

    public final void addMapList(List<LinkedMultiMap> maps) {
        maps.stream()
                .filter(Objects::nonNull)
                .forEach(mapList::add);
        sortMaps();
    }


    public void sortMaps(){
        Collections.sort(mapList, (map1, map2) -> {
            int p1 = map1.containsKey(mapPriority) ? Integer.parseInt(map1.get(mapPriority).toString()) : 0;
            int p2 = map2.containsKey(mapPriority) ? Integer.parseInt(map2.get(mapPriority).toString()) : 0;
            return Integer.compare(p1, p2);
        });
    }

    @SafeVarargs
    public final MapsWrapper createNewMapWrapper(Map<?, ?>... maps) {
        return new MapsWrapper(Stream.concat(Arrays.stream(maps), mapList.stream()).toList());
    }


    public final List<LinkedMultiMap> getMapsWithKey(String key) {
        try {
            return (List<LinkedMultiMap>) firstMap.get(key);
        }
        catch (Exception e)
        {
            System.out.println("failed to retrieve map with " + key + ". " +e.getMessage());
        }
        return null;
    }

    public final void addMapsWithKey(String key, List<LinkedMultiMap> maps) {
        firstMap.removeFromLinkedListMultimap(key);
        for (LinkedMultiMap map : maps) {
            {
                firstMap.addToLinkedListMultimap(key, map);
            }
        }
        sortMaps();
    }

    public Object getConfig(String key) {
        return get(configFlag + key);
    }

    @Override
    public String get(Object key) {
        Object baseMapVal = super.get(key);
        if (baseMapVal != null)
            return String.valueOf(baseMapVal);

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





        @Override
    public String toString() {
        // Create a new map to hold the combined results
//        List<Map<String, Object>> combinedMap = new ArrayList<>();
//        combinedMap.add(this);
        StringBuilder returnString = Optional.ofNullable(super.toString()).map(StringBuilder::new).orElse(null);
        // Iterate through all maps in the list
        for (Map<?, ?> map : mapList) {
            returnString = (returnString == null ? new StringBuilder("null") : returnString).append("\n").append(map.toString());
        }

        // Return the string representation of the combined map
        return returnString == null ? null : returnString.toString();
    }


    /**
     * Adds an entry with a key that is guaranteed to be unique by modifying the provided key if necessary.
     * If the key contains a tilde (~) character, it will be replaced with a unique integer.
     * Otherwise, the unique integer will be appended to the end of the key.
     *
     * @param keyString The base key string to use
     * @param value     The value to associate with the modified key
     * @return The modified key that was actually used to store the value
     */
    public String addWithUniqueKey(String keyString, Object value) {
        // Get current counter value for this key or 0 if it doesn't exist

//        System.out.println("## keyString ) : " + keyString);
//        System.out.println("## value ) : " + value);

//        new Exception().printStackTrace();
//        System.out.println("## this.getOrDefault(keyString,0 ) : " +  this.getOrDefault(keyString,0 ));
        int counter = Integer.parseInt(String.valueOf(super.getOrDefault(keyString, "0")));
//        System.out.println("## counter ) : " + counter);
        // Increment the counter in the map
        this.put(keyString, counter + 1);
//        System.out.println("## put-counter ) : " + counter);

        // Create the modified key based on whether it contains a tilde
        String keyNum = instanceCounter + "_" + counter;
//        System.out.println("## keyNum ) : " + keyNum);


        String modifiedKey = String.format(keyString, keyNum);
//        System.out.println("## modifiedKey ) : " + modifiedKey);
        // Store the value with the modified key

        this.put(modifiedKey, value);
//        System.out.println("## modifiedKey : value ) : " + modifiedKey + " : " + value);
        return modifiedKey;
    }


    /**
     * Performs a regex-based match and replace operation on the input text.
     * Each match is stored in the map with a unique key and replaced in the text
     * using a formatted replacementTemplate string if provided, or the unique key directly if replacementTemplate is null.
     *
     * @param text                The input text to process
     * @param regex               The compiled regex pattern to match against
     * @param replacementTemplate The replacementTemplate string to format replacements (e.g., "(%s)"), or null for direct replacement
     * @return The modified text with all matches replaced
     */
    public String matchReplace(String text, Pattern regex, String keyTemplate, String valuePattern, String replacementTemplate) {
        if (text == null || regex == null) {
            throw new IllegalArgumentException("Text and regex pattern cannot be null");
        }

        String result = text;
        boolean matchesFound;

        do {
            Matcher matcher = regex.matcher(result);
            StringBuilder sb = new StringBuilder();
            matchesFound = false;

            while (matcher.find()) {
                String matchString = matcher.group();
                matchesFound = true;

                String value = (valuePattern == null) ? matchString : matchString.replaceAll(regex.pattern(), valuePattern);

                // Store the match and get the unique key
                String uniqueKey = addWithUniqueKey(keyTemplate, value);
                // Use replacementTemplate if provided, otherwise use unique key directly

                String replacementString = replacementTemplate == null ? uniqueKey : String.format(replacementTemplate, uniqueKey);
                ;

                matcher.appendReplacement(sb, replacementString);

            }

            // Finish building the result string
            matcher.appendTail(sb);
            result = sb.toString();

        } while (matchesFound);

        return result;
    }

    public String restoreSubstitutedValues(String input) {
        String result = input;
        String previous;

        do {
            previous = result;
            for (Map.Entry<String, Object> entry : entrySet()) {
                result = result.replace(entry.getKey(), String.valueOf(entry.getValue()));
            }
        } while (!result.equals(previous));

        return result;
    }


}