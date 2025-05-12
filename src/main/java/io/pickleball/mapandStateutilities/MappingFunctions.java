package io.pickleball.mapandStateutilities;

import io.pickleball.exceptions.PickleballException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getMvelWrapper;
import static io.pickleball.configs.Constants.errorFlag;
import static io.pickleball.configs.Constants.flag1;
//import static io.pickleball.valueresolution.MVELWrapper.evaluateExpression;

public class MappingFunctions {

    @SafeVarargs
    public static Object replaceNestedBrackets(Object input, Map<String, String>... maps) {
        if (!(input instanceof String))
            return input;
        return replaceNestedBrackets(input, Arrays.stream(maps).toList());
    }

    public static Object replaceNestedBrackets(Object input, List<Map<String, String>> maps) {
        if (!(input instanceof String))
            return input;
        MapsWrapper mapsWrapper = new MapsWrapper(maps);
        return replaceNestedBrackets(input, mapsWrapper);
    }

    public static Object replaceNestedBrackets(Object input, MapsWrapper mapsWrapper) {
        if (!(input instanceof String))
            return input;
//        Pattern pattern = Pattern.compile("<(?<angled>[^<>=\\s](?:[^<>]*[^<>\\s])?)>|\\{(?<curly>[^{}]+)\\}");
        Pattern pattern = Pattern.compile("<(?<angled>[^<>=\\s](?:[^<>]*[^<>\\s])?)>|\\{\\{(?<curly>[^{}]+)\\}\\}");
        String result = String.valueOf(input);


        int replacementOrder = 0;

        while (true) {
            Matcher matcher = pattern.matcher(result);

            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String key = matcher.group("angled");
                if (key != null) {
                    String replacedKey = String.valueOf(replaceNestedBrackets(key, mapsWrapper));
                    String value = String.valueOf(mapsWrapper.getOrDefault(replacedKey, "<" + replacedKey + ">"));
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
                    continue;
                }


//                String jsonPath = matcher.group("json");
//                if (jsonPath != null) {
////                    Object expressionReturn = getMvelWrapper().evaluate(expression, mapsWrapper);
////                    matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(expressionReturn)));
//                    continue;
//                }

                String expression = matcher.group("curly");

                if (expression != null) {
                    String replaceExpressionString = String.valueOf(replaceNestedBrackets(expression, mapsWrapper));
                    Object expressionReturn = getMvelWrapper().evaluate(replaceExpressionString, mapsWrapper);
                    if (!(expressionReturn instanceof String))
                        return expressionReturn;
//                        throw new PickleballException("Failed to evaluate expression '" + expression + "'");
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(expressionReturn)));
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
