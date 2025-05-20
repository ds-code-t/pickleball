package io.pickleball.valueresolution;

import io.cucumber.core.backend.Status;
import io.pickleball.exceptions.PickleballException;
import io.pickleball.mapandStateutilities.MapsWrapper;
import io.pickleball.stringutilities.QuoteExtracter;
import java.util.Map;
import java.util.regex.Pattern;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentScenarioStatus;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getPrimaryScenarioStatus;
import static io.pickleball.stringutilities.Constants.*;
import static io.pickleball.stringutilities.ObjectTransformer.transformUntilStable;
import static io.pickleball.valueresolution.BooleanResolver.booleanMap;

public class ExpressionEvaluator extends ParseTransformer { // Note: Class name kept as MVELWrapper for compatibility

    public AviatorWrapper wrapper = new AviatorWrapper();
    public final static String VALUE_PREFIX = "__VAL";
    final static String ARRAY_PREFIX = VALUE_PREFIX + "_ARRAY_";
    final static String OP_PREFIX = VALUE_PREFIX + "OP";

    final static String VALUE_KEY_PATTERN = "(?<value>\\b" + VALUE_PREFIX + "_[A-Z-_0-9]+__\\b)";
//    final static String OP_CHAIN_PATTERN = "(?<opChain>\\b[A-Z-]+)\\s*:\\s*";
    final static Pattern OP_CHAIN_PATTERN = Pattern.compile("(?<opChain>\\b[A-Z-]+)\\s*:\\s*" + VALUE_KEY_PATTERN);



//    final static Pattern VALUE_KEY_PATTERN = Pattern.compile("(?<value>\\b" + VALUE_PREFIX + "_[A-Z-_0-9]+__\\b)");
//    final static Pattern OP_CHAIN_PATTERN = Pattern.compile("(?<opChain>\\b[A-Z-]+)\\s*:\\s*");

//    final static String ARRAY_KEY_PATTERN = ARRAY_PREFIX +"\\d+__";
//


//    final Pattern predicatePattern = Pattern.compile("(ANY|NONE|All)-?(?:(HAS|HAVE)-((NO-)?VALUE))?:\\s*" + ARRAY_KEY_PATTERN);

    static {

        var operationChain = "(?<opChain>\\b[A-Z-]+\\s*:\\s*)";

        var p = "\\s*:\\s*" + VALUE_PREFIX;

        var a = "(?<Quantifier1>ANY|NONE|All)-?(?:(HAS|HAVE)-((NO-)?VALUE))?:\\s*";
        var b = "(?:(HAS|HAVE)-((NO-)?VALUE))";


        String stateRegex = "(?<scenarioState>(?:(?:(?:HARD|SOFT)_)?FAIL)|PASS)[EDSING]*";
        addGlobalTransformation(
                "\\b(?<scenarioType>" + SCENARIO + "|" + TEST + ")_" + stateRegex,
                match -> {
                    String scenarioType = match.group("scenarioType");
                    String scenarioState = match.group("scenarioState");
                    Status status = scenarioType.equals(TEST) ? getPrimaryScenarioStatus() : getCurrentScenarioStatus();

                    boolean returnVal = switch (scenarioState) {
                        case "FAIL" -> (status.equals(Status.FAILED) || status.equals(Status.SOFT_FAILED));
                        case "SOFT_FAIL" -> status.equals(Status.SOFT_FAILED);
                        case "HARD_FAIL" -> status.equals(Status.FAILED);
                        case "PASS" -> (status.equals(Status.PASSED));
                        default -> throw new IllegalStateException("Unexpected value: " + scenarioState);
                    };

                    return String.valueOf(returnVal);
                }
        );

//        addGlobalStringTransformation(" AND | OR ", s -> s.toLowerCase());
//        addGlobalStringTransformation("&&|\\|\\|", s -> (s.equals("&&") ? " and " : " or "));
//        addGlobalStringTransformation("^.*[^\\)]\\s+(?:and|or\\b).*$", ExpressionParsing::addParenthesisToExpression);

        addGlobalTransformation(
                "^\\s*" + ParseTransformer.MASKED_QUOTE_PATTERN + "\\s*$",
                (transformer, match) -> {
                    String quotedText = stripQuotes(transformer.extracter.unmaskQuotedStrings(match.group()).strip());
                    return String.valueOf(booleanMap.getOrDefault(quotedText, true));
                }
        );
    }

    // private final ParserContext context; // Removed MVEL ParserContext


    public Object evaluate(String expression, Map<String, Object> variables) {
        try {
            return transformUntilStable(expression, exp -> evaluateOnce(String.valueOf(exp), variables));
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.toLowerCase().contains("aviator")) {
                e.printStackTrace();
                throw new PickleballException("Aviator failed to evaluate expression '" + expression + "'", e);
            }
            throw new RuntimeException(e);
        }
    }

    public Object evaluateOnce(String expression, Map<String, Object> variables) {
        String preprocessedString = String.valueOf(transformUntilStable(expression, exp -> preprocess(String.valueOf(exp))));
//        Object evaluatedValue = evaluateExpression(String.valueOf(exp);
        return transformUntilStable(preprocessedString, exp -> evaluateExpression(String.valueOf(exp), variables));
    }

    //    Pattern listPattern = Pattern.compile("\\[([^\\[\\]]*)\\]");
    final Pattern arrayPattern = Pattern.compile("\\[([^\\[\\]]*)\\]");

    public Object evaluateExpression(String expression, Map<String, Object> variables) {

        MapsWrapper evalMap = new MapsWrapper();
        evalMap.addMaps(variables);
        String preprocessedExpression = preprocess(expression);

        QuoteExtracter preEvalMasker = new QuoteExtracter();
        evalMap.addMaps(preEvalMasker);
        String preEvalString = preEvalMasker.maskQuotedStrings(preprocessedExpression, true);


        MapsWrapper subReplace = new MapsWrapper();

        String stringToEvaluate = preEvalString
                .replaceAll("(\\[|,)(?:<.*>|\\{\\{.*\\}\\})(,)","$1'null'$2")
                .replaceAll("(,)(?:<.*>|\\{\\{.*\\}\\})(\\])","$1'null'$2");


        stringToEvaluate = subReplace.matchReplace(stringToEvaluate, arrayPattern, ARRAY_PREFIX + "_%s__" , "seq.list($1)", " %s ");

        stringToEvaluate = subReplace.matchReplace(stringToEvaluate, OP_CHAIN_PATTERN, OP_PREFIX + "_%s__" , "predicateCheck(\"${opChain}\",${value})", " %s ");


        stringToEvaluate = subReplace.restoreSubstitutedValues(stringToEvaluate);

        return wrapper.evaluate(stringToEvaluate, evalMap);


    }

    public String preprocess(String expression) {
        return transform(expression);
    }

}