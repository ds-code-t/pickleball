package io.cucumber.gherkin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.common.GlobalConstants.NEXT_SIBLING_STEP;
import static tools.dscode.common.GlobalConstants.PARSER_FLAG;

public aspect EncodingParserLineSwap {

    /** The same pattern you used (multiline). */
    private static final Pattern LINE_SWAP_PATTERN = Pattern.compile(
            "\n((?:(?:\\s*:)|(?:\\s*@\\[[^\\[\\]]*\\]))+)(\\s*[A-Z*].*$)?"
    );



    /**
     * Pointcut: execution of the target method returning String.
     * (Adjust the signature if your method is overloaded differently.)
     */
    pointcut readWithEncoding():
            execution(String io.cucumber.gherkin.EncodingParser.readWithEncodingFromSource(..));

    String replacementStep = "* " + NEXT_SIBLING_STEP;

    /**
     * Around advice: call the original method, then transform its return value.
     */
    String around(): readWithEncoding() {
        String ret = proceed();
        String original = (ret == null) ? "" : ret;
        Matcher matcher = LINE_SWAP_PATTERN.matcher(original);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String g1 = matcher.group(1);
            String g2 = matcher.group(2);

            System.out.println("@@g0: " + matcher.group(0));
            System.out.println("@@g1: " + g1);
            System.out.println("@@g2: " + g2);

            // Hardcoded fallback for group 2
            String prefix = (g2 != null) ? g2 : replacementStep;
            System.out.println("@@prefix: " + prefix);

            String replacement = prefix + PARSER_FLAG + g1.replace("\n"," ").strip();
            System.out.println("@@replacement: " + replacement);

            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
