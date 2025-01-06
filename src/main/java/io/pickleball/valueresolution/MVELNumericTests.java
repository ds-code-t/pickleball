//package io.pickleball.valueresolution;
//
//import org.mvel2.MVEL;
//
//import java.util.HashMap;
//import java.util.Map;
//
//import static io.pickleball.valueresolution.ExpressionParser.preParse;
//
//
//public class MVELNumericTests {
//
//
//    public static void main(String[] args) {
//        // Create context with test variables
//        Map<String, Object> context = new HashMap<>();
//        context.put("numInt", 42);
//        context.put("numLong", 123L);
//        context.put("numDouble", 3.14159);
//        context.put("strNum", "42");
//        context.put("strDec", "3.14");
//        context.put("emptyStr", "");
//        context.put("nullVal", null);
//        context.put("boolTrue", true);
//        context.put("boolStr", "true");
//
//        String expression = " (1) || (0)";
//
//                String parsed = preParse(expression);
//        System.out.println("\nOriginal : " + expression);
//        System.out.println("Parsed   : " + parsed);
//        Object result = MVEL.eval(parsed, context);
//
//        System.out.println("Result   : " + result);
//
//        String[] testExpressions = {
//                // Numeric comparisons with implicit casting
//                "42 == '42' AND 3.14 > '3' OR 123L <= 123.0",
//
//                // String concatenation with numbers
//                "'Value: ' + 42 == 'Value: 42' AND ('Num: ' + 3.14159).startsWith('Num')",
//
//                // Mixed numeric operations
//                "(numInt / 2.0 == 21) AND (numDouble * 2 > 6) OR (numLong / 100 == 1.23)",
//
//                // Boolean string conversion
//                "boolStr == 'true' AND boolTrue.toString() == 'true'",
//
//                // Null comparisons and empty checks
//                "nullVal == null AND emptyStr != null AND emptyStr.empty",
//
//                // Complex numeric comparisons
//                "(numInt + numDouble) > (numLong / 100) AND (strNum.length() * 2) == 4",
//
//                // String to number conversions in operations
//                "strNum.toInteger() + 8 == 50 AND strDec.toDouble() * 2 > 6",
//
//                // Mixed operations with parentheses
//                "((numInt * 2) == 84 AND (strNum + '0') == '420') OR (numDouble.toString().length() > 5)",
//
//                // Chained comparisons with different types
//                "numInt > 40 AND numInt < 43 AND numInt.toString() == '42'",
//
//                // Mathematical functions with implicit casting
//                "Math.max(numInt, strNum.toDouble()) == 42 AND Math.min(numLong, strDec.toDouble()) == 3.14"
//        };
//
////        for (String expr : testExpressions) {
////            try {
////                String parsed = preParse(expr);
////                Object result = MVEL.eval(parsed, context);
////                System.out.println("\nOriginal : " + expr);
////                System.out.println("Parsed   : " + parsed);
////                System.out.println("Result   : " + result);
////            } catch (Exception e) {
////                System.out.println("ERROR processing: " + expr);
////                System.out.println("Exception: " + e.getMessage());
////            }
////        }
//    }
//}