//package io.pickleball.stringutilities;
//
//import java.util.Arrays;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//import java.util.stream.Collectors;
//
//public class ExpressionParsing2 {
//    private static final Pattern PARENTHESES_PATTERN = Pattern.compile("[()]([^()]+)[()]");
//    private static final String BOOLEAN_OPERATOR = "\\b(and|or)\\b";
//    private static final String FLAG1 = "\u0001"; // Assuming this is flag1
//
//    public static String processText(String input) {
//        Matcher matcher = PARENTHESES_PATTERN.matcher(input);
//        StringBuffer result = new StringBuffer();
//
//        while (matcher.find()) {
//            String content = matcher.group(1);
//            System.out.println("DEBUG - Found content: '" + content + "'");
//            String stringToSplit = content.replaceAll(BOOLEAN_OPERATOR, FLAG1 + "$1" + FLAG1);
//            System.out.println("DEBUG - stringToSplit: '" + stringToSplit + "'");
//
//            String processed = Arrays.stream(stringToSplit.split(FLAG1))
//                    .map(String::trim)
//                    .map(s -> {
//                        if (s.isEmpty()) return "()";
//                        if (s.matches(BOOLEAN_OPERATOR)) return " " + s + " ";
//                        return "(" + s + ")";
//                    })
//                    .collect(Collectors.joining());
//            processed= "("+ processed + ")";
//            System.out.println("DEBUG - returnString: '" + processed + "'");
//            matcher.appendReplacement(result, processed); // Replace in context
//        }
//
//        matcher.appendTail(result); // Append remaining text
//        return result.toString();
//    }
//
//    public static void main(String[] args) {
//        String[] testCases = {
//                "This is (a test with and some or boolean terms) and (another test)",
//                "This is (simple) text",
//                "This is ((a and b) or c)",
//                "This is (a and (b or c))",
//                "This is (and expression)",
//                "This is (a or or b)"
//        };
//
//        for (String test : testCases) {
//            System.out.println("Input: " + test);
//            String result = processText(test);
//            System.out.println("Output: " + result + "\n");
//        }
//    }
//}