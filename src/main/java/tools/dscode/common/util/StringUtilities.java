package tools.dscode.common.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StringUtilities {

    // Tag prefix (U+206A: INHIBIT SYMMETRIC SWAPPING)
    private static final char TAG = '\u206A';

    // Common non-alnum, non-whitespace symbols to encode
    private static final String SYMBOLS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private static final Map<Character, String> ENC = new HashMap<>();
    private static final Map<String, Character> DEC = new HashMap<>();
    private static final Pattern DEC_PATTERN;

    static {
        // Assign codes A..Z,0..9 (enough for SYMBOLS length)
        for (int i = 0; i < SYMBOLS.length(); i++) {
            char sym = SYMBOLS.charAt(i);
            char code = (i < 26) ? (char) ('A' + i) : (char) ('0' + (i - 26));
            String token = new String(new char[] { TAG, code });
            ENC.put(sym, token);
            DEC.put(token, sym);
        }
        DEC_PATTERN = Pattern.compile(TAG + "([A-Z0-9])");
    }

    /**
     * Encodes common punctuation using \u206A + code; leaves alnum/whitespace
     * unchanged.
     */
    public static String encodeToPlaceHolders(String s) {
        if (s == null || s.isEmpty())
            return s == null ? "" : "";
        StringBuilder out = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch) || Character.isWhitespace(ch)) {
                out.append(ch);
            } else {
                String token = ENC.get(ch);
                out.append(token != null ? token : String.valueOf(ch));
            }
        }
        return out.toString();
    }

    /**
     * Decodes \u206A+code back to original punctuation; leaves unknown tokens
     * untouched.
     */
    public static String decodeBackToText(String s) {
        if (s == null || s.isEmpty())
            return s == null ? "" : "";
        Matcher m = DEC_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String token = "" + TAG + m.group(1).charAt(0);
            Character rep = DEC.get(token);
            String replacement = (rep == null) ? m.group(0) : rep.toString();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

}
