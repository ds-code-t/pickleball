//package io.pickleball.customtypes;
//
//import io.pickleball.mapandStateutilities.CaseInsensitiveMap;
//import org.mvel2.DataConversion;
//import org.mvel2.MVEL;
//import org.mvel2.ParserContext;
//
//import java.util.Arrays;
//import java.util.HashSet;
//import java.util.Map;
//import java.util.Set;
//
//import static io.pickleball.cucumberutilities.GeneralUtilities.waitTime;
//
//public class EvalExpression {
//
//    private Boolean result;
//
//    static {
//        DataConversion.addConversionHandler(Number.class, new TruthyNumberHandler());
//        DataConversion.addConversionHandler(String.class, new TruthyStringHandler());
//    }
//
//    public EvalExpression(String inputExpression) {
//        String expression = "(" + inputExpression
////                .replace(" or ", "||")
//                .replace(" OR ", "||")
//                .replace(" AND ", "&&")
//                .replace("||", ") || (")
//                .replace("&&", ") && (") + ")";
//
//        System.out.println("@@expression1: " + expression);
////
////        String pattern = "\\(\\s*('[a-zA-Z\\s]*'|\"[a-zA-Z\\s]*\"|`[a-zA-Z\\s]*`)\\s*\\)|(\\(\\s*\\d+(?:\\.\\d+)?\\s*\\))";
////
////        Pattern regex = Pattern.compile(pattern);
////        Matcher matcher = regex.matcher(expression);
////
////        StringBuffer result = new StringBuffer();
////        while (matcher.find()) {
////            String fullMatch = matcher.group(1);
////            if (fullMatch == null)
////                fullMatch = matcher.group(2);
////            System.out.println("@@fullMatch: " + fullMatch);
////            // Trim first and last characters (the quotes) and compare inner content
////            String content = fullMatch.substring(1, fullMatch.length() - 1).trim().toLowerCase();
////            if (Set.of("", "false", "no", "null", "0").contains(content)) {
////                matcher.appendReplacement(result, "false");
////            } else {
////                matcher.appendReplacement(result, "true");
////            }
////        }
////
////        matcher.appendTail(result);
////
////        expression = result.toString();
//
//
//
//        try {
//            System.out.println("@@expression: " + expression);
//
//            ParserContext context = new ParserContext();
////            context.setStrictTypeEnforcement(true);
//
//            Map<String, Object> vars = new CaseInsensitiveMap();
//            vars.put("false", false);
//            vars.put("no", false);
//            vars.put("true", true);
//            vars.put("TruE", true);
//
//
//// Create the CustomResolverFactory for value resolution
//            CustomResolverFactory valueResolver = new CustomResolverFactory(false, vars);
//
//// Create the BooleanCoercingResolverFactory for boolean coercion
//            BooleanCoercingResolverFactory booleanResolver = new BooleanCoercingResolverFactory();
//
//// Chain them together - BooleanCoercing first, then CustomResolver
//            booleanResolver.setNextFactory(valueResolver);
//
//
//            var compiledExpression = MVEL.compileExpression(expression, context);
//            System.out.println("@@compiled: " + compiledExpression);
//
//
//            Object value = MVEL.executeExpression(compiledExpression, booleanResolver);
//            System.out.println("@@for val: " + value);
//
//            if (value instanceof Boolean) {
//                this.result = (Boolean) value;
//                System.out.println("@result1: " + this.result);
//            } else {
//                this.result = interpretAsBoolean(value);
//                System.out.println("@result2: " + this.result);
//            }
//        } catch (Exception e) {
//            System.out.println("@@===\n\n");
//            e.printStackTrace();
//            this.result = null;
//            waitTime(200L);
//        }
//    }
//
//    public static void main(String[] args) {
//
//        EvalExpression e2 = new EvalExpression("1 OR \"TruE\"");
//        System.out.println(" is: " + e2);
//
//        EvalExpression e3 = new EvalExpression(" 'false' == \"FALSE\" || 1 == 2 ");
//        System.out.println(" is: " + e3);
//    }
//
//    private Boolean interpretAsBoolean(Object value) {
//        if (value == null) return false;
//        if (value instanceof Number) {
//            return ((Number) value).doubleValue() != 0.0;
//        }
//        if (value instanceof String) {
//            String s = ((String) value).trim().toLowerCase();
//            return !Arrays.asList("", "false", "no", "null", "0").contains(s);
//        }
//        return true;
//    }
//
//    @Override
//    public String toString() {
//        return (result == null) ? null : result.toString();
//    }
//
//
//}
//
//class TruthyNumberHandler implements org.mvel2.ConversionHandler {
//    @Override
//    public Object convertFrom(Object o) {
//        System.out.println("@TruthyNumberHandler-convertFrom: " + o);
//        Number n = (Number) o;
//        return n.doubleValue() != 0;
//    }
//
//    @Override
//    public boolean canConvertFrom(Class cls) {
//        System.out.println("@TruthyNumberHandler-canConvertFrom: " + cls);
//        return Number.class.isAssignableFrom(cls);
//    }
//}
//
//class TruthyStringHandler implements org.mvel2.ConversionHandler {
//    private static final Set<String> FALSY_VALUES =
//            new HashSet<>(Arrays.asList("", "false", "no", "null", "0"));
//
//    @Override
//    public Object convertFrom(Object o) {
//        System.out.println("@TruthyStringHandler-convertFrom: " + o);
//        String s = String.valueOf(o).trim().toLowerCase();
//        return !FALSY_VALUES.contains(s);
//    }
//
//    @Override
//    public boolean canConvertFrom(Class cls) {
//        System.out.println("@TruthyStringHandler-canConvertFrom: " + cls);
//        return String.class.isAssignableFrom(cls);
//    }
//}
