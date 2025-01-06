package io.pickleball.valueresolution;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseTransformer {
    private static final boolean DEBUG = false;
    private static void debug(String message) {
        if (DEBUG) System.out.println("DEBUG: " + message);
    }
    public static final String MASKED_QUOTE_PATTERN = "\\u2405[\\u2404]+\\u2405";
    private static final char MASK_CONTENT_CHAR = '\u2404';
    private static final char MASK_BOUNDARY_CHAR = '\u2405';
    private static final Pattern QUOTED_STRING_REGEX = Pattern.compile("(['\"`])(.*?)\\1");

    private static final ConcurrentHashMap<Pattern, GroupTransformation> GLOBAL_TRANSFORMATIONS = new ConcurrentHashMap<>();
    private final Map<Pattern, GroupTransformation> localTransformations = new LinkedHashMap<>();

    @FunctionalInterface
    public interface GroupTransformation {
        String apply(MatchResult matchResult);
    }

    public static class MatchResult {
        private final Matcher matcher;
        public MatchResult(Matcher matcher) { this.matcher = matcher; }
        public String group(int group) { return matcher.group(group); }
        public String group(String name) { return matcher.group(name); }
        public int groupCount() { return matcher.groupCount(); }
        public String group() { return matcher.group(); }
    }

    public static void addGlobalTransformation(String regexString, GroupTransformation transformation) {
        debug("Adding global transformation with pattern: " + regexString);
        GLOBAL_TRANSFORMATIONS.put(Pattern.compile(regexString), transformation);
    }

    public static void addGlobalStringTransformation(String regexString, Function<String, String> transformation) {
        debug("Adding global string transformation with pattern: " + regexString);
        addGlobalTransformation(regexString, match -> transformation.apply(match.group()));
    }

    public void addLocalTransformation(String regexString, GroupTransformation transformation) {
        debug("Adding local transformation with pattern: " + regexString);
        localTransformations.put(Pattern.compile(regexString), transformation);
    }

    public void addLocalStringTransformation(String regexString, Function<String, String> transformation) {
        debug("Adding local string transformation with pattern: " + regexString);
        addLocalTransformation(regexString, matchResult -> transformation.apply(matchResult.group()));
    }

    public String transform(String input) {
        debug("\nStarting transformation pipeline with input: " + input);
        Map<String, String> quoteMappings = new HashMap<>();
        String masked = maskQuotedStrings(input, quoteMappings);
        debug("After masking: " + masked);
        debug("Quote mappings: " + quoteMappings);

        String transformed = applyTransformations(masked, localTransformations);
        debug("After local transformations: " + transformed);

        transformed = applyTransformations(transformed, GLOBAL_TRANSFORMATIONS);
        debug("After global transformations: " + transformed);

        String result = unmaskQuotedStrings(transformed, quoteMappings);
        debug("Final result: " + result);

        return result;
    }

    private String maskQuotedStrings(String input, Map<String, String> quoteMappings) {
        debug("\nStarting masking process");
        Matcher m = QUOTED_STRING_REGEX.matcher(input);
        StringBuffer sb = new StringBuffer();
        AtomicInteger counter = new AtomicInteger(1);

        while (m.find()) {
            String quote = m.group(1);
            String content = m.group(2);
            String matchedLiteral = quote + content + quote;

            String placeholder = MASK_BOUNDARY_CHAR +
                   String.valueOf( MASK_CONTENT_CHAR).repeat(counter.getAndIncrement()) +
                    MASK_BOUNDARY_CHAR;

            debug("Masking: " + matchedLiteral + " -> " + placeholder);
            quoteMappings.put(placeholder, matchedLiteral);
            m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String unmaskQuotedStrings(String transformed, Map<String, String> quoteMappings) {
        debug("\nStarting unmasking process");
        return quoteMappings.entrySet().stream()
                .sorted((e1, e2) -> e2.getKey().length() - e1.getKey().length())
                .reduce(transformed, (acc, entry) -> {
                    debug("Unmasking: " + entry.getKey() + " -> " + entry.getValue());
                    return acc.replace(entry.getKey(), entry.getValue());
                }, (a, b) -> b);
    }

    private String applyTransformations(String input, Map<Pattern, GroupTransformation> transformations) {
        debug("\nApplying transformations to: " + input);
        String result = input;
        for (Map.Entry<Pattern, GroupTransformation> entry : transformations.entrySet()) {
            Pattern pattern = entry.getKey();
            debug("Applying transformation with pattern: " + pattern);

            GroupTransformation transformFunction = entry.getValue();
            Matcher matcher = pattern.matcher(result);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                debug("Match found: " + matcher.group());
                for (int i = 0; i <= matcher.groupCount(); i++) {
                    debug("Group " + i + ": " + matcher.group(i));
                }

                String transformed = transformFunction.apply(new MatchResult(matcher));
                debug("Transforming match: '" + matcher.group() + "' -> '" + transformed + "'");
                matcher.appendReplacement(sb, Matcher.quoteReplacement(transformed));
            }
            matcher.appendTail(sb);

            result = sb.toString();
            debug("After this transformation: " + result);
        }
        return result;
    }
}
