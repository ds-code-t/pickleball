package io.pickleball.stringutilities;

import io.pickleball.datafunctions.ValWrapper;
import io.pickleball.exceptions.PickleballException;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.pickleball.datafunctions.ValWrapper.wrapVal;
import static io.pickleball.valueresolution.ExpressionEvaluator.VALUE_PREFIX;

public class QuoteExtracter extends HashMap<String, Object> {
    // Pattern that captures quoted strings, handling escaped quotes
    public static final Pattern QUOTED_STRING_REGEX = Pattern.compile("(?<!\\\\)(['\"`])((?:\\\\.|(?!\\1).)*?)(?<!\\\\)(?:\\\\\\\\)*\\1");

    private static final char MASK_CONTENT_CHAR = '\u2404';
    private static final char MASK_BOUNDARY_CHAR = '\u2405';
    final static String QUOTE_PREFIX = VALUE_PREFIX + "_Q";
    // Debug flag to enable/disable debug output
    private boolean debugEnabled = false;


    public QuoteExtracter() {
        put(QUOTE_PREFIX + "_TD_0__", wrapVal("\"\""));
        put(QUOTE_PREFIX + "_TS_0__", wrapVal("''"));
        put(QUOTE_PREFIX + "_TB_0__", wrapVal("``"));
    }

    /**
     * Counter for generating unique placeholders
     */
    private final AtomicInteger placeholderCounter = new AtomicInteger(1);

    /**
     * Generates a unique placeholder key for masking quoted strings.
     * Uses an incrementing counter to ensure each placeholder is distinct.
     *
     * @return A unique placeholder string
     */
    private String generateUnmatchableKey() {
        return MASK_BOUNDARY_CHAR +
                String.valueOf(MASK_CONTENT_CHAR).repeat(placeholderCounter.getAndIncrement()) +
                MASK_BOUNDARY_CHAR;
    }

    private String generateQuoteKey(String quote) {
        if (quote == null || quote.isEmpty())
            throw new PickleballException("illegal quote type.  Quote must be bookended with matching double quotes, single quotes, or back ticks.  Quote: " + quote);

        String quoteType = switch (quote) {
            case "\"" -> "D";
            case "'" -> "S";
            case "`" -> "B";
            default ->
                    throw new PickleballException("illegal quote type.  Quote must be bookended with matching double quotes, single quotes, or back ticks.  Quote: " + quote);
        };

        return QUOTE_PREFIX + "_T" + quoteType + "_" + placeholderCounter.getAndIncrement() + "__";
    }

    /**
     * Masks quoted strings in the input text and stores the mappings in this map.
     * Each quoted string is replaced with a unique placeholder that can be
     * reversed later using unmaskQuotedStrings.
     *
     * @param input The text to process
     * @return The masked text with placeholders
     */
    public String maskQuotedStrings(String input) {
        return maskQuotedStrings(input, false);

    }

    public String maskQuotedStrings(String input, boolean readableKey) {
        debug("\nStarting masking process");
        Matcher m = QUOTED_STRING_REGEX.matcher(input);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String quote = m.group(1);
            String content = m.group(2);
            String matchedLiteral = quote + content + quote;

            String placeholder = readableKey ? generateQuoteKey(quote) : generateUnmatchableKey();

            debug("Masking: " + matchedLiteral + " -> " + placeholder);
            this.put(placeholder, wrapVal(matchedLiteral));
            m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Unmasks the quoted strings in the transformed text using the mappings stored in this map
     *
     * @param transformed The masked text with placeholders
     * @return The original text with quoted strings restored
     */
    public String unmaskQuotedStrings(String transformed) {
        debug("\nStarting unmasking process");
        return this.entrySet().stream()
                .sorted((e1, e2) -> e2.getKey().length() - e1.getKey().length())
                .reduce(transformed, (acc, entry) -> {
                    debug("Unmasking: " + entry.getKey() + " -> " + entry.getValue());
                    return acc.replace(entry.getKey(), String.valueOf(entry.getValue()));
                }, (a, b) -> b);
    }

    /**
     * Enables or disables debug output
     *
     * @param enabled true to enable debug output, false to disable
     */
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
    }

    /**
     * Prints a debug message if debug is enabled
     *
     * @param message The message to print
     */
    private void debug(String message) {
        if (debugEnabled) {
            System.out.println("[DEBUG] " + message);
        }
    }

    /**
     * Clears the stored mappings and resets this object
     */
    public void reset() {
        this.clear();
    }


    /**
     * Converts a square-bracketed list string to a List, unmasking any quoted elements
     */
    public java.util.List<Object> unMaskToList(String listStr) {
        // Remove brackets
        String content = listStr.substring(1, listStr.length() - 1);

        // Split by commas and process each item
        return java.util.Arrays.stream(content.split(","))
                .map(String::trim)
                .map(item -> this.getOrDefault(item, item))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Converts a map string to a Map, unmasking any quoted elements in keys and values
     */
    public java.util.Map<String, Object> unMaskToMap(String mapStr) {
        // Remove brackets
        String content = mapStr.substring(1, mapStr.length() - 1);

        java.util.Map<String, Object> resultMap = new java.util.HashMap<>();

        // Split by commas to get key-value pairs
        for (String pair : content.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2) {
                String key = parts[0].trim();
                Object value = parts[1].trim();

                // Unmask key and value if they are in the map
                key = String.valueOf(this.getOrDefault(key, key));
                value = this.getOrDefault(value, value);

                resultMap.put(key, value);
            }
        }

        return resultMap;
    }
}