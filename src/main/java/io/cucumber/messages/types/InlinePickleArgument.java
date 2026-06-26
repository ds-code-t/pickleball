package io.cucumber.messages.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class InlinePickleArgument {

    private static final Pattern INLINE_ARGUMENT_MARKER = Pattern.compile("\\b([A-Z]+):::");

    private InlinePickleArgument() {
    }

    public static Extracted extract(String text) {
        if (text == null) {
            return null;
        }

        String strippedText = text.stripTrailing();
        if (!strippedText.endsWith("|")) {
            return null;
        }

        var matcher = INLINE_ARGUMENT_MARKER.matcher(strippedText);
        String type = null;
        int markerStart = -1;
        int markerEnd = -1;
        while (matcher.find()) {
            type = matcher.group(1);
            markerStart = matcher.start();
            markerEnd = matcher.end();
        }

        if (type == null) {
            return null;
        }

        String argumentText = strippedText.substring(markerEnd).strip();
        if (argumentText.isEmpty()) {
            return null;
        }

        String stepText = strippedText.substring(0, markerStart).stripTrailing();
        return new Extracted(stepText, type, argumentText);
    }

    public record Extracted(String stepText, String argumentType, String argumentText) {
    }

    public static List<?> asListOrMaps(String inlineArgumentText) {
        if (inlineArgumentText == null || inlineArgumentText.isBlank()) {
            return null;
        }

        try {
            ParsedInlineDataTable parsed = parseInlineDataTable(inlineArgumentText);
            List<List<String>> rows = parsed.rows();
            if (!parsed.mapped()) {
                return rows.isEmpty() ? List.of() : rows.getFirst();
            }

            if (rows.isEmpty()) {
                return List.of();
            }

            List<String> headers = rows.getFirst();
            List<Map<String, String>> maps = new ArrayList<>();
            for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
                List<String> values = rows.get(rowIndex);
                if (values.size() != headers.size()) {
                    throw new IllegalArgumentException("Mapped inline argument row " + rowIndex
                            + " has " + values.size() + " cells but header has " + headers.size() + " cells.");
                }

                Map<String, String> map = new LinkedHashMap<>();
                for (int cellIndex = 0; cellIndex < headers.size(); cellIndex++) {
                    map.put(headers.get(cellIndex), values.get(cellIndex));
                }
                maps.add(map);
            }
            return maps;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unable to convert inline argument text to a list or list of maps: "
                    + inlineArgumentText, e);
        }
    }

    public static List<List<String>> inlineDataTableRows(String inlineText) {
        return parseInlineDataTable(inlineText).rows();
    }

    private static ParsedInlineDataTable parseInlineDataTable(String inlineText) {
        List<String> segments = splitInlineDataTableSegments(inlineText == null ? "" : inlineText);
        if (segments.isEmpty()) {
            return new ParsedInlineDataTable(false, List.of(List.of("")));
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
            throw new IllegalArgumentException("Inline argument map syntax must use \"key\":value for every column.");
        }

        if (!hasMapEntry) {
            return new ParsedInlineDataTable(false, List.of(segments.stream()
                    .map(InlinePickleArgument::decodeInlineDataTableLiteral)
                    .toList()));
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

        return new ParsedInlineDataTable(true, rows);
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
                    throw new IllegalArgumentException("Inline argument has an unmatched ']'.");
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
            throw new IllegalArgumentException("Inline argument has an unclosed quoted substring.");
        }
        if (bracketDepth != 0) {
            throw new IllegalArgumentException("Inline argument has an unclosed array.");
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
                    .map(InlinePickleArgument::decodeInlineDataTableLiteral)
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
            throw new IllegalArgumentException("Inline argument array value is not closed.");
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
                    throw new IllegalArgumentException("Inline argument array has an unmatched ']'.");
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
            throw new IllegalArgumentException("Inline argument array has an unclosed quoted substring.");
        }
        if (bracketDepth != 0) {
            throw new IllegalArgumentException("Inline argument array has an unclosed nested array.");
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
            throw new IllegalArgumentException("Inline argument literal has an unclosed quoted substring.");
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

        throw new IllegalArgumentException("Inline argument has an unclosed quoted string.");
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
            throw new IllegalArgumentException("Inline argument has an incomplete unicode escape.");
        }
        try {
            return (char) Integer.parseInt(text.substring(start, end), 16);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Inline argument has an invalid unicode escape.", e);
        }
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private record ParsedInlineDataTable(boolean mapped, List<List<String>> rows) {
    }

    private record InlineDataTableColumn(String key, List<String> values) {
    }

    private record ParsedString(String value, int endIndex) {
    }
}
