package tools.dscode.common.domoperations;

import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class KeyParser {

    private KeyParser() {}

    // Flip this on/off as needed
    private static final boolean DEBUG = true;

    private static void dbg(String fmt, Object... args) {
        if (DEBUG) System.out.println("[KeyParser] " + String.format(fmt, args));
    }

    private static String show(CharSequence cs) {
        if (cs == null) return "null";
        if (cs instanceof Keys k) return "Keys." + k.name() + "(U+" + String.format("%04X", (int) k.charAt(0)) + ")";
        String s = cs.toString();
        if (s.length() == 1) return "'" + s + "'(U+" + String.format("%04X", (int) s.charAt(0)) + ")";
        return "\"" + s + "\"";
    }

    private static String showList(List<CharSequence> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(show(list.get(i)));
        }
        return sb.append("]").toString();
    }

    /** Sends to the active element / browser context. */
    public static void sendComplexKeys(WebDriver driver, String input) {
        dbg("sendComplexKeys(driver) input=%s", quote(input));
        Actions actions = new Actions(driver);
        parseInto(actions, input);
        dbg("actions.perform()");
        actions.perform();
        dbg("done");
    }

    /** Sends to a specific element (focus/click is NOT forced). */
    public static void sendComplexKeys(WebDriver driver, WebElement element, String input) {
        if(element == null)
        {
            sendComplexKeys(driver, input);
            return;
        }
        dbg("sendComplexKeys(driver, element) input=%s", quote(input));
        Actions actions = new Actions(driver);
        dbg("targeting element via actions.sendKeys(element, \"\")");
        actions.sendKeys(element, "");
        parseInto(actions, input);
        dbg("actions.perform()");
        actions.perform();
        dbg("done");
    }

    private static void parseInto(Actions actions, String input) {
        Deque<CharSequence> hold = new ArrayDeque<>();
        int[] pos = {0};
        dbg("parseInto start len=%d", input.length());
        parseSequence(actions, input, pos, hold, false);
        dbg("parseInto end pos=%d holdDepth=%d", pos[0], hold.size());
    }

    private static void parseSequence(Actions actions, String input, int[] pos, Deque<CharSequence> hold, boolean sub) {
        int len = input.length();
        dbg("parseSequence(sub=%s) enter pos=%d next=%s", sub, pos[0], peek(input, pos));
        while (pos[0] < len && (!sub || input.charAt(pos[0]) != ']')) {
            skipSpaces(input, pos);
            if (pos[0] >= len || (sub && input.charAt(pos[0]) == ']')) break;
            dbg("parseSequence loop pos=%d next=%s", pos[0], peek(input, pos));
            parseTerm(actions, input, pos, hold);
        }
        dbg("parseSequence(sub=%s) exit pos=%d next=%s", sub, pos[0], peek(input, pos));
    }

    private static void parseTerm(Actions actions, String input, int[] pos, Deque<CharSequence> hold) {
        dbg("parseTerm enter pos=%d next=%s", pos[0], peek(input, pos));
        skipSpaces(input, pos);

        int chordStart = pos[0];
        List<CharSequence> chord = parseChord(input, pos);
        dbg("parsed chord from [%d..%d): %s", chordStart, pos[0], showList(chord));

        skipSpaces(input, pos);

        if (pos[0] < input.length() && input.charAt(pos[0]) == '[') {
            dbg("hold block begins at pos=%d", pos[0]);
            pos[0]++; // consume '['
            skipSpaces(input, pos);

            int heldCount = 0;
            for (CharSequence k : chord) {
                dbg("keyDown %s", show(k));
                actions.keyDown(k);
                hold.push(k);
                heldCount++;
            }
            dbg("holdDepth=%d (after keyDowns)", hold.size());

            parseSequence(actions, input, pos, hold, true);

            skipSpaces(input, pos);
            if (pos[0] >= input.length() || input.charAt(pos[0]) != ']') {
                throw new IllegalArgumentException("Missing ] at position " + pos[0]);
            }
            dbg("hold block ends at pos=%d (consuming ])", pos[0]);
            pos[0]++; // consume ']'

            for (int i = 0; i < heldCount; i++) {
                CharSequence up = hold.pop();
                dbg("keyUp %s", show(up));
                actions.keyUp(up);
            }
            dbg("holdDepth=%d (after keyUps)", hold.size());
            return;
        }

        if (!chord.isEmpty()) {
            boolean mods = containsModifier(chord);
            dbg("send term (mods=%s) %s", mods, showList(chord));
            if (mods) {
                String s = Keys.chord(chord.toArray(CharSequence[]::new));
                dbg("sendKeys(Keys.chord(...)) => %s (note: includes Keys.NULL)", printable(s));
                actions.sendKeys(s);
            } else {
                dbg("sendKeys(raw...)");
                actions.sendKeys(chord.toArray(CharSequence[]::new));
            }
        } else {
            dbg("empty term (no-op)");
        }
    }

    private static List<CharSequence> parseChord(String input, int[] pos) {
        int len = input.length();
        List<CharSequence> out = new ArrayList<>();
        boolean first = true;

        while (true) {
            skipSpaces(input, pos);
            if (pos[0] >= len) break;

            char c = input.charAt(pos[0]);
            if (c == '[' || c == ']') break;

            int tokStart = pos[0];
            String token = parseToken(input, pos);
            dbg("token [%d..%d): %s", tokStart, pos[0], quote(token));

            if (token.isEmpty()) {
                if (first) break;
                throw new IllegalArgumentException("Empty token at " + pos[0]);
            }

            List<CharSequence> resolved = resolveKey(token);
            dbg("resolved %s -> %s", quote(token), showList(resolved));
            out.addAll(resolved);
            first = false;

            skipSpaces(input, pos);
            if (pos[0] < len && input.charAt(pos[0]) == '+') {
                dbg("consume '+' at pos=%d", pos[0]);
                pos[0]++;
            } else {
                break;
            }
        }
        return out;
    }

    private static String parseToken(String input, int[] pos) {
        int len = input.length();
        StringBuilder sb = new StringBuilder();
        while (pos[0] < len) {
            char c = input.charAt(pos[0]);
            if (c == ' ' || c == '+' || c == '[' || c == ']') break;
            sb.append(c);
            pos[0]++;
        }
        return sb.toString();
    }

    private static void skipSpaces(String input, int[] pos) {
        int len = input.length();
        while (pos[0] < len && input.charAt(pos[0]) == ' ') pos[0]++;
    }

    private static List<CharSequence> resolveKey(String token) {
        try {
            return List.of(Keys.valueOf(token));
        } catch (IllegalArgumentException ignored) {
            if (token.length() == 1) return List.of(token);
            throw new IllegalArgumentException("Unknown key: " + token);
        }
    }

    private static boolean containsModifier(List<CharSequence> chord) {
        for (CharSequence cs : chord) {
            if (cs instanceof Keys k) {
                if (k == Keys.SHIFT || k == Keys.CONTROL || k == Keys.ALT || k == Keys.META
                        || k == Keys.LEFT_SHIFT || k == Keys.LEFT_CONTROL || k == Keys.LEFT_ALT || k == Keys.COMMAND
                        || k == Keys.RIGHT_SHIFT || k == Keys.RIGHT_CONTROL || k == Keys.RIGHT_ALT || k == Keys.RIGHT_COMMAND) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- tiny helpers for readable debug output ---

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String peek(String input, int[] pos) {
        if (pos[0] >= input.length()) return "<EOF>";
        char c = input.charAt(pos[0]);
        return "'" + c + "' (pos " + pos[0] + ")";
    }

    private static String printable(String s) {
        // Helps you see PUA keys + NULL terminator in logs
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127 || (c >= 0xE000 && c <= 0xF8FF)) {
                sb.append("\\u").append(String.format("%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
