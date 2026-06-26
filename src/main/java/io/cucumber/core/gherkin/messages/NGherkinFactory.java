package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.messages.types.PickleTable;
import io.cucumber.messages.types.PickleTableCell;
import io.cucumber.messages.types.PickleTableRow;
import io.cucumber.plugin.event.Location;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static tools.dscode.common.util.Reflect.getProperty;

public class NGherkinFactory {

    private static final URI DEFAULT_URI = URI.create("memory:/single.feature");

    public static GherkinMessagesStep createGherkinMessagesStep(PickleStep pickleStep,
                                                                GherkinDialect dialect,
                                                                String previousGwtKeyWord,
                                                                Location location,
                                                                String keyword) {
        return new GherkinMessagesStep(pickleStep, dialect, previousGwtKeyWord, location, keyword);
    }


    // text to Step


    public static GherkinMessagesPickle createGherkinMessagesPickle(String keyword, String stepText, String argument) {
        StringBuilder featureSrc = new StringBuilder()
                .append("Feature: Virtual Feature\n")
                .append("  Scenario: Virtual Scenario\n")
                .append("    ").append(keyword).append(stepText).append("\n");
// --- decide how to render the argument ---
        if (argument != null && !argument.isBlank()) {
            // Heuristic: every non-blank line starts and ends with a pipe -> treat as DataTable
            var lines = argument.lines()
                    .map(String::stripTrailing)
                    .filter(l -> !l.isBlank())
                    .toList();
            boolean looksLikeTable = !lines.isEmpty()
                    && lines.stream().allMatch(l -> {
                String t = l.strip();
                return t.startsWith("|") && t.endsWith("|");
            });
            if (looksLikeTable) {
                // Emit a real Gherkin DataTable (no docstring fence)
                for (String l : lines) {
                    featureSrc.append("      ").append(l.strip()).append("\n");
                }
            } else {
                featureSrc.append(argument.replace("\r\n", "\n").replace("\r", "\n")).append("\n");

                // Emit as DocString
//                featureSrc.append("      \"\"\"\n")
//                        .append(argument.replace("\r\n", "\n").replace("\r", "\n")).append("\n")
//                        .append("      \"\"\"\n");
            }
        }

        byte[] bytes = featureSrc.toString().getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            GherkinMessagesFeatureParser parser = new GherkinMessagesFeatureParser();
            Optional<Feature> parsed =
                    parser.parse(DEFAULT_URI, in, UUID::randomUUID);

            GherkinMessagesFeature feature =
                    (GherkinMessagesFeature) parsed.orElseThrow(
                            () -> new IllegalStateException("No feature parsed from generated source"));
            return (GherkinMessagesPickle) feature.getPickles()
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No pickles found"));
        } catch (Exception e) {
            throw new IllegalStateException("Error creating pickle", e);
        }
    }


    // step to text


    public static String getGherkinArgumentText(io.cucumber.core.gherkin.Step gherkinMessagesStep) {
        PickleStep pickleStep = (PickleStep) getProperty(gherkinMessagesStep, "pickleStep");
        String inlineDataTableArgument = getInlineDataTableArgumentText(pickleStep);
        if (inlineDataTableArgument != null) {
            return inlineDataTableArgument;
        }

        PickleStepArgument pickleStepArgument = pickleStep.getArgument().orElse(null);
        return argumentToGherkinText(pickleStepArgument);
    }

    static String getInlineDataTableArgumentText(PickleStep pickleStep) {
        if (pickleStep == null || !pickleStep.hasInlineArgument() || !"DT".equals(pickleStep.getInlineArgumentType())) {
            return null;
        }

        return inlineDataTableArgumentToGherkinText(pickleStep.getInlineArgumentText());
    }

    static String inlineDataTableArgumentToGherkinText(String inlineArgumentText) {
        PickleTable table = new PickleTable(inlineDataTableRows(inlineArgumentText).stream()
                .map(row -> new PickleTableRow(row.stream()
                        .map(PickleTableCell::new)
                        .toList()))
                .toList());
        return argumentToGherkinText(PickleStepArgument.of(table));
    }

    static List<List<String>> inlineDataTableRows(String inlineText) {
        List<String> segments = splitInlineDataTableSegments(inlineText == null ? "" : inlineText);
        if (segments.isEmpty()) {
            return List.of(List.of(""));
        }

        List<InlineDataTableColumn> columns = new ArrayList<>();
        boolean hasMapEntry = false;
        boolean hasNonMapEntry = false;
        for (String segment : segments) {
            InlineDataTableColumn column = parseInlineDataTableColumn(segment);
            if (column == null) {
                hasNonMapEntry = true;
            } else {
                hasMapEntry = true;
                columns.add(column);
            }
        }

        if (hasMapEntry && hasNonMapEntry) {
            throw new IllegalArgumentException("Inline DataTable map syntax must use \"key\":value for every column.");
        }

        if (!hasMapEntry) {
            return List.of(segments.stream()
                    .map(NGherkinFactory::decodeInlineDataTableLiteral)
                    .toList());
        }

        List<List<String>> rows = new ArrayList<>();
        rows.add(columns.stream().map(InlineDataTableColumn::key).toList());

        int valueRowCount = columns.stream()
                .mapToInt(column -> column.values().size())
                .max()
                .orElse(0);
        for (int rowIndex = 0; rowIndex < valueRowCount; rowIndex++) {
            int finalRowIndex = rowIndex;
            rows.add(columns.stream()
                    .map(column -> finalRowIndex < column.values().size() ? column.values().get(finalRowIndex) : "")
                    .toList());
        }

        return rows;
    }

    private static List<String> splitInlineDataTableSegments(String inlineText) {
        String text = inlineText.strip();
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        boolean escaping = false;
        boolean quoted = false;
        int bracketDepth = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaping) {
                segment.append(ch);
                escaping = false;
            } else if (ch == '\\') {
                segment.append(ch);
                escaping = true;
            } else if (ch == '"') {
                segment.append(ch);
                quoted = !quoted;
            } else if (!quoted && ch == '[') {
                segment.append(ch);
                bracketDepth++;
            } else if (!quoted && ch == ']') {
                if (bracketDepth == 0) {
                    throw new IllegalArgumentException("Inline DataTable has an unmatched ']'.");
                }
                segment.append(ch);
                bracketDepth--;
            } else if (!quoted && bracketDepth == 0 && ch == '|') {
                segments.add(segment.toString().strip());
                segment.setLength(0);
            } else {
                segment.append(ch);
            }
        }

        if (quoted) {
            throw new IllegalArgumentException("Inline DataTable has an unclosed quoted substring.");
        }
        if (bracketDepth != 0) {
            throw new IllegalArgumentException("Inline DataTable has an unclosed array.");
        }

        segments.add(segment.toString().strip());
        removeOuterDelimiterSegments(segments);
        return segments;
    }

    private static void removeOuterDelimiterSegments(List<String> segments) {
        if (!segments.isEmpty() && segments.getFirst().isEmpty()) {
            segments.removeFirst();
        }
        if (!segments.isEmpty() && segments.getLast().isEmpty()) {
            segments.removeLast();
        }
    }

    private static InlineDataTableColumn parseInlineDataTableColumn(String segment) {
        String trimmed = segment.strip();
        if (!trimmed.startsWith("\"")) {
            return null;
        }

        ParsedString key = parseJsonStyleString(trimmed, 0);
        int colonIndex = skipWhitespace(trimmed, key.endIndex());
        if (colonIndex >= trimmed.length() || trimmed.charAt(colonIndex) != ':') {
            return null;
        }

        String rawValue = trimmed.substring(colonIndex + 1).strip();
        return new InlineDataTableColumn(key.value(), parseInlineDataTableColumnValues(rawValue));
    }

    private static List<String> parseInlineDataTableColumnValues(String rawValue) {
        if (isWrappedByTopLevelArray(rawValue)) {
            String value = rawValue.strip();
            String content = value.substring(1, value.length() - 1);
            if (content.isBlank()) {
                return List.of();
            }
            return splitArrayValues(content).stream()
                    .map(NGherkinFactory::decodeInlineDataTableLiteral)
                    .toList();
        }

        return List.of(decodeInlineDataTableLiteral(rawValue));
    }

    private static boolean isWrappedByTopLevelArray(String rawValue) {
        String value = rawValue.strip();
        if (!value.startsWith("[") || !value.endsWith("]")) {
            return false;
        }

        boolean escaping = false;
        boolean quoted = false;
        int bracketDepth = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaping) {
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && ch == '[') {
                bracketDepth++;
            } else if (!quoted && ch == ']') {
                bracketDepth--;
                if (bracketDepth == 0 && i != value.length() - 1) {
                    return false;
                }
            }
        }

        if (quoted || bracketDepth != 0) {
            throw new IllegalArgumentException("Inline DataTable array value is not closed.");
        }
        return true;
    }

    private static List<String> splitArrayValues(String content) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        boolean quoted = false;
        int bracketDepth = 0;

        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (escaping) {
                value.append(ch);
                escaping = false;
            } else if (ch == '\\') {
                value.append(ch);
                escaping = true;
            } else if (ch == '"') {
                value.append(ch);
                quoted = !quoted;
            } else if (!quoted && ch == '[') {
                value.append(ch);
                bracketDepth++;
            } else if (!quoted && ch == ']') {
                if (bracketDepth == 0) {
                    throw new IllegalArgumentException("Inline DataTable array has an unmatched ']'.");
                }
                value.append(ch);
                bracketDepth--;
            } else if (!quoted && bracketDepth == 0 && ch == ',') {
                values.add(value.toString().strip());
                value.setLength(0);
            } else {
                value.append(ch);
            }
        }

        if (quoted) {
            throw new IllegalArgumentException("Inline DataTable array has an unclosed quoted substring.");
        }
        if (bracketDepth != 0) {
            throw new IllegalArgumentException("Inline DataTable array has an unclosed nested array.");
        }

        values.add(value.toString().strip());
        return values;
    }

    private static String decodeInlineDataTableLiteral(String text) {
        String value = text.strip();
        StringBuilder decoded = new StringBuilder();
        boolean escaping = false;
        boolean quoted = false;

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaping) {
                decoded.append(decodeEscapedCharacter(ch, value, i));
                if (ch == 'u') {
                    i += 4;
                }
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                quoted = !quoted;
            } else {
                decoded.append(ch);
            }
        }

        if (escaping) {
            decoded.append('\\');
        }
        if (quoted) {
            throw new IllegalArgumentException("Inline DataTable literal has an unclosed quoted substring.");
        }

        return decoded.toString();
    }

    private static ParsedString parseJsonStyleString(String text, int startIndex) {
        if (startIndex >= text.length() || text.charAt(startIndex) != '"') {
            throw new IllegalArgumentException("Expected quoted string.");
        }

        StringBuilder decoded = new StringBuilder();
        boolean escaping = false;
        for (int i = startIndex + 1; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaping) {
                decoded.append(decodeEscapedCharacter(ch, text, i));
                if (ch == 'u') {
                    i += 4;
                }
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                return new ParsedString(decoded.toString(), i + 1);
            } else {
                decoded.append(ch);
            }
        }

        throw new IllegalArgumentException("Inline DataTable has an unclosed quoted string.");
    }

    private static char decodeEscapedCharacter(char escaped, String text, int escapedIndex) {
        return switch (escaped) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> decodeUnicodeEscape(text, escapedIndex);
            default -> escaped;
        };
    }

    private static char decodeUnicodeEscape(String text, int escapedIndex) {
        int start = escapedIndex + 1;
        int end = start + 4;
        if (end > text.length()) {
            throw new IllegalArgumentException("Inline DataTable has an incomplete unicode escape.");
        }
        try {
            return (char) Integer.parseInt(text.substring(start, end), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Inline DataTable has an invalid unicode escape.", e);
        }
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private record InlineDataTableColumn(String key, List<String> values) {
    }

    private record ParsedString(String value, int endIndex) {
    }

    public static String argumentToGherkinText(io.cucumber.messages.types.PickleStepArgument arg) {
        if (arg == null) return "";

        var dsOpt = arg.getDocString();
        if (dsOpt.isPresent()) {
            var ds = dsOpt.get();
            String content = ds.getContent().replace("\r\n", "\n").replace("\r", "\n");
            boolean hasTriple = content.lines().anyMatch(l -> l.equals("\"\"\""));
            boolean hasTicks = content.lines().anyMatch(l -> l.equals("```"));
            String fence = !hasTriple ? "\"\"\"" : (!hasTicks ? "```" : null);
            if (fence == null) {
                throw new IllegalArgumentException("DocString contains both \"\"\" and ``` lines; cannot render safely.");
            }
            String media = ds.getMediaType().filter(s -> !s.isBlank()).map(s -> " " + s.trim()).orElse("");
            String body = content.lines().map(l -> "      " + l).collect(java.util.stream.Collectors.joining("\n"));
            return "      " + fence + media + "\n" + body + "\n      " + fence;
        }

        var tableOpt = arg.getDataTable();
        if (tableOpt.isPresent()) {
            var rows = tableOpt.get().getRows();
            if (rows.isEmpty()) throw new IllegalArgumentException("DataTable has zero rows; no legal Gherkin form.");
            return rows.stream()
                    .map(r -> "      | " + r.getCells().stream()
                            .map(c -> {
                                String v = c.getValue();
                                return v == null ? "" : v
                                        .replace("\r\n", "\n")
                                        .replace("\r", "\n")
                                        .replace("\\", "\\\\")
                                        .replace("|", "\\|")
                                        .replace("\n", "\\n");
                            })
                            .collect(java.util.stream.Collectors.joining(" | ")) + " |")
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        return "";
    }


}
