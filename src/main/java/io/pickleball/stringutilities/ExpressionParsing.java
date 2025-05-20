package io.pickleball.stringutilities;

import static io.pickleball.stringutilities.Constants.*;

public class ExpressionParsing {
    private static final String BOOLEAN_OPERATOR = "\\b(and|or)\\b";
    private static final String leftFlag = "~"  + flag2 + flag1; // Assuming this is flag1
    private static final String rightFlag = flag1 + flag2 + "~"; // Assuming this is flag1

    public static String addParenthesisToExpression(String input) {
        String stringToSplit = input.replaceAll(BOOLEAN_OPERATOR, leftFlag + "$1" + rightFlag);
        stringToSplit = stringToSplit.replaceAll("(^|\\(|" + rightFlag + ")([^()]*?)(" + leftFlag + ")", "$1\\($2\\)$3");
        stringToSplit = stringToSplit.replaceAll("(" + rightFlag + ")([^()]*?)($|\\)|" + leftFlag + ")", "$1\\($2\\)$3");
        return stringToSplit.replaceAll(leftFlag, " ").replaceAll(rightFlag, " ");
    }
//    public static void main(String[] args) {
//        String[] testCases = {
//                " 4 or  4 < 3"
//        };
//
//        for (String test : testCases) {
//            System.out.println("Input: " + test);
//            String result = addParenthesisToExpression(test);
//            System.out.println("Output: " + result + "\n");
//        }
//    }
}