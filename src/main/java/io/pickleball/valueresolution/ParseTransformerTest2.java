//package io.pickleball.valueresolution;
//
//
//import static io.pickleball.valueresolution.ParseTransformer.MASK_CHAR;
//
//public class ParseTransformerTest2 {
//
//
//    /**
//     * Simple equality checker that throws AssertionError if not equal.
//     */
//    private static void assertEquals(String expected, String actual, String message) {
//        if (!expected.equals(actual)) {
//            throw new AssertionError(String.format("%s%nExpected: %s%nActual: %s",
//                    message, expected, actual));
//        }
//        System.out.println("✓ " + message);
//    }
//
//
//
//    public static void main(String[] args) {
//        ParseTransformerTest2 test = new ParseTransformerTest2();
//        test.testSwapQuotes();
//    }
//
//
//    public static final String MASKED_QUOTE = "[`'\"]"+ MASK_CHAR + "*[`'\"]";
//    private void testSwapQuotes() {
//        ParseTransformer transformer = new ParseTransformer();
//
//        transformer.addLocalTransformation("First\\s+([`'\"]␄+[`'\"])",
//                match -> "First text is '" + match.getPlaceholderMap().get("␄␄").content + "'");
//
//        transformer.addLocalTransformation("Second\\s+([`'\"]␄+[`'\"])",
//                match -> "second text is '" + match.getPlaceholderMap().get("␄").content + "'");
//
//        String input = "First 'AAA' , and Second 'BBB'";
//        String expected = "First text is 'BBB' , and second text is 'AAA'";
//
//        String output = transformer.transform(input);
//        assertEquals(expected, output, "Should swap 'AAA' to 'BBB' and 'BBB' to 'AA'");
//    }
//
//
//}
