package io.pickleball.valueresolution;

import io.cucumber.core.backend.Status;
import io.pickleball.datafunctions.EvalList;
import io.pickleball.exceptions.PickleballException;
import io.pickleball.mapandStateutilities.MapsWrapper;
import io.pickleball.stringutilities.QuoteExtracter;
// import org.mvel2.MVEL; // Removed MVEL import
// import org.mvel2.ParserContext; // Removed ParserContext import

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.pickleball.cacheandstate.PrimaryScenarioData.getCurrentScenarioStatus;
import static io.pickleball.cacheandstate.PrimaryScenarioData.getPrimaryScenarioStatus;
import static io.pickleball.configs.Constants.*;
import static io.pickleball.stringutilities.ObjectTransformer.transformUntilStable;
import static io.pickleball.valueresolution.BooleanResolver.booleanMap;

public class ExpressionEvaluator extends ParseTransformer { // Note: Class name kept as MVELWrapper for compatibility

    public AviatorWrapper wrapper = new AviatorWrapper();
    public final static String VALUE_PREFIX = "__VAL";
    final static String ARRAY_PREFIX = VALUE_PREFIX + "_ARRAY_";
    final static Pattern VALUE_KEY_PATTERN = Pattern.compile("(?<value>\\b" + VALUE_PREFIX + "_[A-Z-_0-9]+__\\b)");
    final static Pattern OP_CHAIN_PATTERN = Pattern.compile("(?<opChain>\\b[A-Z-]+)\\s*:\\s*");

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
//            Object returObj = transformUntilStable(expression, exp -> evaluateOnce(String.valueOf(exp), variables));
//            System.out.println("@@returObj: " + returObj);
//            System.out.println("@@returObj.getClass: " + returObj.getClass());
//            return returObj;
            return transformUntilStable(expression, exp -> evaluateOnce(String.valueOf(exp), variables));
        } catch (Exception e) {
            // Updated error message to reflect Aviator instead of MVEL
            if (e.getMessage().toLowerCase().contains("aviator")) {
                e.printStackTrace();
                throw new PickleballException("Aviator failed to evaluate expression '" + expression + "'", e);
            }
            e.printStackTrace();
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
//        System.out.println("@@obj:::::: " + obj);
//        System.out.println("@@obj getClass:::::: " + obj.getClass());
//
//        String expression = String.valueOf(obj);
        MapsWrapper evalMap = new MapsWrapper();
        evalMap.addMaps(variables);
        String preprocessedExpression = preprocess(expression);
//        try {
        // Replaced MVEL.eval with AviatorEvaluator.execute
        // Aviator doesn't need a context object, uses static method instead
        QuoteExtracter preEvalMasker = new QuoteExtracter();
        evalMap.addMaps(preEvalMasker);

        String preEvalString = preEvalMasker.maskQuotedStrings(preprocessedExpression, true);
        System.out.println("@@preEvalString111: " + preEvalString);
//            preEvalString =  preEvalString.replaceAll("\\[([^\\[\\]]*)\\]","seq.list($1)");
//        MapsWrapper evalMap = new MapsWrapper(preEvalMasker, variables);


        String matchReturnString = preEvalString;
        boolean foundMatches;
        int keyIncrement = 1; // Starting key
        Map<String, Object> matchMap = new HashMap<>();

        do {
            Matcher matcher = arrayPattern.matcher(matchReturnString);
            StringBuilder result = new StringBuilder();
            foundMatches = false;

            // Process matches
            while (matcher.find()) {
                String key = ARRAY_PREFIX + (keyIncrement++) + "__";
//                    String key = "aa";
                String match = matcher.group(1);
                EvalList evalList = (EvalList) wrapper.evaluate("seq.list(" + match + ")", new HashMap<>(variables));
                matchMap.put(key, evalList);
                matcher.appendReplacement(result, " " + key + " ");
                foundMatches = true;
            }
            matcher.appendTail(result);
            matchReturnString = result.toString();
        } while (foundMatches);

        evalMap.addMaps(matchMap);


        String stringToEvaluate = matchReturnString;
//        OP_CHAIN_PATTERN


//            if(stringToEvaluate.contains(ARRAY_PREFIX)){
//                final Pattern predicatePattern = Pattern.compile("(ANY|NONE|All)-?(?:(HAS|HAVE)-((NO-)?VALUE))?:\\s*" + ARRAY_KEY_PATTERN);
//
//            }


        Object returnObj = wrapper.evaluate(stringToEvaluate, evalMap);
        System.out.println("@@returnObj: " + returnObj);
        return returnObj;

    }

    public String preprocess(String expression) {
        return transform(expression);
    }

}