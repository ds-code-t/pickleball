package tools.dscode.common.mappings;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QuoteParserChecks {
    private static final int LARGE_QUOTED_CHARS = 64 * 1024;
    private static final Pattern LEGACY_SINGLE =
            Pattern.compile("(?<!\\\\)(['\"`])((?:\\\\.|(?!\\1).)*?)(?<!\\\\)(?:\\\\\\\\)*\\1");
    private static final Pattern LEGACY_TRIPLE =
            Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*(''')((?:\\\\.|(?!\\1).)*?)(?<!\\\\)(?:\\\\\\\\)*\\1");

    @Test
    void scannerMatchesLegacyRegexOnSmallQuoteCases() {
        for (String input : smallCases()) {
            assertEquivalentToLegacy(input);
        }
    }

    @Test
    void largeQuotedValuesMaskWithoutOverflowing() {
        String huge = "x".repeat(LARGE_QUOTED_CHARS);
        assertQuotedSpan(huge, "'");
        assertQuotedSpan(huge, "\"");
        assertQuotedSpan(huge, "`");

        String json = "{\"payload\":\"" + huge + "\"}";
        QuoteParser parsed = new QuoteParser(json);
        assertEquals(1, parsed.size());
        assertEquals(huge, parsed.values().iterator().next());
        assertEquals(json, parsed.restore());
    }

    @Test
    void largeQuotedTemplatesResolveThroughBookendMapping() {
        String huge = "y".repeat(LARGE_QUOTED_CHARS);
        TestProcessor processor = new TestProcessor(new NodeMap(MapConfigurations.MapType.RUN_MAP));
        processor.put("payload", huge);

        String quotedTemplate = "'" + huge + "'";
        assertEquals(quotedTemplate, processor.resolveWholeText(quotedTemplate));
        assertEquals(
                "start \"" + huge + "\" end",
                processor.resolveWholeText("start \"<payload>\" end")
        );
        assertEquals(
                "'mixed \"" + huge + "\" quotes'",
                processor.resolveWholeText("'mixed \"<payload>\" quotes'")
        );
    }

    private static void assertQuotedSpan(String inner, String quote) {
        String input = quote + inner + quote;
        QuoteParser parsed = new QuoteParser(input);
        assertEquals(1, parsed.size());
        Map.Entry<String, String> entry = parsed.entrySet().iterator().next();
        assertEquals(quote, parsed.delimiterOf(entry.getKey()));
        assertEquals(inner, entry.getValue());
        assertEquals(input, parsed.restore());
        assertTrue(parsed.masked().indexOf(quote) < 0);
    }

    private static void assertEquivalentToLegacy(String input) {
        LegacyParse legacy = legacyParse(input);
        QuoteParser parsed = new QuoteParser(input);
        assertEquals(legacy.masked, parsed.masked(), "masked for " + visible(input));
        assertEquals(legacy.inners.size(), parsed.size(), "entry count for " + visible(input));
        List<Map.Entry<String, String>> actual = new ArrayList<>(parsed.entrySet());
        assertEquals(legacy.inners.size(), actual.size());
        for (int i = 0; i < legacy.inners.size(); i++) {
            Map.Entry<String, String> entry = actual.get(i);
            assertEquals(legacy.delimiters.get(i), parsed.delimiterOf(entry.getKey()),
                    "delimiter " + i + " for " + visible(input));
            assertEquals(legacy.inners.get(i), entry.getValue(),
                    "inner " + i + " for " + visible(input));
        }
        assertEquals(legacyRestore(legacy), parsed.restore(), "restore for " + visible(input));
    }

    private static List<String> smallCases() {
        return List.of(
                "",
                "plain",
                "''",
                "\"\"",
                "``",
                "'a'",
                "\"a\"",
                "`a`",
                "Hello, \"a,\\\"b\" and 'c:\\'drive' and \"x\\`y`\". and `s:\\'drive`  and `w:\\\"drive`",
                "Hello \"'\" \"`\" end",
                "'hello'",
                "\"hello\"",
                "`hello`",
                "'''hello'''",
                "\\'''hello'''",
                "\\\\'''hello'''",
                "'a\"b'",
                "\"a'b\"",
                "`a'b\"c`",
                "'a\\\\'",
                "\"foo\\\\\"",
                "\"foo\\\"bar\"",
                "\"a\\\\b\"",
                "'it\\'s'",
                "mix 'one' and \"two\" and `three`",
                "unclosed 'abc",
                "unclosed \"abc",
                "a 'b' c 'd'",
                "\"outer 'inner' still\"",
                "'outer \"inner\" still'",
                "'''",
                "''''",
                "'''''",
                "''''''",
                "x'''y'''z",
                "'<payload>'",
                "\"<a><b>\"",
                "line\n'ok'",
                "'a\nb'",
                "'a\\\\nb'",
                "`\\``",
                "\"\\n\"",
                "prefix\\'not'",
                "prefix\\\\'yes'",
                "empty '' middle",
                "'",
                "\"",
                "`",
                "nested-looking \"a 'b' c\"",
                "triple-in-double \"'''\"",
                "\\'''foo'''",
                "\\\\'''foo'''",
                "\\\\\\\\\\'''foo'''",
                "start '<payload>' end",
                "\"\"\"",
                "`unclosed",
                "'empty-ish' and more",
                "\"a\\nb\"",
                "'\u0085not-a-span'",
                "ok\r\n'yes'"
        );
    }

    private static LegacyParse legacyParse(String input) {
        AtomicInteger n = new AtomicInteger(1);
        LegacyParse pass1 = applyLegacyPass(input, LEGACY_SINGLE, n);
        LegacyParse pass2 = applyLegacyPass(pass1.masked, LEGACY_TRIPLE, n);
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(pass1.captured);
        merged.putAll(pass2.captured);
        LinkedHashMap<String, String> delims = new LinkedHashMap<>(pass1.delimiterOf);
        delims.putAll(pass2.delimiterOf);
        List<String> inners = new ArrayList<>();
        List<String> delimiters = new ArrayList<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            inners.add(entry.getValue());
            delimiters.add(delims.get(entry.getKey()));
        }
        return new LegacyParse(pass2.masked, merged, delims, inners, delimiters);
    }

    private static LegacyParse applyLegacyPass(String in, Pattern pattern, AtomicInteger n) {
        Matcher matcher = pattern.matcher(in);
        StringBuffer out = new StringBuffer();
        LinkedHashMap<String, String> captured = new LinkedHashMap<>();
        LinkedHashMap<String, String> delims = new LinkedHashMap<>();
        while (matcher.find()) {
            String opening = matcher.group(1);
            String inner = matcher.group(2).replace("\\" + opening.charAt(0), String.valueOf(opening.charAt(0)));
            String placeholder = QuoteParser.MASK_BOUNDARY
                    + String.valueOf('\u2404').repeat(n.getAndIncrement())
                    + QuoteParser.MASK_BOUNDARY;
            captured.put(placeholder, inner);
            delims.put(placeholder, opening);
            matcher.appendReplacement(out, Matcher.quoteReplacement(placeholder));
        }
        matcher.appendTail(out);
        return new LegacyParse(out.toString(), captured, delims, List.copyOf(captured.values()), List.copyOf(delims.values()));
    }

    private static String legacyRestore(LegacyParse legacy) {
        String out = legacy.masked;
        List<String> keys = new ArrayList<>(legacy.captured.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String key : keys) {
            String delim = legacy.delimiterOf.get(key);
            String escaped = legacy.captured.get(key).replace(
                    String.valueOf(delim.charAt(0)),
                    "\\" + delim.charAt(0));
            out = out.replace(key, delim + escaped + delim);
        }
        return out;
    }

    private static String visible(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private record LegacyParse(
            String masked,
            LinkedHashMap<String, String> captured,
            LinkedHashMap<String, String> delimiterOf,
            List<String> inners,
            List<String> delimiters
    ) {
    }

    private static final class TestProcessor extends MappingProcessor {
        private TestProcessor(NodeMap map) {
            super(map);
        }
    }
}
