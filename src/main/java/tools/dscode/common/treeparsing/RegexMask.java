package tools.dscode.common.treeparsing;

import java.util.Objects;

/**
 * Deterministic, reversible masker for [A-Za-z0-9#].
 * - Encodes each allowed char into BMP Private-Use Area (PUA) starting at U+E100.
 * - Bookends the encoded payload with noncharacters U+FDD0 (start) and U+FDEF (end).
 * - Reverses back to the original text and validates inputs.
 *
 * Notes:
 *  - The masked text uses untypable code points that won't appear in normal input.
 *  - Reversal is exact; any unexpected codepoint triggers an IllegalArgumentException.
 */
public final class RegexMask {

    // Allowed alphabet (index defines codepoint offset)
    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#";

    // Base for encoded chars (BMP Private Use Area U+E100..)
    private static final int PUA_BASE = 0xE100;

    // Bookend noncharacters (won't appear in normal text)
    private static final char BOOKEND_START = '\uFDD0'; // Noncharacter
    private static final char BOOKEND_END   = '\uFDEF'; // Noncharacter

    // Quick bounds for decode validation
    private static final int MIN_CODEPOINT = PUA_BASE;
    private static final int MAX_CODEPOINT = PUA_BASE + ALPHABET.length() - 1;

    private RegexMask() {}

    /**
     * Masks a string consisting only of [A-Za-z0-9#].
     * Returns: START_BOOKEND + encoded(payload) + END_BOOKEND
     */
    public static String mask(String plain) {
        Objects.requireNonNull(plain, "plain");

        StringBuilder out = new StringBuilder(plain.length() + 2);
        out.append(BOOKEND_START);

        for (int i = 0; i < plain.length(); i++) {
            char ch = plain.charAt(i);
            int idx = indexOfAllowed(ch);
            if (idx < 0) {
                throw new IllegalArgumentException(
                        "Unsupported character '" + ch + "' at position " + i +
                                " (allowed: A-Z a-z 0-9 #)");
            }
            out.append((char) (PUA_BASE + idx));
        }

        out.append(BOOKEND_END);
        return out.toString();
    }

    /**
     * Unmasks a previously masked string.
     * Expects: START_BOOKEND + encoded(payload) + END_BOOKEND
     * Returns the original payload (only [A-Za-z0-9#]).
     */
    public static String unmask(String masked) {
        Objects.requireNonNull(masked, "masked");
        if (masked.length() < 2 ||
                masked.charAt(0) != BOOKEND_START ||
                masked.charAt(masked.length() - 1) != BOOKEND_END) {
            throw new IllegalArgumentException("Input is not bookended masked text.");
        }

        StringBuilder plain = new StringBuilder(masked.length() - 2);
        for (int i = 1; i < masked.length() - 1; i++) {
            char enc = masked.charAt(i);
            int cp = enc;
            if (cp < MIN_CODEPOINT || cp > MAX_CODEPOINT) {
                throw new IllegalArgumentException(
                        String.format("Unexpected encoded code point U+%04X at position %d", cp, i));
            }
            int idx = cp - PUA_BASE;
            plain.append(ALPHABET.charAt(idx));
        }
        return plain.toString();
    }

    private static int indexOfAllowed(char ch) {
        // Fast paths for common ranges to avoid scanning the string for valid inputs
        if (ch >= '0' && ch <= '9') return ch - '0';
        if (ch >= 'A' && ch <= 'Z') return 10 + (ch - 'A');
        if (ch >= 'a' && ch <= 'z') return 36 + (ch - 'a');
        if (ch == '#') return ALPHABET.length() - 1; // last entry
        return -1;
    }
}
