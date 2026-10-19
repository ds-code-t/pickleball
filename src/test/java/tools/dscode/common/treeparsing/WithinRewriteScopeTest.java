package tools.dscode.common.treeparsing;

import org.junit.jupiter.api.Test;
import tools.dscode.common.treeparsing.preparsing.BracketMasker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.treeparsing.RegexUtil.normalizeWhitespace;
import static tools.dscode.common.treeparsing.preparsing.LineData.getBracketMasker;

class WithinRewriteScopeTest {

    private static final Pattern STEP_LINE = Pattern.compile(
            "^\\s*(?:\\*|Given|When|Then|And|But)\\b.*");
    private static final Pattern WITHIN_DURATION = Pattern.compile(
            "\\bwithin\\s+(\\d+(?:\\.\\d+)?)\\s+((?:second|minute|hour|day|week|month|year)s?)\\b");
    private static final Pattern COMPARISON_KEYWORD = Pattern.compile(
            "\\b(?:at\\s+least|at\\s+most|equals|equal|greater|less|before|after)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WITHIN_MARGIN = Pattern.compile(
            "\\bwithin\\s+(?:(?:a|the)\\s+)?margin-of\\b");

    @Test
    void comparisonWithinBecomesMarginOfAndWaitsStay() {
        assertEquals(
                "\"A\" is equal to \"B\" margin-of 5 minutes",
                DefinitionContext.preParseDynamicStepString(
                        "\"A\" is equal to \"B\" within 5 minutes"));
        assertEquals(
                "\"A\" is equal to \"B\" margin-of 5 minutes",
                DefinitionContext.preParseDynamicStepString(
                        "\"A\" is equal to \"B\" within a margin of 5 minutes"));
        assertEquals(
                "wait for dialog within 30 seconds",
                DefinitionContext.preParseDynamicStepString(
                        "wait for the dialog within 30 seconds"));
        assertEquals(
                "click button within 2 minutes",
                DefinitionContext.preParseDynamicStepString(
                        "click the button within 2 minutes"));
        assertEquals(
                "click button \"within 5 minutes\"",
                DefinitionContext.preParseDynamicStepString(
                        "click the button \"within 5 minutes\""));
        assertEquals(
                "verify \"A\" equals \"B\" margin-of 5 minutes, then wait within 30 seconds",
                DefinitionContext.preParseDynamicStepString(
                        "verify \"A\" equals \"B\" within 5 minutes, then wait within 30 seconds"));
        assertEquals(
                "within 5 minutes is greater than 1",
                DefinitionContext.preParseDynamicStepString(
                        "within 5 minutes is greater than 1"));
        assertEquals(
                "click \"equal\" within 5 minutes",
                DefinitionContext.preParseDynamicStepString(
                        "click \"equal\" within 5 minutes"));
        assertEquals(
                "count is at least 2 margin-of 1 minute",
                DefinitionContext.preParseDynamicStepString(
                        "count is at least 2 within 1 minute"));
        assertEquals(
                "equal to 1 margin-of 2 seconds; wait within 3 seconds",
                DefinitionContext.preParseDynamicStepString(
                        "equal to 1 within 2 seconds; wait within 3 seconds"));

        String quotedOnly = DefinitionContext.preParseDynamicStepString(
                "wait \"within 5 minutes\"");
        assertTrue(quotedOnly.contains("within 5 minutes"), quotedOnly);
        assertFalse(quotedOnly.contains("margin-of"), quotedOnly);
    }

    @Test
    void consumerStepLinesMatchPre217ExceptComparisonWithin() throws IOException {
        Path root = Path.of("maven-consumer-project").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(root), root.toString());
        List<String> unexpected = new ArrayList<>();
        int steps = 0;
        int allowed = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".feature")).toList()) {
                int lineNumber = 0;
                for (String line : Files.readAllLines(file)) {
                    lineNumber++;
                    if (!STEP_LINE.matcher(line).matches()) {
                        continue;
                    }
                    steps++;
                    String current = DefinitionContext.preParseDynamicStepString(line);
                    String historical = preParseAt42404209(line);
                    String keptMargin = WITHIN_MARGIN.matcher(historical).replaceAll("margin-of");
                    if (current.equals(keptMargin)) {
                        continue;
                    }
                    if (withinFollowsComparison(line)) {
                        allowed++;
                        assertFalse(current.contains("within " + withinAmount(line)), current);
                        continue;
                    }
                    unexpected.add(root.relativize(file) + ":" + lineNumber
                            + "\n  line=" + line
                            + "\n  42404209=" + historical
                            + "\n  kept=" + keptMargin
                            + "\n  current=" + current);
                }
            }
        }
        assertTrue(steps > 0, "expected consumer feature steps");
        assertTrue(allowed > 0, "expected at least one comparison within-duration step");
        assertEquals(List.of(), unexpected);
    }

    /**
     * Pre-parse as of 42404209, before either within rewrite. The {@code margin of}
     * normalisation is the only duration wording that version changed.
     */
    private static String preParseAt42404209(String input) {
        BracketMasker bm = getBracketMasker(input);
        String parsedText = normalizeWhitespace(bm.masked())
                .replaceAll("\\bmargin\\s+of\\b", "margin-of")
                .replaceAll("\\bno\\s+attribute\\b", "noattribute")
                .replaceAll("\\b(?:the|a)\\b", "")
                .replaceAll("\\bare\\b", "is")
                .replaceAll("\\bhave\\b", "has")
                .replaceAll("(\\d+)(?:\\s*(?:st|nd|rd|th)\\b)", "#$1")
                .replaceAll("\\bverifies\\b", "verify")
                .replaceAll("\\bensures\\b", "ensure")
                .replaceAll("\\bnot\\b|n't\\b", " no ")
                .replaceAll("\\s+", " ");
        return bm.restoreFrom(parsedText);
    }

    private static boolean withinFollowsComparison(String line) {
        int phraseStart = 0;
        int index = 0;
        while (index <= line.length()) {
            if (index == line.length() || line.charAt(index) == ',' || line.charAt(index) == ';') {
                if (phraseFollows(line.substring(phraseStart, index))) {
                    return true;
                }
                phraseStart = index + 1;
                index = phraseStart;
                continue;
            }
            index = skipAtom(line, index);
        }
        return false;
    }

    private static boolean phraseFollows(String phrase) {
        List<int[]> quotes = quoteSpans(phrase);
        List<Integer> keywordEnds = new ArrayList<>();
        Matcher keywords = COMPARISON_KEYWORD.matcher(phrase);
        while (keywords.find()) {
            if (!inside(quotes, keywords.start())) {
                keywordEnds.add(keywords.end());
            }
        }
        Matcher durations = WITHIN_DURATION.matcher(phrase);
        while (durations.find()) {
            if (inside(quotes, durations.start())) {
                continue;
            }
            for (int end : keywordEnds) {
                if (end <= durations.start()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String withinAmount(String line) {
        Matcher durations = WITHIN_DURATION.matcher(line);
        return durations.find() ? durations.group(1) : "";
    }

    private static int skipAtom(String input, int index) {
        if (index + 2 < input.length()
                && input.startsWith("'''", index)
                && !escaped(input, index)) {
            int close = input.indexOf("'''", index + 3);
            return close < 0 ? input.length() : close + 3;
        }
        char current = input.charAt(index);
        if ((current == '"' || current == '\'' || current == '`') && !escaped(input, index)) {
            int close = index + 1;
            while (close < input.length()
                    && !(input.charAt(close) == current && !escaped(input, close))) {
                close++;
            }
            return close >= input.length() ? input.length() : close + 1;
        }
        return index + 1;
    }

    private static List<int[]> quoteSpans(String input) {
        List<int[]> spans = new ArrayList<>();
        int index = 0;
        while (index < input.length()) {
            int next = skipAtom(input, index);
            char current = input.charAt(index);
            if ((current == '"' || current == '\'' || current == '`') && !escaped(input, index)) {
                spans.add(new int[] {index, Math.min(next, input.length())});
            }
            if (next <= index) {
                break;
            }
            index = next;
        }
        return spans;
    }

    private static boolean inside(List<int[]> spans, int index) {
        for (int[] span : spans) {
            if (index >= span[0] && index < span[1]) {
                return true;
            }
        }
        return false;
    }

    private static boolean escaped(String input, int index) {
        int slashes = 0;
        for (int cursor = index - 1; cursor >= 0 && input.charAt(cursor) == '\\'; cursor--) {
            slashes++;
        }
        return slashes % 2 == 1;
    }
}
