package io.cucumber.gherkin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.common.GlobalConstants.PARSER_FLAG;

/**
 * Rewrites the string returned by EncodingParser.readWithEncodingFromSource(..)
 * by swapping the order of certain prefix segments and the main line content,
 * inserting a META flag in between — equivalent to the provided ByteBuddy DSL.
 *
 * ByteBuddy DSL intent:
 *   - Pattern (MULTILINE):
 *       ^((?:(?:\s*:)|(?:\s*@\[[^\[\]]*\]))+)(\s*[A-Z*].*$)
 *   - Replacement: "$2" + PARSER_FLAG + "$1"
 *
 * This aspect applies the same transformation with AspectJ.
 */
public aspect EncodingParserLineSwap {

    /** The same pattern you used (multiline). */
    private static final Pattern LINE_SWAP_PATTERN = Pattern.compile(
            "^((?:(?:\\s*:)|(?:\\s*@\\[[^\\[\\]]*\\]))+)(\\s*[A-Z*].*$)",
            Pattern.MULTILINE
    );



    /**
     * Pointcut: execution of the target method returning String.
     * (Adjust the signature if your method is overloaded differently.)
     */
    pointcut readWithEncoding():
            execution(String io.cucumber.gherkin.EncodingParser.readWithEncodingFromSource(..));

    /**
     * Around advice: call the original method, then transform its return value.
     */
    String around(): readWithEncoding() {
        String ret = proceed();
        String original = (ret == null) ? "" : ret;
        System.out.println("@@original: " + original);
        Matcher matcher = LINE_SWAP_PATTERN.matcher(original);
        System.out.println("@@matcher.replaceAll(\"$2\" + PARSER_FLAG + \"$1\"): " + matcher.replaceAll("$2" + PARSER_FLAG + "$1"));

        return matcher.replaceAll("$2" + PARSER_FLAG + "$1");
    }
}
