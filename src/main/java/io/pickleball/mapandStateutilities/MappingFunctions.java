package io.pickleball.mapandStateutilities;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getMvelWrapper;
import static io.pickleball.configs.Constants.flag1;
//import static io.pickleball.valueresolution.MVELWrapper.evaluateExpression;

public class MappingFunctions {

    @SafeVarargs
    public static String replaceNestedBrackets(String input, Map<String, String>... maps) {
        return replaceNestedBrackets(input, Arrays.stream(maps).toList());

    }

    public static String replaceNestedBrackets(String input, List<Map<String, String>> maps) {
        MapsWrapper mapsWrapper = new MapsWrapper(maps);
        return replaceNestedBrackets(input, mapsWrapper);
    }

    public static String replaceNestedBrackets(String input, MapsWrapper mapsWrapper) {


//        Pattern pattern = Pattern.compile("<(?<json>\\$[^<>]+)>|<(?<angled>[^<>]+)>|\\{(?<curly>[^{}]+)\\}");
//        Pattern pattern = Pattern.compile("<(?<json>\\$[^<>]+)>|<(?<angled>[^<>$=\\s](?:[^<>]*[^<>\\s])?)>|\\{(?<curly>[^{}]+)\\}");
        Pattern pattern = Pattern.compile("<(?<angled>[^<>=\\s](?:[^<>]*[^<>\\s])?)>|\\{(?<curly>[^{}]+)\\}");
        String result = input;


        while (true) {
            Matcher matcher = pattern.matcher(result);

            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String key = matcher.group("angled");
                if (key != null) {
                    String value = String.valueOf(mapsWrapper.getOrDefault(key, "<" + key + ">"));
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
                    continue;
                }

                String expression = matcher.group("curly");
                if (expression != null) {
                    Object expressionReturn = getMvelWrapper().evaluate(expression, mapsWrapper);
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(expressionReturn)));
                    continue;
                }

                String jsonPath = matcher.group("json");
                if (jsonPath != null) {

//                    Object expressionReturn = getMvelWrapper().evaluate(expression, mapsWrapper);
//                    matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(expressionReturn)));
                    continue;
                }
            }


            matcher.appendTail(sb);
            String newResult = sb.toString();

            // If no changes were made in this iteration, break
            if (newResult.equals(result)) {
                break;
            }

            result = newResult;
        }
        return result.replaceAll("<" + flag1 + "[^<>]+>", "");
    }

}
