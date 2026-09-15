package tools.dscode.workbench.player;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless Gherkin text-editor operations for the live scenario buffer.
 *
 * <p>Tab at the indent prefix inserts Pickleball leading-colon nesting.
 * Keyword completion is editor UX only: it does not parse or execute Gherkin.</p>
 */
public final class GherkinTextEditing {
    public static final List<String> KEYWORDS = List.of(
            "Feature",
            "Rule",
            "Background",
            "Scenario",
            "Scenario Outline",
            "Examples",
            "Given",
            "When",
            "Then",
            "And",
            "But",
            "*",
            "IF",
            "ELSE",
            "ELSE-IF"
    );

    public record LineCaret(String line, int caretColumn) {
        public LineCaret {
            line = line == null ? "" : line;
            caretColumn = Math.max(0, Math.min(caretColumn, line.length()));
        }
    }

    private GherkinTextEditing() {
    }

    /**
     * True when the caret is at column 0 or only leading colons/spaces exist
     * before the caret on the line.
     */
    public static boolean inIndentPrefix(String line, int caretColumn) {
        String value = line == null ? "" : line;
        int column = clamp(caretColumn, value.length());
        for (int i = 0; i < column; i++) {
            char ch = value.charAt(i);
            if (ch != ':' && !isIndentSpace(ch)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Inserts one {@code :} after any existing leading colons. Does not insert
     * a tab character or four spaces.
     */
    public static LineCaret indent(String line, int caretColumn) {
        String value = line == null ? "" : line;
        int column = clamp(caretColumn, value.length());
        if (!inIndentPrefix(value, column)) {
            return insertSpace(value, column);
        }
        int insertAt = lastLeadingColonIndex(value) + 1;
        String next = value.substring(0, insertAt) + ":" + value.substring(insertAt);
        int caret = column <= insertAt ? insertAt + 1 : column + 1;
        return new LineCaret(next, caret);
    }

    /**
     * Removes one leading {@code :} if present; otherwise a no-op.
     */
    public static LineCaret outdent(String line, int caretColumn) {
        String value = line == null ? "" : line;
        int column = clamp(caretColumn, value.length());
        if (!inIndentPrefix(value, column)) {
            return new LineCaret(value, column);
        }
        int colonAt = lastLeadingColonIndex(value);
        if (colonAt < 0) {
            return new LineCaret(value, column);
        }
        String next = value.substring(0, colonAt) + value.substring(colonAt + 1);
        int caret = column <= colonAt ? column : column - 1;
        return new LineCaret(next, caret);
    }

    public static LineCaret insertSpace(String line, int caretColumn) {
        String value = line == null ? "" : line;
        int column = clamp(caretColumn, value.length());
        String next = value.substring(0, column) + " " + value.substring(column);
        return new LineCaret(next, column + 1);
    }

    /**
     * Tab: accept an open completion, else colon-indent at the prefix, else a
     * single space. Mid-line Tab never prepends {@code :}.
     */
    public static LineCaret tab(String line, int caretColumn, boolean completionOpen, String selectedKeyword) {
        if (completionOpen && selectedKeyword != null && !selectedKeyword.isBlank()) {
            return acceptCompletion(line, caretColumn, selectedKeyword);
        }
        if (inIndentPrefix(line, caretColumn)) {
            return indent(line, caretColumn);
        }
        return insertSpace(line, caretColumn);
    }

    public static List<String> completions(String line, int caretColumn) {
        Token token = firstToken(line, caretColumn);
        if (token == null || token.text.isEmpty()) {
            return List.of();
        }
        String typed = token.text;
        List<String> matches = new ArrayList<>();
        for (String keyword : KEYWORDS) {
            if (typed.length() <= keyword.length()
                    && keyword.regionMatches(true, 0, typed, 0, typed.length())) {
                matches.add(keyword);
            }
        }
        return List.copyOf(matches);
    }

    public static LineCaret acceptCompletion(String line, int caretColumn, String keyword) {
        String value = line == null ? "" : line;
        int column = clamp(caretColumn, value.length());
        Token token = firstToken(value, column);
        if (token == null || keyword == null || keyword.isBlank()) {
            return new LineCaret(value, column);
        }
        int end = token.end;
        while (end < value.length() && isTokenChar(value.charAt(end))) {
            end++;
        }
        String tail = value.substring(end);
        if (tail.startsWith(" ")) {
            tail = tail.substring(1);
        }
        String next = value.substring(0, token.start) + keyword + " " + tail;
        int caret = token.start + keyword.length() + 1;
        return new LineCaret(next, caret);
    }

    private static Token firstToken(String line, int caretColumn) {
        String value = line == null ? "" : line;
        int column = clamp(caretColumn, value.length());
        int prefix = indentPrefixLength(value);
        if (column < prefix) {
            return null;
        }
        String typed = value.substring(prefix, column);
        if (typed.isEmpty() || typed.charAt(0) == '#' || typed.charAt(0) == '|') {
            return null;
        }
        if (typed.indexOf('\t') >= 0) {
            return null;
        }
        if (!prefixesAnyKeyword(typed)) {
            return null;
        }
        return new Token(prefix, column, typed);
    }

    private static boolean prefixesAnyKeyword(String typed) {
        for (String keyword : KEYWORDS) {
            if (keyword.regionMatches(true, 0, typed, 0, typed.length())) {
                return true;
            }
        }
        return false;
    }

    private static int indentPrefixLength(String line) {
        int i = 0;
        while (i < line.length()) {
            char ch = line.charAt(i);
            if (ch != ':' && !isIndentSpace(ch)) {
                break;
            }
            i++;
        }
        return i;
    }

    private static int lastLeadingColonIndex(String line) {
        int last = -1;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == ':') {
                last = i;
            } else if (!isIndentSpace(ch)) {
                break;
            }
        }
        return last;
    }

    private static boolean isIndentSpace(char ch) {
        return ch == ' ' || ch == '\t';
    }

    private static boolean isTokenChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '-' || ch == '*';
    }

    private static int clamp(int column, int length) {
        if (column < 0) return 0;
        return Math.min(column, length);
    }

    private record Token(int start, int end, String text) { }
}
