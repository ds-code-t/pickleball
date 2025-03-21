package io.pickleball.valueresolution;

import io.pickleball.stringutilities.QuoteExtracter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseTransformer {
    private static final boolean DEBUG = true;
    private static void debug(String message) {
        if (DEBUG) System.out.println("DEBUG: " + message);
    }
    public static final String MASKED_QUOTE_PATTERN = "\\u2405[\\u2404]+\\u2405";


    private static final ConcurrentHashMap<Pattern, Object> GLOBAL_TRANSFORMATIONS = new ConcurrentHashMap<>();
    private final Map<Pattern, Object> localTransformations = new LinkedHashMap<>();
    public QuoteExtracter extracter =  new QuoteExtracter();


    @FunctionalInterface
    public interface GroupTransformation {
        String apply(MatchResult matchResult); // Original, unchanged
    }

    @FunctionalInterface
    public interface GroupTransformationWithInstance {
        String apply(ParseTransformer transformer, MatchResult matchResult);
    }

    public static class MatchResult {
        private final Matcher matcher;
        public MatchResult(Matcher matcher) { this.matcher = matcher; }
        public String group(int group) { return matcher.group(group); }
        public String group(String name) { return matcher.group(name); }
        public int groupCount() { return matcher.groupCount(); }
        public String group() { return matcher.group(); }
    }

    // Original style
    public static void addGlobalTransformation(String regexString, GroupTransformation transformation) {
        debug("Adding global transformation with pattern: " + regexString);
        GLOBAL_TRANSFORMATIONS.put(Pattern.compile(regexString), transformation);
    }

    // New style
    public static void addGlobalTransformation(String regexString, GroupTransformationWithInstance transformation) {
        debug("Adding global transformation with instance with pattern: " + regexString);
        GLOBAL_TRANSFORMATIONS.put(Pattern.compile(regexString), transformation);
    }

    // Original style
    public static void addGlobalStringTransformation(String regexString, Function<String, String> transformation) {
        debug("Adding global string transformation with pattern: " + regexString);
        addGlobalTransformation(regexString, match -> transformation.apply(match.group()));
    }

    // New style
    public static void addGlobalStringTransformation(String regexString, BiFunction<ParseTransformer, String, String> transformation) {
        debug("Adding global string transformation with instance with pattern: " + regexString);
        addGlobalTransformation(regexString, (transformer, match) -> transformation.apply(transformer, match.group()));
    }

    // Original style
    public void addLocalTransformation(String regexString, GroupTransformation transformation) {
        debug("Adding local transformation with pattern: " + regexString);
        localTransformations.put(Pattern.compile(regexString), transformation);
    }

    // New style
    public void addLocalTransformation(String regexString, GroupTransformationWithInstance transformation) {
        debug("Adding local transformation with instance with pattern: " + regexString);
        localTransformations.put(Pattern.compile(regexString), transformation);
    }

    // Original style
    public void addLocalStringTransformation(String regexString, Function<String, String> transformation) {
        debug("Adding local string transformation with pattern: " + regexString);
        addLocalTransformation(regexString, matchResult -> transformation.apply(matchResult.group()));
    }

    // New style
    public void addLocalStringTransformation(String regexString, BiFunction<ParseTransformer, String, String> transformation) {
        debug("Adding local string transformation with instance with pattern: " + regexString);
        addLocalTransformation(regexString, (transformer, matchResult) -> transformation.apply(transformer, matchResult.group()));
    }

    public String transform(String input) {
        debug("\nStarting transformation pipeline with input: " + input);
        extracter.clear();
        String masked = extracter.maskQuotedStrings(input);
        debug("After masking: " + masked);
        debug("Quote mappings: " + extracter);

        String transformed = applyTransformations(masked, localTransformations);
        debug("After local transformations: " + transformed);

        transformed = applyTransformations(transformed, GLOBAL_TRANSFORMATIONS);
        debug("After global transformations: " + transformed);

        String result = extracter.unmaskQuotedStrings(transformed);
        debug("Final result: " + result);
        return result;
    }


    private String applyTransformations(String input, Map<Pattern, Object> transformations) {
        debug("\nApplying transformations to: " + input);
        String result = input;
        for (Map.Entry<Pattern, Object> entry : transformations.entrySet()) {
            Pattern pattern = entry.getKey();
            debug("Applying transformation with pattern: " + pattern);

            Object transformFunction = entry.getValue();
            Matcher matcher = pattern.matcher(result);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                debug("Match found: " + matcher.group());
                String transformed;
                if (transformFunction instanceof GroupTransformation) {
                    transformed = ((GroupTransformation) transformFunction).apply(new MatchResult(matcher));
                } else if (transformFunction instanceof GroupTransformationWithInstance) {
                    transformed = ((GroupTransformationWithInstance) transformFunction).apply(this, new MatchResult(matcher));
                } else {
                    throw new IllegalStateException("Unknown transformation type: " + transformFunction.getClass());
                }
                debug("Transforming match: '" + matcher.group() + "' -> '" + transformed + "'");
                matcher.appendReplacement(sb, Matcher.quoteReplacement(transformed));
            }
            matcher.appendTail(sb);

            result = sb.toString();
            debug("After this transformation: " + result);
        }
        return result;
    }


    public static String stripQuotes(String input) {
        if (input == null || input.length() < 2) {
            return input; // Too short or null, return as-is
        }
        input = input.strip();

        char firstChar = input.charAt(0);
        char lastChar = input.charAt(input.length() - 1);

        // Check if it starts and ends with matching quotes
        if (firstChar == lastChar && (firstChar == '"' || firstChar == '\'' || firstChar == '`')) {
            return input.substring(1, input.length() - 1); // Strip quotes
        }

        return input; // No quotes, mismatched, or malformed, return original
    }
}
