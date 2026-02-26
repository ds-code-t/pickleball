package io.cucumber.gherkin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.common.GlobalConstants.NEXT_SIBLING_STEP;
import static tools.dscode.common.GlobalConstants.PARSER_FLAG;

public aspect EncodingParserLineSwap {


    private static final Pattern LINE_SWAP_PATTERN = Pattern.compile(
            "^((?:(?:\\s*:)|(?:\\s*(?:@|\\*\\s+)\\[DEBUG[^\\[\\]]*\\]))+)(\\s*[A-Z*].*$)?",
            Pattern.MULTILINE
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
            // Hardcoded fallback for group 2
            String prefix = (g2 != null) ? g2 : replacementStep;


            String replacement = prefix + PARSER_FLAG +
                    g1.replaceFirst("\\*\\s+\\[","@[")
                            .replace("\n"," ").strip();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
