//package io.pickleball.mapandStateutilities;
//
//import java.util.HashMap;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//
//public class EvalMap extends HashMap<String, Object> {
//
//
//    private final Object defaultValue;
//
//    public EvalMap() {
//        this(null);
//    }
//
//    public EvalMap(Object defaultValue) {
//        this.defaultValue = defaultValue;
//    }
//
//    @Override
//    public Object get(Object key) {
//        Object value = super.get(key);
//
//        if (value == null) {
//            return defaultValue;
//        }
//
//        if (value instanceof String) {
//            String strValue = (String) value;
//            if (strValue.equalsIgnoreCase("true")) {
//                return true;
//            } else if (strValue.equalsIgnoreCase("false")) {
//                return false;
//            }
//        }
//
//        return value;
//    }
//
//
//
////
////    private static final char delimiter = '\u2402';
////
////    private static final Pattern QUOTE_PATTERN = Pattern.compile(
////            "([\"'`])"           // Capture opening quote, single quote, or backtick
////                    + "((?:\\\\.|(?!\\1).)*?)" // Capture any escaped char or any char that isn't the delimiter
////                    + "\\1"              // Match the same quote/backtick as closing
////    );
////
////    private static final Pattern PARENTHESES_PATTERN = Pattern.compile(
////            "\\("                // Opening parenthesis
////                    + "(.*?)"           // Non-greedy capture of everything between parentheses
////                    + "\\)"             // Closing parenthesis
////    );
////
//
////
////    public EvalMap() {
////        this(null);
////    }
////
////    @Override
////    public Object get(Object key) {
////        Object value = super.get(key);
////
////        if (value == null) {
////            return defaultValue;
////        }
////
////        if (value instanceof String) {
////            String strValue = (String) value;
////            if (strValue.equalsIgnoreCase("true")) {
////                return true;
////            } else if (strValue.equalsIgnoreCase("false")) {
////                return false;
////            }
////        }
////
////        return value;
////    }
////
////
////
////
////    /**
////     * Extracts quoted substrings from input and stores them in this map.
////     * @param input The input string to process
////     * @return The modified string with quotes replaced by keys
////     */
////    public String extractQuotedSubstrings(String input) {
////        put("originalText", input);
////        Matcher matcher = QUOTE_PATTERN.matcher(input);
////        StringBuffer result = new StringBuffer();
////
////        while (matcher.find()) {
////            String inside;
////            String key = "quoted";
////
////            switch (matcher.group(1)) {
////                case "`":
////                    key += "_bt";
////                    inside = "\"" + matcher.group(2).replaceAll("(?<!\\\\)\"", "\\\\\"") + "\"";
////                    break;
////                case "'":
////                    key += "_sq";
////                    inside = "\"" + matcher.group(2) + "\"";
////                    break;
////                default:
////                    key += "_dq";
////                    inside = matcher.group(0);
////                    break;
////            }
////
////            key += "_" + size();
////            put(key, inside);
////            String replacementKey = delimiter + key + delimiter;
////            matcher.appendReplacement(result, replacementKey);
////        }
////
////        matcher.appendTail(result);
////        put("quotedExtracted", result.toString());
////        return result.toString();
////    }
////
////    /**
////     * Restores quoted substrings from the map back into the text.
////     * @param modifiedText The text with quote placeholders
////     * @return The restored text with original quotes
////     */
////    public String restoreQuotedSubstrings(String modifiedText) {
////        Pattern restorePattern = Pattern.compile(delimiter + "(quoted_(?:bt|sq|dq)_\\d+)" + delimiter);
////        Matcher m = restorePattern.matcher(modifiedText);
////        StringBuffer restored = new StringBuffer();
////
////        while (m.find()) {
////            String key = m.group(1);
////            Object value = get(key);
////            if (value instanceof String) {
////                m.appendReplacement(restored, Matcher.quoteReplacement((String)value));
////            }
////        }
////
////        m.appendTail(restored);
////        return restored.toString();
////    }
////
////    /**
////     * Extracts parenthesized substrings from input and stores them in this map.
////     * @param input The input string to process
////     * @return The modified string with parentheses replaced by keys
////     */
////    public String extractParenthesizedSubstrings(String input) {
////        Matcher matcher = PARENTHESES_PATTERN.matcher(input);
////        StringBuffer result = new StringBuffer();
////
////        while (matcher.find()) {
////            String inside = matcher.group(0);
////            String key = "paren_" + size();
////            put(key, inside);
////            String replacementKey = delimiter + key + delimiter;
////            matcher.appendReplacement(result, replacementKey);
////        }
////
////        matcher.appendTail(result);
////        put("modifiedText", result.toString());
////        return result.toString();
////    }
////
////    /**
////     * Restores parenthesized substrings from the map back into the text.
////     * @param modifiedText The text with parentheses placeholders
////     * @return The restored text with original parentheses
////     */
////    public String restoreParenthesizedSubstrings(String modifiedText) {
////        Pattern restorePattern = Pattern.compile(delimiter + "(paren_\\d+)" + delimiter);
////        Matcher m = restorePattern.matcher(modifiedText);
////        StringBuffer restored = new StringBuffer();
////
////        while (m.find()) {
////            String key = m.group(1);
////            Object value = get(key);
////            if (value instanceof String) {
////                m.appendReplacement(restored, Matcher.quoteReplacement((String)value));
////            }
////        }
////
////        m.appendTail(restored);
////        return restored.toString();
////    }
//}