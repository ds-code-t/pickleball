//package io.pickleball.valueresolution;
//
//import java.util.Map;
//
//import static io.pickleball.configs.Constants.flag3;
//import static io.pickleball.stringutilities.QuotedSubstringExtractor.extractQuotedSubstrings;
//import static io.pickleball.stringutilities.QuotedSubstringExtractor.restoreQuotedSubstrings;
//
//public class ExpressionParser {
//
//
//
//    public static String preParse(String inputExpression) {
//        // First protect string literals
//        Map<String, String> quotedStrings = extractQuotedSubstrings(inputExpression);
//        String protectedText = quotedStrings.get("modifiedText");
//
//        // Process operators and add parentheses
//        String processed = "(" + protectedText
//                .replace(" OR ", "||")
//                .replace(" AND ", "&&")
//                .replace("||", flag3 +") || (" + flag3)
//                .replace("&&", flag3 +") && (" + flag3) + ")";
//
//        // Restore string literals
//        return restoreQuotedSubstrings(processed, quotedStrings);
//    }
//
//}
