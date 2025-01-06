//package io.pickleball.customtypes;
//
//
//import org.mvel2.CompileException;
//import org.mvel2.MVEL;
//import org.mvel2.ParserContext;
//
//import java.io.Serializable;
//import java.util.HashMap;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//
//import static io.pickleball.valueresolution.BooleanResolver.resolveObjectToBoolean;
//
//
//public class EvalExpression3 extends HashMap<String, Object> {
//
//    private final Object defaultValue;
//
//    public static final char booleanFlag = '\u2403';
//    public static final String booleanFlagString = String.valueOf(booleanFlag);
////    private static final char delimiter = '\u2402';
////    private static final String delimiterString = String.valueOf(delimiter);
//
//    private Object returnValue;
//    private final String inputExpression;
//    private final String parsedQuotesString;
//    private final String booleanParsed;
//    private final String parsedParenthesisString;
//    private String currentText;
//    //    final List<String> components;
//    private ParserContext context;
//
//    static String[] testExpressions2 = {
////            "((Integer.MAX_VALUE > '1.0') AND (Integer.MIN_VALUE < '1.0')) OR ((1/0 > 0) AND (-1/0 < 0))"
////            "((Integer.MAX_VALUE > '1.0') AND true)"
//            "if ((Integer.MAX_VALUE > '1.0') AND true) return 5;"
//
//    };
//
//    static String[] testExpressions = {
//            // Deeply nested parentheses with mixed types
//            "((((5 > '3') AND (true)) OR ((!'false') AND ('0' == 0))) AND (((1.5 < 2) OR (!'no')) AND ((0.0 == '0') OR (!null))))",
//            "(((('yes' AND 1) OR ('true' AND 0)) AND ((!false) OR ('1.5' > 1))) OR ((('NO' == false) AND (11 < '.001')) OR (true)))",
//
//            // Complex type coercion cases
//            "'true' AND 1 AND 1.0 AND 'yes' AND !('false')",
//            "'0.0' OR 0 OR false OR 'no' OR 'FALSE' OR null",
//            "'1.5' > 1 AND '2.0' <= '2' AND '-1' < '-0.5'",
//
//            // Mixed numeric comparisons
//            "1.23e-4 < '0.0001' AND .01 > '0.009' AND -1 == '-12300.0'",
//            "'100' + 50 > '125' AND '200' - '50' == 150",
//
//            // String and numeric operations mixed
//            "(('abc' == 'abc') AND (100 > '50')) OR (('def' != 'ghi') AND ('75.5' >= 75))",
//            "((!'no') AND ('1' == 1)) OR ((!'false') AND ('0.0' == 0))",
//
//            // Edge cases with whitespace and special values
//            "   (  '   '    )   OR   (  ''  )   AND   (  null  )",
//            "!('false') AND !'0' AND !'no' AND !'' AND !null",
//
//            // Complex boolean logic with implicit conversions
//            "!(('0' OR false) AND ('no' OR '')) OR (('true' AND 1) OR ('yes' AND !null))",
//            "(('1' > 0) AND ('true' == true)) OR (('yes' != 'no') AND ('1.0' == 1))",
//
//            // Arithmetic with mixed types
//            "((5 + '3') * 2 > '15') AND ((10 - '3') / 2 >= '3.5')",
//            "(('10' * 2 + 5) > 25) OR (('100' / '2' - '25') <= 25)",
//
//            // Nested comparisons with multiple data types
//            "((('true' == true) AND ('1' == 1)) OR (('false' == 0) AND ('no' == false))) AND ((1.5 > '1') OR (2 <= '2.5'))",
//
//            // Complex logical operations with type coercion
//            "!(!('true') OR !('1')) AND !(!true OR !'yes') OR !(!'false' AND !'0')",
//
//            // Mixed comparison operators
//            "'abc' < 'def' AND '123' <= 123 AND '456' > '123' AND 789 >= '456'",
//
//            // Extremely nested expression with mixed everything
//            "((((('1' == 1) AND ('true' == !false)) OR (('no' == !'yes') AND ('0' == !true))) AND (((1.5 > '1.4') OR ('2.0' <= 2)) AND (('-1' < 0) OR (0 >= '-0.1')))) OR ((((null == '') OR ('false' == 0)) AND (('true' != 'false') OR ('1' != '0'))) OR (((1 + '2') > 2.5) AND (('4' * 2) <= '10'))))",
//
//            // Edge cases with scientific notation and decimals
//            "1.23E-4 < '0.000123' AND -1.23e+4 > '-12301' AND .01 == '0.01'",
//
//            // Complex string comparisons with numeric conversions
//            "'100a' != 100 AND 'abc123' != 123 AND '123abc' != 123",
//
//            // Boundary value testing
//            "((Integer.MAX_VALUE > '1.0') AND (Integer.MIN_VALUE < '1.0')) OR ((1/0 > 0) AND (-1/0 < 0))"
//    };
//
//    public static void main(String[] args) {
//
//
//        for (String expr : testExpressions) {
//            System.out.println("@@␃");
//            System.out.println("@@" + booleanFlagString);
//            System.out.println("\n========\nExpression::: " + expr);
//            EvalExpression3 ex = new EvalExpression3(expr);
//            String returnValue = String.valueOf(ex.startEval());
////            String reevaluated = "";
////            while (!returnValue.equals(reevaluated)) {
////                reevaluated = returnValue;
////                returnValue = (String) ex.eval(returnValue);
////            }
//
//
//            System.out.println("Evaluated value: : " + returnValue);
////            System.out.println(ex);
//        }
//    }
//    public EvalExpression3(String inputExpression) {
//        this(inputExpression, null);
//    }
//
//
//    public EvalExpression3(String inputExpression, String defaultValue) {
////        put(OP, " ( ");
////        put(CP, " ) ");
//
//
//        this.currentText = inputExpression;
//        this.inputExpression = inputExpression;
//        this.defaultValue = defaultValue;
//        this.parsedQuotesString = extractQuotedSubstrings();
//        extractNumericValues();
//        this.booleanParsed = preParse();
//
////        this.parsedParenthesisString = currentText.replace(booleanFlagString, "");
//        this.parsedParenthesisString = extractParenthesizedSubstrings();
//
//        context = new ParserContext();
////        context.setBlockSymbols(false);
//        context.setStrictTypeEnforcement(false);
//        context.setStrongTyping(false);
//    }
//
//
//    public Object evaluate(String inputString) {
//        Object obj;
//        String expressionString = replaceParens(inputString);
//        try {
////            Serializable compiled = MVEL.compileExpression(inputExpression.replace(CP, ")").replace(OP, "("), context);
//            Serializable compiled = MVEL.compileExpression(expressionString, context);
////            System.out.println("@@evaluate: " + inputExpression);
//            obj = MVEL.executeExpression(compiled, this);
//        } catch (CompileException | ClassCastException e) {
//            System.out.println("@@message: " + e.getMessage());
//            if(e.getMessage().contains("cannot be cast to class java.lang.Boolean"))
//                return resolveObjectToBoolean(expressionString);
//            e.printStackTrace();
//            obj = expressionString;
//        }
//        System.out.println("@@evaluate:  " + expressionString + " -> " + obj);
//        boolean matches = Pattern.matches("\\b___paren_\\w+\\b", expressionString.replace("(",""));
//        return "(" + obj + ")";
////        return obj;
//    }
//
//    public Object startEval() {
//        System.out.println("startEval Expression::: " + restoreExtractedValues(parsedParenthesisString));
//        String obj = String.valueOf(evaluate(parsedParenthesisString));
////        if (obj instanceof Boolean)
////            return obj;
//        return obj.substring(1,obj.length()-1);
//    }
//
//    public Object eval(String inputExpression) {
//        Object obj = evaluate(inputExpression);
//        if (obj instanceof Boolean)
//            return obj;
//        return obj;
////        return "(" + obj + ")";
//    }
//
//    private final String keyPrefix = "___";
//    private final String OP = " ___OP_ ";
//    private final String CP = " ___CP_ ";
//
//    private String replaceParens(String input) {
//        return input.replace(OP, "(").replace(CP, ")");
//    }
//
//    public Object regularGet(Object key) {
//        return super.get(key);
//    }
//
////    @Override
////    public boolean containsKey(Object key) {
////        if (CLASS_LITERALS.containsKey(key))
////            return false;
////
////
////        return true;
////    }
//
//    @Override
//    public Object get(Object key) {
//        System.out.println("@@get override key: " + key);
//        if (!super.containsKey(key))
//            return String.valueOf(key);
//
//
//        Object value = super.get(key);
//        String stringKey = key.toString();
//        String stringVal = value.toString();
//
//        System.out.println("@@value: " + stringVal);
//
//        if (!stringKey.startsWith(keyPrefix))
//            return stringVal;
//
//        else if (stringKey.startsWith(numericKey))
//            return Double.valueOf(stringVal);
//
//        else if (stringKey.contains(parenKey)) {
//            String innerVal = stringVal.substring(1, stringVal.length() - 1);
//            return eval(stringVal);
////            return stringVal;
//        } else if (stringKey.contains(parenBoolKey)) {
//            String innerVal = stringVal.substring(1, stringVal.length() - 1);
//            boolean evalBoolValue = resolveObjectToBoolean(eval(innerVal));
//            return  evalBoolValue;
//        }
//        return value.toString();
//    }
//
//
//    private static final Pattern QUOTE_PATTERN = Pattern.compile(
//            "([\"'`])"           // Capture opening quote, single quote, or backtick
//                    + "((?:\\\\.|(?!\\1).)*?)" // Capture any escaped char or any char that isn't the delimiter
//                    + "\\1"              // Match the same quote/backtick as closing
//    );
//
//
//    private final String quotedKey = keyPrefix + "quoted_";
//
//    public String extractQuotedSubstrings() {
//        Matcher matcher = QUOTE_PATTERN.matcher(currentText);
//        StringBuffer result = new StringBuffer();
//
//        while (matcher.find()) {
//            String value;
//            String key = quotedKey;
//
//            switch (matcher.group(1)) {
//                case "`":
//                    key += "bt";
//                    value = matcher.group(2);
////                    value = "\"" + matcher.group(2).replaceAll("(?<!\\\\)\"", "\\\\\"") + "\"";
//                    break;
//                case "'":
//                    key += "sq";
//                    value = matcher.group(2);
//                    break;
//                default:
//                    key += "dq";
//                    value = matcher.group(2);
//                    break;
//            }
//
//            key += "_" + size();
////            put( delimiterString + key  + delimiterString, value);
//            put(key, value);
//            matcher.appendReplacement(result, key);
//        }
//
//        matcher.appendTail(result);
//        put("quotedExtracted", result.toString());
//        currentText = result.toString();
//        String quotedText = currentText;
//        currentText = currentText.replaceAll("\\b(null)\\b", "'$1'");
//        if (!quotedText.equals(currentText))
//            extractQuotedSubstrings();
//        return currentText;
//    }
//
//    private static final Pattern NUMERIC_PATTERN = Pattern.compile(
//            "(?<![\\w$])"           // Negative lookbehind for word chars or $
//                    + "(-?"                 // Optional negative sign
//                    + "(?:"                 // Non-capturing group for number formats
//                    + "\\d+\\.?\\d*"       // Matches: 123, 123., 123.456
//                    + "|"                   // OR
//                    + "\\.\\d+"            // Matches: .123
//                    + ")"                   // End of number formats group
//                    + ")"                   // End of negative sign group
//                    + "(?![.\\w])"         // Negative lookahead for word chars or additional dots
//    );
//
//    private final String numericKey = keyPrefix + "numeric_";
////    private final String boolKey = "bool_";
//
//    public String extractNumericValues() {
//        Matcher matcher = NUMERIC_PATTERN.matcher(currentText);
//        StringBuffer result = new StringBuffer();
//
//        while (matcher.find()) {
//            String value = matcher.group(0);
//            String key = numericKey + size();
//
//            // Verify it's a valid number before storing
//            try {
//                Double.parseDouble(value);
//                put(key, value);
//                matcher.appendReplacement(result, key);
//            } catch (NumberFormatException e) {
//                // Skip invalid numbers
//                matcher.appendReplacement(result, matcher.group(0));
//            }
//        }
//
//        matcher.appendTail(result);
//        currentText = result.toString();
//        return currentText;
//    }
//
//
//    public String preParse() {
//        // First protect string literals
//
//
//        // Process operators and add parentheses
//        currentText = "(" + currentText
//                .replace(" OR ", "||")
//                .replace(" AND ", "&&")
//                .replace("||", booleanFlagString + ") || (" + booleanFlagString)
//                .replace("&&", booleanFlagString + ") && (" + booleanFlagString) + ")";
//        return currentText;
//    }
//
//    private final String parenKey = keyPrefix + "paren_";
//    private final String parenBoolKey = keyPrefix + "paren_bool_";
//
//
//    private static final Pattern NESTED_PARENTHESES_PATTERN = Pattern.compile(
//            "\\([^()]*\\)"
//    );
//
//    public String extractParenthesizedSubstrings() {
//        String prevText;
//        System.out.println("@@currentText1: " + currentText);
//        do {
//            prevText = currentText;
//            Matcher matcher = NESTED_PARENTHESES_PATTERN.matcher(currentText);
//            StringBuffer result = new StringBuffer();
//
//            while (matcher.find()) {
//                String value = matcher.group(0);
//                String key = value;
//                if (value.contains(booleanFlagString)) {
//                    value = value.replace(booleanFlagString, "");
//                    key = parenBoolKey + size();
//
//                    System.out.println("@@key:::: " + key);
//                    System.out.println("@@value:::: " + value);
//
//                    put(key, value);
////                matcher.appendReplacement(result, OP + " " + key + " " + CP);
////                    matcher.appendReplacement(result, key);
//                } else {
//
//                    key = parenKey + size();
//
//                    System.out.println("@@key:::: " + key);
//                    System.out.println("@@value:::: " + value);
//
//                    put(key, value);
////
////                    matcher.appendReplacement(result, key);
//                }
//
//                matcher.appendReplacement(result, OP  + " " + key + " " +  CP);
//
//            }
//
//            matcher.appendTail(result);
//            currentText = result.toString();
//            System.out.println("@@-currentText " + currentText);
//        } while (!prevText.equals(currentText));
//        System.out.println("@@currentText2: " + currentText);
////        currentText  = currentText.replaceAll("\\b___paren_\\w+\\b", "($0)");
////        currentText  = currentText.replaceAll(OP," ( ").replaceAll(CP," ) ");
////        System.out.println("@@currentText3: " + currentText);
//        return currentText;
//    }
//
//    public String restoreExtractedValues(String inputText) {
//        // Create pattern to match any key starting with ___
//        String returnText = inputText;
//        String oldText = "";
//
//        while (!returnText.equals(oldText)) {
//            oldText = returnText;
//            Pattern keyPattern = Pattern.compile("(___[\\w]+)");
//            Matcher matcher = keyPattern.matcher(returnText);
//
//            StringBuffer result = new StringBuffer();
//
//            while (matcher.find()) {
//                String key = matcher.group(1);
//                String value = String.valueOf(regularGet(key));
//                if (value != null) {
//                    // Replace the key with its original value
//                    matcher.appendReplacement(result, Matcher.quoteReplacement(value));
//                } else {
//                    // If key not found in map, preserve original text
//                    matcher.appendReplacement(result, key);
//                }
//            }
//            matcher.appendTail(result);
//            returnText = result.toString();
//        }
//
//        return returnText.replace(booleanFlagString, "");
//    }
//
//
//}
