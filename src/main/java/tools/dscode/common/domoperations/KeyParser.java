package tools.dscode.common.domoperations;

import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class KeyParser {

    private KeyParser() {}

    // Flip this on/off as needed
    private static final boolean DEBUG = true;

    private static void dbg(String fmt, Object... args) {
        if (DEBUG) System.out.println("[KeyParser] " + String.format(fmt, args));
    }

    /**
     * DSL rules:
     *
     * SPACE = separates sequential terms.
     * +     = combines keys into one simultaneous press/release group.
     * [...] = holds the group before the bracket while executing the inner sequence.
     *
     * Examples:
     *
     * CONTROL+A
     *   keyDown CONTROL
     *   keyDown a
     *   keyUp a
     *   keyUp CONTROL
     *
     * CONTROL[A B]
     *   keyDown CONTROL
     *   keyDown a
     *   keyUp a
     *   keyDown b
     *   keyUp b
     *   keyUp CONTROL
     *
     * CONTROL[A+B]
     *   keyDown CONTROL
     *   keyDown a
     *   keyDown b
     *   keyUp b
     *   keyUp a
     *   keyUp CONTROL
     *
     * CONTROL+SHIFT[A+B C+B]
     *   keyDown CONTROL
     *   keyDown SHIFT
     *   keyDown a
     *   keyDown b
     *   keyUp b
     *   keyUp a
     *   keyDown c
     *   keyDown b
     *   keyUp b
     *   keyUp c
     *   keyUp SHIFT
     *   keyUp CONTROL
     */

    /** Sends to the active element / browser context. */
    public static void sendComplexKeys(WebDriver driver, String input) {
        dbg("sendComplexKeys(driver) input=%s", quote(input));
        Actions actions = new Actions(driver);
        parseInto(actions, input);
        dbg("actions.perform()");
        actions.perform();
        dbg("done");
    }

    /** Sends to a specific element. Focus/click is NOT forced. */
    public static void sendComplexKeys(WebDriver driver, WebElement element, String input) {
        if (element == null) {
            sendComplexKeys(driver, input);
            return;
        }

        dbg("sendComplexKeys(driver, element) input=%s", quote(input));
        Actions actions = new Actions(driver);

        // Preserve your existing behavior: target the element without forcing click/focus.
        dbg("targeting element via actions.sendKeys(element, \"\")");
        actions.sendKeys(element, "");

        parseInto(actions, input);

        dbg("actions.perform()");
        actions.perform();
        dbg("done");
    }

    private static void parseInto(Actions actions, String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input cannot be null");
        }

        int[] pos = {0};

        dbg("parseInto start len=%d", input.length());

        parseSequence(actions, input, pos, false);

        skipSpaces(input, pos);

        if (pos[0] < input.length()) {
            throw new IllegalArgumentException("Unexpected text at position " + pos[0] + ": " + peek(input, pos));
        }

        dbg("parseInto end pos=%d", pos[0]);
    }

    private static void parseSequence(Actions actions, String input, int[] pos, boolean sub) {
        dbg("parseSequence(sub=%s) enter pos=%d next=%s", sub, pos[0], peek(input, pos));

        while (pos[0] < input.length()) {
            skipSpaces(input, pos);

            if (pos[0] >= input.length()) {
                break;
            }

            if (input.charAt(pos[0]) == ']') {
                if (sub) {
                    break;
                }
                throw new IllegalArgumentException("Unexpected ] at position " + pos[0]);
            }

            dbg("parseSequence loop pos=%d next=%s", pos[0], peek(input, pos));
            parseTerm(actions, input, pos);
        }

        dbg("parseSequence(sub=%s) exit pos=%d next=%s", sub, pos[0], peek(input, pos));
    }

    private static void parseTerm(Actions actions, String input, int[] pos) {
        dbg("parseTerm enter pos=%d next=%s", pos[0], peek(input, pos));

        skipSpaces(input, pos);

        int groupStart = pos[0];
        List<CharSequence> group = parseGroup(input, pos);

        if (group.isEmpty()) {
            throw new IllegalArgumentException("Expected key token at position " + pos[0]);
        }

        dbg("parsed group from [%d..%d): %s", groupStart, pos[0], showList(group));

        skipSpaces(input, pos);

        if (pos[0] < input.length() && input.charAt(pos[0]) == '[') {
            dbg("hold block begins at pos=%d with holder=%s", pos[0], showList(group));
            pos[0]++; // consume '['

            pressGroupDown(actions, group);
            parseSequence(actions, input, pos, true);

            skipSpaces(input, pos);

            if (pos[0] >= input.length() || input.charAt(pos[0]) != ']') {
                throw new IllegalArgumentException("Missing ] at position " + pos[0]);
            }

            dbg("hold block ends at pos=%d (consuming ])", pos[0]);
            pos[0]++; // consume ']'

            releaseGroupUp(actions, group);
            return;
        }

        pressAndReleaseGroup(actions, group);
    }

    /**
     * Parses one simultaneous key group:
     *
     * A
     * A+B
     * CONTROL + A
     * CONTROL + SHIFT
     *
     * Spaces around + are ignored.
     * A plain space without + ends the group.
     */
    private static List<CharSequence> parseGroup(String input, int[] pos) {
        List<CharSequence> out = new ArrayList<>();

        skipSpaces(input, pos);

        out.add(parseRequiredKey(input, pos));

        while (true) {
            int beforeSpaces = pos[0];
            skipSpaces(input, pos);

            if (pos[0] < input.length() && input.charAt(pos[0]) == '+') {
                dbg("consume '+' at pos=%d", pos[0]);
                pos[0]++; // consume '+'
                skipSpaces(input, pos);
                out.add(parseRequiredKey(input, pos));
                continue;
            }

            // No '+', so whitespace belongs to the outer sequence.
            // We intentionally keep pos after spaces because callers also skip spaces.
            // beforeSpaces is only kept for debug clarity if needed.
            if (beforeSpaces != pos[0]) {
                dbg("space ended group at pos=%d -> %d", beforeSpaces, pos[0]);
            }

            break;
        }

        return out;
    }

    private static CharSequence parseRequiredKey(String input, int[] pos) {
        skipSpaces(input, pos);

        if (pos[0] >= input.length()) {
            throw new IllegalArgumentException("Expected key token at end of input");
        }

        char c = input.charAt(pos[0]);

        if (c == '+' || c == '[' || c == ']') {
            throw new IllegalArgumentException("Expected key token at position " + pos[0] + " but found '" + c + "'");
        }

        int tokStart = pos[0];
        String token = parseToken(input, pos);

        if (token.isEmpty()) {
            throw new IllegalArgumentException("Empty key token at position " + tokStart);
        }

        CharSequence resolved = resolveKey(token);

        dbg("token [%d..%d): %s -> %s", tokStart, pos[0], quote(token), show(resolved));

        return resolved;
    }

    private static String parseToken(String input, int[] pos) {
        StringBuilder sb = new StringBuilder();

        while (pos[0] < input.length()) {
            char c = input.charAt(pos[0]);

            if (c == ' ' || c == '+' || c == '[' || c == ']') {
                break;
            }

            sb.append(c);
            pos[0]++;
        }

        return sb.toString();
    }

    private static CharSequence resolveKey(String token) {
        try {
            return Keys.valueOf(token);
        } catch (IllegalArgumentException ignored) {
            // Not a Selenium Keys enum.
        }

        if (token.length() == 1) {
            /*
             * Treat A-Z as physical letter-key identities, not uppercase text intent.
             *
             * This prevents CONTROL+A from behaving like "type uppercase A".
             * Use SHIFT[A] when you want uppercase typing behavior.
             */
            char c = token.charAt(0);

            if (Character.isLetter(c)) {
                return String.valueOf(token.toLowerCase(Locale.ROOT).charAt(0));
            }

            return token;
        }

        throw new IllegalArgumentException("Unknown key: " + token);
    }

    private static void pressAndReleaseGroup(Actions actions, List<CharSequence> group) {
        dbg("pressAndReleaseGroup %s", showList(group));
        pressGroupDown(actions, group);
        releaseGroupUp(actions, group);
    }

    /**
     * Simultaneous group press rule:
     * press left-to-right.
     */
    private static void pressGroupDown(Actions actions, List<CharSequence> group) {
        for (CharSequence key : group) {
            dbg("keyDown %s", show(key));
            actions.keyDown(key);
        }
    }

    /**
     * Simultaneous group release rule:
     * release right-to-left.
     */
    private static void releaseGroupUp(Actions actions, List<CharSequence> group) {
        for (int i = group.size() - 1; i >= 0; i--) {
            CharSequence key = group.get(i);
            dbg("keyUp %s", show(key));
            actions.keyUp(key);
        }
    }

    private static void skipSpaces(String input, int[] pos) {
        while (pos[0] < input.length() && input.charAt(pos[0]) == ' ') {
            pos[0]++;
        }
    }

    // --- tiny helpers for readable debug output ---

    private static String show(CharSequence cs) {
        if (cs == null) return "null";

        if (cs instanceof Keys k) {
            return "Keys." + k.name() + "(U+" + String.format("%04X", (int) k.charAt(0)) + ")";
        }

        String s = cs.toString();

        if (s.length() == 1) {
            return "'" + s + "'(U+" + String.format("%04X", (int) s.charAt(0)) + ")";
        }

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

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String peek(String input, int[] pos) {
        if (pos[0] >= input.length()) return "<EOF>";

        char c = input.charAt(pos[0]);
        return "'" + c + "' (pos " + pos[0] + ")";
    }
}