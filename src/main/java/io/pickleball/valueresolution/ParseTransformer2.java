//package io.pickleball.valueresolution;
//
//import java.util.LinkedHashMap;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//import static io.pickleball.configs.Constants.QUOTED_STRING_REGEX;
//
//public class ParseTransformer2 {
//    public static final String MASK_CHAR = String.valueOf('\u2404');
//    private static final int MAX_ITERATIONS = 10;
//    private static final boolean DEBUG = true;
//
//    private static final ConcurrentHashMap<Pattern, GroupTransformation> GLOBAL_TRANSFORMATIONS =
//            new ConcurrentHashMap<>();
//    private final Map<Pattern, GroupTransformation> localTransformations = new LinkedHashMap<>();
//
//    @FunctionalInterface
//    public interface GroupTransformation {
//        String apply(MatchResult matchResult);
//    }
//
//    public static class MatchResult {
//        private final Matcher matcher;
//        private final Map<String, QuotedContent> placeholderMap;
//
//        public MatchResult(Matcher matcher, Map<String, QuotedContent> placeholderMap) {
//            this.matcher = matcher;
//            this.placeholderMap = placeholderMap;
//        }
//
//        public String group(int group) {
//            return matcher.group(group);
//        }
//
//        public String group(String name) {
//            return matcher.group(name);
//        }
//
//        public String group() {
//            return matcher.group();
//        }
//
//        public Map<String, QuotedContent> getPlaceholderMap() {
//            return placeholderMap;
//        }
//    }
//
//    public void addLocalTransformation(String regexString, GroupTransformation transformation) {
//        if (DEBUG) System.out.println("Adding local transformation with pattern: " + regexString);
//        localTransformations.put(Pattern.compile(regexString), transformation);
//    }
//
//    public static void addGlobalTransformation(String regexString, GroupTransformation transformation) {
//        if (DEBUG) System.out.println("Adding global transformation with pattern: " + regexString);
//        GLOBAL_TRANSFORMATIONS.put(Pattern.compile(regexString), transformation);
//    }
//
//    public String transform(String input) {
//        if (DEBUG) System.out.println("\nTransforming input: " + input);
//
//        // Initial masking of quoted strings
//        MaskResult maskResult = maskQuotedStrings(input);
//        if (DEBUG) System.out.println("Masked text: " + maskResult.masked);
//        if (DEBUG) System.out.println("Placeholder map: " + maskResult.placeholderMap);
//
//        // Apply transformations with iteration limit
//        String result = applyAllTransformations(maskResult);
//
//        // Final unmasking
//        if (DEBUG) System.out.println("Final masked result before unmasking: " + result);
//        String unmasked = unmaskQuotedStrings(result, maskResult.placeholderMap);
//        if (DEBUG) System.out.println("Final unmasked result: " + unmasked);
//
//        return unmasked;
//    }
//
//    private String applyAllTransformations(MaskResult maskResult) {
//        String current = maskResult.masked;
//        int iteration = 0;
//
//        while (iteration < MAX_ITERATIONS) {
//            if (DEBUG) System.out.println("\nIteration " + iteration);
//
//            // Apply local transformations
//            String afterLocal = applyTransformations(current, localTransformations, maskResult.placeholderMap);
//
//            // Apply global transformations
//            String afterGlobal = applyTransformations(afterLocal, GLOBAL_TRANSFORMATIONS, maskResult.placeholderMap);
//
//            // Check if any changes occurred
//            if (afterGlobal.equals(current)) {
//                if (DEBUG) System.out.println("No changes detected, stopping iterations");
//                return current;
//            }
//
//            current = afterGlobal;
//            iteration++;
//        }
//
//        throw new RuntimeException("Exceeded maximum iterations (" + MAX_ITERATIONS + ")");
//    }
//
//    private String applyTransformations(
//            String input,
//            Map<Pattern, GroupTransformation> transformations,
//            Map<String, QuotedContent> placeholderMap
//    ) {
//        String result = input;
//
//        for (Map.Entry<Pattern, GroupTransformation> entry : transformations.entrySet()) {
//            Pattern pattern = entry.getKey();
//            GroupTransformation transform = entry.getValue();
//
//            if (DEBUG) System.out.println("Applying pattern: " + pattern);
//
//            Matcher matcher = pattern.matcher(result);
//            StringBuffer sb = new StringBuffer();
//
//            while (matcher.find()) {
//                String match = matcher.group();
//                if (DEBUG) System.out.println("Found match: " + match);
//
//                String replacement = transform.apply(new MatchResult(matcher, placeholderMap));
//                if (DEBUG) System.out.println("Replacement: " + replacement);
//
//                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
//            }
//
//            matcher.appendTail(sb);
//            result = sb.toString();
//
//            if (DEBUG) System.out.println("After transformation: " + result);
//        }
//
//        return result;
//    }
//
//    private static class MaskResult {
//        final String masked;
//        final Map<String, QuotedContent> placeholderMap;
//
//        MaskResult(String masked, Map<String, QuotedContent> placeholderMap) {
//            this.masked = masked;
//            this.placeholderMap = placeholderMap;
//        }
//    }
//
//    private static class QuotedContent {
//        final String content;
//        final char quoteType;
//
//        QuotedContent(String content, char quoteType) {
//            this.content = content;
//            this.quoteType = quoteType;
//        }
//
//        String getFullQuotedString() {
//            return quoteType + content + quoteType;
//        }
//
//        @Override
//        public String toString() {
//            return "QuotedContent{" +
//                    "content='" + content + '\'' +
//                    ", quoteType=" + quoteType +
//                    '}';
//        }
//    }
//
//    private MaskResult maskQuotedStrings(String input) {
//        Matcher m = QUOTED_STRING_REGEX.matcher(input);
//        StringBuffer sb = new StringBuffer();
//        Map<String, QuotedContent> placeholderMap = new LinkedHashMap<>();
//        int counter = 1;
//
//        while (m.find()) {
//            String matchedLiteral = m.group(0);
//            char quoteType = matchedLiteral.charAt(0);
//            String content = matchedLiteral.substring(1, matchedLiteral.length() - 1);
//
//            String placeholder = String.valueOf(MASK_CHAR).repeat(counter++);
//            placeholderMap.put(placeholder, new QuotedContent(content, quoteType));
//
//            String masked = quoteType + placeholder + quoteType;
//            m.appendReplacement(sb, Matcher.quoteReplacement(masked));
//
//            if (DEBUG) System.out.println("Masked " + matchedLiteral + " to " + masked);
//        }
//
//        m.appendTail(sb);
//        return new MaskResult(sb.toString(), placeholderMap);
//    }
//
//    private String unmaskQuotedStrings(String transformed, Map<String, QuotedContent> placeholderMap) {
//        Pattern maskPattern = Pattern.compile("(['\"`])(" + MASK_CHAR + "+)(['\"`])");
//        Matcher m = maskPattern.matcher(transformed);
//        StringBuffer sb = new StringBuffer();
//
//        while (m.find()) {
//            String repeatedMask = m.group(2);
//            QuotedContent qc = placeholderMap.get(repeatedMask);
//
//            if (qc == null) {
//                if (DEBUG) System.out.println("Warning: No content found for mask: " + repeatedMask);
//                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
//                continue;
//            }
//
//            String restored = qc.getFullQuotedString();
//            m.appendReplacement(sb, Matcher.quoteReplacement(restored));
//
//            if (DEBUG) System.out.println("Unmasked " + m.group(0) + " to " + restored);
//        }
//
//        m.appendTail(sb);
//        return sb.toString();
//    }
//}