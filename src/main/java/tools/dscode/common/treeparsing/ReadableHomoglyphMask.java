package tools.dscode.common.treeparsing;

import java.util.HashMap;
import java.util.Map;

public final class ReadableHomoglyphMask {
    public enum Profile { FULLWIDTH, MATH_SANS }

    private ReadableHomoglyphMask() {}

    /** Mask ASCII letters/digits to lookalike Unicode (reversible). */
    public static String mask(String ascii, Profile profile) {
        StringBuilder sb = new StringBuilder(ascii.length());
        for (int i = 0; i < ascii.length(); i++) {
            char ch = ascii.charAt(i);
            sb.append(switch (profile) {
                case FULLWIDTH -> toFullwidth(ch);
                case MATH_SANS -> toMathSans(ch);
            });
        }
        return sb.toString();
    }

    /** Unmask back to plain ASCII. Works regardless of which profile was used. */
    public static String unmask(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            sb.appendCodePoint(fromLookalike(cp));
        }
        return sb.toString();
    }

    // ===== Fullwidth mapping =====
    private static char toFullwidth(char ch) {
        if (ch >= '0' && ch <= '9') return (char) (ch + 0xFEE0);       // 0..9 -> FF10..FF19
        if (ch >= 'A' && ch <= 'Z') return (char) (ch + 0xFEE0);       // A..Z -> FF21..FF3A
        if (ch >= 'a' && ch <= 'z') return (char) (ch + 0xFEE0);       // a..z -> FF41..FF5A
        return ch; // leave others as-is
    }

    // ===== Mathematical Sans (Monospace-like) mapping =====
    // Only map alnum; leave others unchanged.
    private static final Map<Integer,Integer> ASCII_TO_MATH = buildMathMap();
    private static String toMathSans(char ch) {
        Integer mapped = ASCII_TO_MATH.get((int) ch);
        return mapped != null ? new String(Character.toChars(mapped)) : String.valueOf(ch);
    }

    private static Map<Integer,Integer> buildMathMap() {
        // Digits 0..9 -> MATHEMATICAL SANS-SERIF BOLD DIGIT ZERO..NINE (U+1D7EC..U+1D7F5 looks similar),
        // Letters -> MATHEMATICAL SANS-SERIF (U+1D5A0..).
        Map<Integer,Integer> m = new HashMap<>(62);
        // A..Z
        int baseA = 0x1D5A0; // MATHEMATICAL SANS-SERIF CAPITAL A
        for (int i = 0; i < 26; i++) m.put((int)('A'+i), baseA + i);
        // a..z
        int basea = 0x1D5BA; // MATHEMATICAL SANS-SERIF SMALL A
        for (int i = 0; i < 26; i++) m.put((int)('a'+i), basea + i);
        // 0..9
        int base0 = 0x1D7E2; // MATHEMATICAL SANS-SERIF DIGIT ZERO
        for (int i = 0; i < 10; i++) m.put((int)('0'+i), base0 + i);
        return m;
    }

    // ===== Reverse map: lookalikes -> ASCII =====
    private static int fromLookalike(int cp) {
        // Fullwidth digits/letters
        if (cp >= 0xFF10 && cp <= 0xFF19) return cp - 0xFEE0;
        if (cp >= 0xFF21 && cp <= 0xFF3A) return cp - 0xFEE0;
        if (cp >= 0xFF41 && cp <= 0xFF5A) return cp - 0xFEE0;

        // Mathematical Sans-Serif (regular)
        if (cp >= 0x1D5A0 && cp <= 0x1D5B9) return 'A' + (cp - 0x1D5A0);
        if (cp >= 0x1D5BA && cp <= 0x1D5D3) return 'a' + (cp - 0x1D5BA);
        if (cp >= 0x1D7E2 && cp <= 0x1D7EB) return '0' + (cp - 0x1D7E2);

        // Not a mapped code point—return as-is.
        return cp;
    }

    // tiny demo
    public static void main(String[] args) {
        var plain = "Hello123";
        var m1 = mask(plain, Profile.FULLWIDTH);
        var m2 = mask(plain, Profile.MATH_SANS);
        System.out.println("FULLWIDTH: " + m1);
        System.out.println("MATH_SANS: " + m2);
        System.out.println("Back 1: " + unmask(m1));
        System.out.println("Back 2: " + unmask(m2));
    }
}
