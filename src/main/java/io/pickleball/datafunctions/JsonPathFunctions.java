//package io.pickleball.datafunctions;
//
//import com.fasterxml.jackson.databind.node.ArrayNode;
//import com.jayway.jsonpath.*;
//import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
//import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
//
//import java.util.*;
//
//public class JsonPathFunctions {
//
//    private static final ObjectMapper MAPPER = new ObjectMapper();
//
//    public static Map<String, Object> processAndCompare(ObjectNode originalNode, String jsonPath) {
//        ObjectNode modifiedNode = createModifiedNode(originalNode);
//
//        // --------------------------------------------------
//        // A) Configuration to get path strings
//        // --------------------------------------------------
//        Configuration pathConfig = Configuration.builder()
//                .mappingProvider(new JacksonMappingProvider())
//                .jsonProvider(new JacksonJsonNodeJsonProvider())
//                .options(
//                        Option.AS_PATH_LIST,
//                        Option.ALWAYS_RETURN_LIST,
//                        Option.SUPPRESS_EXCEPTIONS
//                )
//                .build();
//
//        DocumentContext originalPathContext = JsonPath.using(pathConfig).parse(originalNode);
//        DocumentContext modifiedPathContext = JsonPath.using(pathConfig).parse(modifiedNode);
//
//        // Force plain Java List<String> for the matched paths
//        TypeRef<List<String>> listOfStringsType = new TypeRef<>() {
//        };
//        List<String> originalPathsList = originalPathContext.read(jsonPath, listOfStringsType);
////        System.out.println("@@originalPathContext:");
////        originalPathsList.forEach(System.out::println);
//        List<String> modifiedPathsList = modifiedPathContext.read(jsonPath, listOfStringsType);
////        System.out.println("@@modifiedPathsList:");
////        modifiedPathsList.forEach(System.out::println);
//        // We'll store them in a set to remove duplicates
//        Set<String> originalPaths = new LinkedHashSet<>(originalPathsList);
//        Set<String> modifiedPaths = new LinkedHashSet<>(modifiedPathsList);
//
//        // --------------------------------------------------
//        // B) Configuration to read the actual values
//        //    (no AS_PATH_LIST here!)
//        // --------------------------------------------------
//        Configuration valueConfig = Configuration.builder()
//                .mappingProvider(new JacksonMappingProvider())
//                .jsonProvider(new JacksonJsonNodeJsonProvider())
//                .options(
//                        // You can still keep ALWAYS_RETURN_LIST if you want arrays
//                        // for multiple matches, but remove AS_PATH_LIST!
//                        Option.ALWAYS_RETURN_LIST,
//                        Option.SUPPRESS_EXCEPTIONS
//                )
//                .build();
//
//        DocumentContext originalValueContext = JsonPath.using(valueConfig).parse(originalNode);
//        DocumentContext modifiedValueContext = JsonPath.using(valueConfig).parse(modifiedNode);
//
//        // Get "path -> value" for each path
//        Map<String, Object> originalPathValueMap = getPathValueMap(originalValueContext, originalPaths);
//        Map<String, Object> modifiedPathValueMap = getPathValueMap(modifiedValueContext, modifiedPaths);
//
//        // Merge results (original has precedence)
//        Map<String, Object> matchMap = new LinkedHashMap<>();
//        Set<String> usedNormalizedPaths = new HashSet<>();
////        System.out.println("\n\n=================\n");
//
//        // Original first
//        for (Map.Entry<String, Object> entry : originalPathValueMap.entrySet()) {
//            String origPath = entry.getKey();
//            Object origVal = entry.getValue();
//            String normPath = normalizeRootLevelIndex(origPath);
////            System.out.println("@@orgNormPath? " + usedNormalizedPaths.add(normPath) + " : " + normPath + " ,origPath: " + origPath + " ,val: "+ origVal);
////            System.out.println("@@E Org: " + origVal + "\norgNormPath? " + usedNormalizedPaths.add(normPath) + " : " + normPath + " ,origPath: " + origPath );
//            usedNormalizedPaths.add(normPath);
//            matchMap.put(origPath, origVal);
//        }
////        System.out.println("\n----------------");
//
//        // Then modified
//        for (Map.Entry<String, Object> entry : modifiedPathValueMap.entrySet()) {
//            String modPath = entry.getKey();
//            Object modVal = entry.getValue();
//            String normPath = normalizeRootLevelIndex(modPath);
////            System.out.println("@@modNormPath? " + usedNormalizedPaths.add(normPath) + " : " + normPath + " ,modPath: " + modPath+ " ,val: "+ modVal);
////            System.out.println("@@E Mod: " + modVal + "\nmodNormPath? " + usedNormalizedPaths.add(normPath) + " : " + normPath + " ,modPath: " + modPath);
//
//            if (usedNormalizedPaths.add(normPath)) {
//                matchMap.put(modPath, modVal);
//            }
//        }
////        System.out.println("\n=================\n\n");
//
//        return matchMap;
//    }
//
//    private static Map<String, Object> getPathValueMap(
//            DocumentContext valueContext,
//            Set<String> absolutePaths
//    ) {
//        Map<String, Object> pathValueMap = new LinkedHashMap<>();
//        for (String path : absolutePaths) {
//            try {
//                ArrayNode arrayNode = valueContext.read(path);
//                Object value = arrayNode.get(0);
////                System.out.println("\n@@path: "+ path);
////                System.out.println("@@value: "+ value.getClass() + " : " + value);
//                pathValueMap.put(path, value);
//            } catch (Exception ignored) {
//            }
//        }
//        return pathValueMap;
//    }
//
//    private static ObjectNode createModifiedNode(ObjectNode originalNode) {
//        ObjectNode modifiedNode = MAPPER.createObjectNode();
//
//        Iterator<String> fieldNames = originalNode.fieldNames();
//        while (fieldNames.hasNext()) {
//            String fieldName = fieldNames.next();
//            var fieldValue = originalNode.get(fieldName);
//
//            if (fieldValue.isArray()) {
//                if (fieldValue.size() == 0) {
//                    // remove empty
//                    continue;
//                } else {
//                    // replace with last item
//                    var lastItem = fieldValue.get(fieldValue.size() - 1);
//                    modifiedNode.set(fieldName, lastItem.deepCopy());
//                }
//            } else {
//                // copy as is
//                modifiedNode.set(fieldName, fieldValue.deepCopy());
//            }
//        }
//        return modifiedNode;
//    }
//
//    /**
//     * Removes the first numeric bracket (e.g. [0]) after the root property
//     * so that $['rootField'][0]['subfield'] -> $['rootField']['subfield']
//     */
//    private static String normalizeRootLevelIndex(String path) {
//        return path.replaceFirst("(\\$\\['[^']*'\\])(\\[\\d+\\])", "$1");
//    }
//
//
//}
