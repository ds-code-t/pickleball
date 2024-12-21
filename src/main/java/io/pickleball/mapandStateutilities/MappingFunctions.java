package io.pickleball.mapandStateutilities;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.pickleball.configs.Constants.flag1;

public class MappingFunctions {

    public static String replaceNestedBrackets(String input, LinkedMultiMap<String, String>... maps) {
      return replaceNestedBrackets(input, Arrays.stream(maps).toList());

    }
    public static String replaceNestedBrackets(String input, List<LinkedMultiMap<String, String>> maps) {
        String result = input;



        // Simplified pattern that matches text between angle brackets
        // [^<>]+ matches one or more characters that are not angle brackets
        Pattern pattern = Pattern.compile("<([^<>]+)>");
        for (LinkedMultiMap<String, String> map : maps) {
            while (true) {
                Matcher matcher = pattern.matcher(result);
                boolean found = false;
                StringBuffer sb = new StringBuffer();

                while (matcher.find()) {
                    found = true;
                    String key = matcher.group(1);
                    String matchKey = key.trim().replace(flag1, '?');
                    String value = map.getValueByStringOrDefault(matchKey, "");
                    if(value.isEmpty() && matchKey.startsWith("?"))
                        continue;
                    String replacement = value.isEmpty() ? "<"+ flag1  + key + ">" : value;
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }

                if (!found) {
                    break;
                }

                matcher.appendTail(sb);
                String newResult = sb.toString();

                // If no changes were made in this iteration, break
                if (newResult.equals(result)) {
                    break;
                }

                result = newResult;
            }
        }
        return result.replaceAll("<"+flag1+"[^<>]+>","");
//        return result;


    }

}
