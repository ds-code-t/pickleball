package tools.dscode.common.domoperations;

import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import tools.dscode.common.seleniumextensions.ElementWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static tools.dscode.common.domoperations.HumanInteractions.centerScroll;

public final class KeyParser {

    private KeyParser() {
    }

    // Flip this on/off as needed
    private static final boolean DEBUG = false;

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

    /**
     * Sends to the active element / browser context.
     */
    public static void sendComplexKeys(WebDriver driver, String input) {
        dbg("sendComplexKeys(driver) input=%s", quote(input));
        if (tools.dscode.common.composite.ElementOperations.primary()) {
            tools.dscode.common.composite.ElementOperations.keys(driver, input);
            return;
        }
        Actions actions = tools.dscode.common.composite.ElementOperations.actions(driver);
        parseInto(actions, input);
        dbg("actions.perform()");
        actions.perform();
        dbg("done");
    }

    /**
     * Sends to a specific element. Focus/click is NOT forced.
     */
    public static void sendComplexKeys(WebDriver driver, ElementWrapper element, String input) {
        if (element == null) {
            sendComplexKeys(driver, input);
            return;
        }

        try {
            centerScroll(driver, element);
        } catch (RuntimeException ignored) {
            // Best-effort scroll;
        }

        dbg("sendComplexKeys(driver, element) input=%s", quote(input));
        if (tools.dscode.common.composite.ElementOperations.primary()) {
            String plain = plainCharacters(input);
            if (plain != null) {
                if (!plain.isEmpty()) {
                    tools.dscode.common.composite.ElementOperations.appendKeys(driver, element, plain);
                }
                return;
            }
            if (tools.dscode.common.composite.ElementOperations.pressKeys(driver, element, input)) return;
            // No key verb, or a chord the verb cannot represent (two non-modifiers).
            // Focus, then the same Actions parser the Selenium path uses.
            tools.dscode.common.composite.ElementOperations.click(driver, element);
            Actions actions = tools.dscode.common.composite.ElementOperations.actions(driver);
            parseInto(actions, input);
            actions.perform();
            return;
        }
        Actions actions = tools.dscode.common.composite.ElementOperations.actions(driver);

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
     * <p>
     * A
     * A+B
     * CONTROL + A
     * CONTROL + SHIFT
     * <p>
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
        Keys ignoreCase = null;
        for (Keys key : Keys.values()) {
            if (key.name().equals(token)) {
                return key;
            }
            if (ignoreCase == null && key.name().equalsIgnoreCase(token)) {
                ignoreCase = key;
            }
        }
        if (ignoreCase != null) {
            return ignoreCase;
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

    /**
     * Chords the composite {@code key} verb can send, or null when the expression
     * needs WebDriver Actions. {@code ENTER} is {@code [ENTER]}. {@code CONTROL[A]}
     * and {@code CONTROL+A} are {@code [CONTROL+A]}. {@code SHIFT+TAB} is
     * {@code [SHIFT+TAB]}. {@code ENTER TAB} is {@code [ENTER, TAB]}. {@code A+B}
     * and {@code CONTROL[A+B]} are null: a chord has one non-modifier.
     * Each returned chord is one {@code key} command. The engine focuses the target on every command.
     */
    public static List<String> compositeChords(String input) {
        if (input == null) return null;
        int[] pos = {0};
        List<String> out = new ArrayList<>();
        if (!chordSequence(input, pos, List.of(), out, false)) return null;
        skipSpaces(input, pos);
        if (pos[0] < input.length()) return null;
        return List.copyOf(out);
    }

    private static boolean chordSequence(String input, int[] pos, List<String> held, List<String> out, boolean sub) {
        while (pos[0] < input.length()) {
            skipSpaces(input, pos);
            if (pos[0] >= input.length()) break;
            if (input.charAt(pos[0]) == ']') return sub;
            if (!chordTerm(input, pos, held, out)) return false;
        }
        return true;
    }

    private static boolean chordTerm(String input, int[] pos, List<String> held, List<String> out) {
        skipSpaces(input, pos);
        List<String> group = new ArrayList<>();
        if (!chordGroup(input, pos, group)) return false;
        skipSpaces(input, pos);
        if (pos[0] < input.length() && input.charAt(pos[0]) == '[') {
            pos[0]++;
            List<String> next = new ArrayList<>(held);
            for (String key : group) {
                if (!isModifier(key)) return false;
                next.add(key);
            }
            if (!chordSequence(input, pos, next, out, true)) return false;
            skipSpaces(input, pos);
            if (pos[0] >= input.length() || input.charAt(pos[0]) != ']') return false;
            pos[0]++;
            return true;
        }
        String chord = chordOf(held, group);
        if (chord == null) return false;
        out.add(chord);
        return true;
    }

    private static boolean chordGroup(String input, int[] pos, List<String> out) {
        String first = chordToken(input, pos);
        if (first == null) return false;
        out.add(first);
        while (true) {
            skipSpaces(input, pos);
            if (pos[0] < input.length() && input.charAt(pos[0]) == '+') {
                pos[0]++;
                String next = chordToken(input, pos);
                if (next == null) return false;
                out.add(next);
                continue;
            }
            break;
        }
        return true;
    }

    private static String chordToken(String input, int[] pos) {
        skipSpaces(input, pos);
        if (pos[0] >= input.length()) return null;
        char mark = input.charAt(pos[0]);
        if (mark == '+' || mark == '[' || mark == ']') return null;
        String token = parseToken(input, pos);
        if (token.isEmpty()) return null;
        return compositeToken(token);
    }

    private static String chordOf(List<String> held, List<String> group) {
        List<String> mods = new ArrayList<>(held);
        String main = null;
        for (String key : group) {
            if (isModifier(key) && main == null) {
                mods.add(key);
                continue;
            }
            if (main != null) return null;
            main = key;
        }
        if (main == null) {
            if (mods.isEmpty()) return null;
            main = mods.remove(mods.size() - 1);
        }
        if (mods.isEmpty()) return main;
        return String.join("+", mods) + "+" + main;
    }

    private static boolean isModifier(String key) {
        return "CONTROL".equals(key) || "SHIFT".equals(key) || "ALT".equals(key) || "META".equals(key);
    }

    /** Composite key name, or null when this token is not one composite key. */
    private static String compositeToken(String raw) {
        String name = raw.toUpperCase(Locale.ROOT);
        return switch (name) {
            case "ENTER", "RETURN" -> "ENTER";
            case "TAB" -> "TAB";
            case "ESCAPE", "ESC" -> "ESCAPE";
            case "UP", "ARROW_UP", "ARROWUP" -> "ARROWUP";
            case "DOWN", "ARROW_DOWN", "ARROWDOWN" -> "ARROWDOWN";
            case "LEFT", "ARROW_LEFT", "ARROWLEFT" -> "ARROWLEFT";
            case "RIGHT", "ARROW_RIGHT", "ARROWRIGHT" -> "ARROWRIGHT";
            case "BACK_SPACE", "BACKSPACE" -> "BACKSPACE";
            case "DELETE" -> "DELETE";
            case "HOME" -> "HOME";
            case "END" -> "END";
            case "PAGE_UP", "PAGEUP" -> "PAGEUP";
            case "PAGE_DOWN", "PAGEDOWN" -> "PAGEDOWN";
            case "SPACE" -> "SPACE";
            case "CONTROL", "CTRL", "LEFT_CONTROL" -> "CONTROL";
            case "SHIFT", "LEFT_SHIFT" -> "SHIFT";
            case "ALT", "LEFT_ALT" -> "ALT";
            case "META", "COMMAND", "WIN", "LEFT_META" -> "META";
            case "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12" -> name;
            default -> name.length() == 1 && Character.isLetterOrDigit(name.charAt(0)) ? name : null;
        };
    }

    /** Letters and digits the composite type verb can insert.
     *  null when the expression needs a real keydown (names, chords, holds).
     *  A lone A is a, matching resolveKey. */
    private static String plainCharacters(String input) {
        if (input == null || input.isBlank()) return "";
        int[] pos = {0};
        StringBuilder out = new StringBuilder();
        boolean saw = false;
        while (true) {
            skipSpaces(input, pos);
            if (pos[0] >= input.length()) break;
            char mark = input.charAt(pos[0]);
            if (mark == '[' || mark == ']') return null;
            List<CharSequence> group = parseGroup(input, pos);
            if (group.size() != 1 || !(group.get(0) instanceof String text) || text.isEmpty()) return null;
            out.append(text);
            saw = true;
        }
        return saw ? out.toString() : "";
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