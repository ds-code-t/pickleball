package tools.dscode.engine.parse;

import tools.dscode.common.GlobalConstants;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Same rewrite {@code EncodingParserLineSwap} applies before Gherkin parse.
 * The native parser runs it on the text. The aspect still runs for Cucumber.
 */
public final class EncodingRewrite {
    private static final Pattern LINE_SWAP_PATTERN = Pattern.compile(
            "^((?:(?:\\s*:)|(?:\\s*(?:@|\\*\\s+)\\[DEBUG[^\\[\\]]*\\]))+)(\\s*[A-Z*].*$)?",
            Pattern.MULTILINE
    );

    private EncodingRewrite() {
    }

    public static String apply(String text) {
        String original = text == null ? "" : text;
        Matcher matcher = LINE_SWAP_PATTERN.matcher(original);
        StringBuffer rewritten = new StringBuffer();
        String replacementStep = "* " + GlobalConstants.NEXT_SIBLING_STEP;
        while (matcher.find()) {
            String prefixGroup = matcher.group(1);
            String stepGroup = matcher.group(2);
            String prefix = stepGroup != null ? stepGroup : replacementStep;
            String replacement = prefix + GlobalConstants.PARSER_FLAG
                    + prefixGroup.replaceFirst("\\*\\s+\\[", "@[").replaceFirst("\n", " ").strip();
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }
}
