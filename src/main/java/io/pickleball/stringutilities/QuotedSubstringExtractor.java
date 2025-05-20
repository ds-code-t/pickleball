//package io.pickleball.stringutilities;
//
//import java.util.HashMap;
//import java.util.Map;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//import static io.pickleball.stringutilities.Constants.flag2;
//
//
//public class QuotedSubstringExtractor {
//
//
//
//    private static final Pattern PATTERN = Pattern.compile(
//            "([\"'`])"            // Capture opening quote, single quote, or backtick
//                    + "((?:\\\\.|(?!\\1).)*?)" // Capture any escaped char or any char that isn't the delimiter
//                    + "\\1"                 // Match the same quote/backtick as closing
//    );
//
//    public static Map<String, String> extractQuotedSubstrings(String input) {
//        Map<String, String> map = new HashMap<>();
//        map.put("originalText", input);
//        Matcher matcher = PATTERN.matcher(input);
//        StringBuffer result = new StringBuffer();
//        while (matcher.find()) {
//            String inside;
//            String key = "quoted";
//            switch (matcher.group(1))
//            {
//                case "`":
//                    key += "_bt";
//                    inside = "\"" + matcher.group(2).replaceAll("(?<!\\\\)\"", "\\\\\"") + "\"";
//                    break;
//                case "'":
//                    key += "_sq";
//                    inside = "\"" + matcher.group(2) + "\"";
//                    break;
//                default:
//                    key += "_dq";
//                    inside =  matcher.group(0) ;
//                    break;
//            }
//
//
//            if (matcher.group(1).equals("`"))
//                inside = "\"" + matcher.group(2).replaceAll("(?<!\\\\)\"", "\\\\\"") + "\"";
//            else
//                inside = matcher.group(0);
//            key += "_" + map.size();
//            map.put(key, inside);                   // store the inside content
//            String replacementKey = flag2 + key + flag2;
//            matcher.appendReplacement(result, replacementKey); // replace the whole match with the key
//        }
//        matcher.appendTail(result);
//        map.put("modifiedText", result.toString());
//        return map;
//    }
//
//    public static String restoreQuotedSubstrings(String modifiedText, Map<String, String> quotedStrings) {
//        Pattern restorePattern = Pattern.compile(flag2 + "(key\\d+)" + flag2 );
//        Matcher m = restorePattern.matcher(modifiedText);
//        StringBuffer restored = new StringBuffer();
//
//        while (m.find()) {
//            String key = m.group(1);
//            String originalQuoted = quotedStrings.get(key);
//            m.appendReplacement(restored, Matcher.quoteReplacement(originalQuoted));
//        }
//        m.appendTail(restored);
//        return restored.toString();
//    }
//
//
//    private static final Pattern PARENTHESES_PATTERN = Pattern.compile(
//            "\\("                 // Opening parenthesis
//                    + "(.*?)"     // Non-greedy capture of everything between parentheses
//                    + "\\)"       // Closing parenthesis
//    );
//
//    public static Map<String, String> extractParenthesizedSubstrings(String input) {
//        Map<String, String> map = new HashMap<>();
//        map.put("originalText", input);
//        Matcher matcher = PARENTHESES_PATTERN.matcher(input);
//        StringBuffer result = new StringBuffer();
//        while (matcher.find()) {
//            String inside = matcher.group(0);  // Get the full match including parentheses
//            String key = "paren_" + map.size();
//            map.put(key, inside);
//            String replacementKey = flag2 + key + flag2;
//            matcher.appendReplacement(result, replacementKey);
//        }
//        matcher.appendTail(result);
//        map.put("modifiedText", result.toString());
//        return map;
//    }
//
//
//
//    public static String restoreParenthesizedSubstrings(String modifiedText, Map<String, String> parenthesizedStrings) {
//        Pattern restorePattern = Pattern.compile(flag2 + "(paren_\\d+)" + flag2);
//        Matcher m = restorePattern.matcher(modifiedText);
//        StringBuffer restored = new StringBuffer();
//
//        while (m.find()) {
//            String key = m.group(1);
//            String originalParenthesized = parenthesizedStrings.get(key);
//            m.appendReplacement(restored, Matcher.quoteReplacement(originalParenthesized));
//        }
//        m.appendTail(restored);
//        return restored.toString();
//    }
//
//    public static void main(String[] args) {
//        String input = "This is a test (with parens) and (more parens)";
//        Map<String, String> extracted = extractParenthesizedSubstrings(input);
//        String modified = extracted.get("modifiedText");
//// ... do something with the modified text ...
//
//        String restored = restoreParenthesizedSubstrings(modified, extracted);
//
//    }
//
//
//
//}
