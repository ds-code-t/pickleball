package tools.ds.modkit.evaluations;

import java.util.function.Function;
import java.util.function.Function;

public class BookendUtils {
    public static final String OPEN_PLACEHOLDER  = "\u206A-OPEN";
    public static final String CLOSE_PLACEHOLDER = "\u206A-CLOSED";

    /**
     * Generic processor for balanced bookends.
     *
     * @param src         the input string
     * @param open        the opening bookend (e.g. "(", "{", "[", "<tag>")
     * @param close       the closing bookend (e.g. ")", "}", "]", "</tag>")
     * @param transformer function to apply to the inner content
     * @param openPh      placeholder for open bookend
     * @param closePh     placeholder for close bookend
     * @return rewritten string with placeholders
     */
    public static String preprocessBalancedBookends(
            String src,
            String open,
            String close,
            Function<String, String> transformer,
            String openPh,
            String closePh) {

        int closeIdx = src.indexOf(close);
        while (closeIdx >= 0) {
            int openIdx = src.lastIndexOf(open, closeIdx);
            if (openIdx >= 0) {
                String inner = src.substring(openIdx + open.length(), closeIdx);

                // recursively process inside first
                String processed = preprocessBalancedBookends(inner, open, close, transformer, openPh, closePh);

                // apply transformer to this inner block
                processed = transformer.apply(processed);

                // replace with placeholder
                String replaced = openPh + processed + closePh;
                src = src.substring(0, openIdx) + replaced + src.substring(closeIdx + close.length());

                // restart search
                closeIdx = src.indexOf(close);
                continue;
            }
            closeIdx = src.indexOf(close, closeIdx + close.length());
        }
        return src;
    }

    /** Restore placeholders back to given bookend strings. */
    public static String restoreBookends(String src, String openPh, String closePh, String open, String close) {
        return src.replace(openPh, open).replace(closePh, close);
    }

    /** Convenience: specific for parentheses. */
    public static String preprocessBalancedParens(String src, Function<String, String> transformer) {
        return preprocessBalancedBookends(src, "(", ")", transformer, OPEN_PLACEHOLDER, CLOSE_PLACEHOLDER);
    }

    public static String restoreParens(String src) {
        return restoreBookends(src, OPEN_PLACEHOLDER, CLOSE_PLACEHOLDER, "(", ")");
    }
}
