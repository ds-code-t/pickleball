package utilities;

public class StringUtilities {

    public static boolean startsWithColonOrAtBracket(String s) {
        int len = s.length();
        int i = 0;

        // skip leading whitespace
        while (i < len && Character.isWhitespace(s.charAt(i))) {
            i++;
        }

        // need at least 2 chars after whitespace
        if (i + 1 >= len) {
            return false;
        }

        char c0 = s.charAt(i);

        // ':' case → must have another char after it
        if (c0 == ':') {
            return true;
        }

        // '@[' case
        if (c0 == '@' && i + 1 < len && s.charAt(i + 1) == '[') {
            return true;
        }

        return false;
    }

}
