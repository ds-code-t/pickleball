//package io.pickleball.valueresolution;
//
//public class ParseTransformerTest3 {
//    private static void assertEquals(String expected, String actual, String message) {
//        if (!expected.equals(actual)) {
//            throw new AssertionError(String.format("%s%nExpected: %s%nActual: %s",
//                    message, expected, actual));
//        }
//        System.out.println("✓ " + message);
//    }
//
//    private static void assertThrows(Runnable test, String message) {
//        try {
//            test.run();
//            throw new AssertionError("Expected exception but none was thrown: " + message);
//        } catch (RuntimeException e) {
//            System.out.println("✓ " + message + " - Got expected exception: " + e.getMessage());
//        }
//    }
//
//    public static void main(String[] args) {
//        ParseTransformerTest3 test = new ParseTransformerTest3();
//        test.testMixedCaptureGroups();
////        test.testNestedQuotes();
////        test.testIterativeTransformations();
////        test.testInfiniteLoopDetection();
////        test.testGlobalAndLocalTransformations();
////        test.testEmptyQuotes();
////        test.testComplexPatternMatching();
////        test.testMixedQuoteTypes();
//    }
//
//    private void testMixedCaptureGroups() {
//        ParseTransformer transformer = new ParseTransformer();
//        transformer.addLocalTransformation(
//                "(?<type>[A-Z]\\w+)?\\s*(?<name>[a-z]\\w*)\\s*=\\s*(['\"`])(?<value>.+?)\\3(?<space>\\s*)",
//                match -> {
//                    String type = match.group("type");
//                    String name = match.group("name");
//                    String value = match.group("value");
//                    String quote = match.group(3);
//                    String space = match.group("space");
//                    return (type != null ? type + " " : "") +
//                            name.toUpperCase() + " = " + quote + value + quote + space;
//                }
//        );
//
//        String input = "name = 'John' String age = \"25\" count = `10`";
//        String expected = "NAME = 'John' String AGE = \"25\" COUNT = `10`";
//
//        System.out.println("\nMixed Capture Groups Test:");
//        System.out.println("Input:    " + input);
//        String result = transformer.transform(input);
//        System.out.println("Output:   " + result);
//        assertEquals(expected, result, "Mixed named and numbered capture groups should work correctly");
//    }
//
////    private void testNestedQuotes() {
////        ParseTransformer transformer = new ParseTransformer();
////        transformer.addLocalStringTransformation("nested", match -> "NESTED");
////
////        String input = "nested 'nested \"nested\" nested' \"nested 'nested' nested\"";
////        String expected = "NESTED 'nested \"nested\" nested' \"nested 'nested' nested\"";
////
////        System.out.println("\nNested Quotes Test:");
////        System.out.println("Input:    " + input);
////        String result = transformer.transform(input);
////        System.out.println("Output:   " + result);
////        assertEquals(expected, result, "Should handle nested quotes correctly");
////    }
////
////    private void testIterativeTransformations() {
////        ParseTransformer transformer = new ParseTransformer();
////        transformer.addLocalStringTransformation("a", match -> "b");
////        transformer.addLocalStringTransformation("b", match -> "c");
////        transformer.addLocalStringTransformation("c", match -> "d");
////
////        String input = "a 'a' \"b\" `c`";
////        String expected = "d 'a' \"b\" `c`";
////
////        System.out.println("\nIterative Transformations Test:");
////        System.out.println("Input:    " + input);
////        String result = transformer.transform(input);
////        System.out.println("Output:   " + result);
////        assertEquals(expected, result, "Should apply transformations iteratively");
////    }
////
////    private void testInfiniteLoopDetection() {
////        ParseTransformer transformer = new ParseTransformer();
////        transformer.addLocalStringTransformation("x", match -> "xy");
////        transformer.addLocalStringTransformation("y", match -> "yz");
////
////        String input = "x";
////
////        System.out.println("\nInfinite Loop Detection Test:");
////        System.out.println("Input:    " + input);
////        assertThrows(() -> transformer.transform(input),
////                "Should detect excessive iterations");
////    }
////
////    private void testGlobalAndLocalTransformations() {
////        ParseTransformer transformer = new ParseTransformer();
////        ParseTransformer.addGlobalStringTransformation("global", match -> "GLOBAL");
////        transformer.addLocalStringTransformation("local", match -> "LOCAL");
////
////        String input = "global local 'global local' \"global local\"";
////        String expected = "GLOBAL LOCAL 'global local' \"global local\"";
////
////        System.out.println("\nGlobal and Local Transformations Test:");
////        System.out.println("Input:    " + input);
////        String result = transformer.transform(input);
////        System.out.println("Output:   " + result);
////        assertEquals(expected, result, "Should apply both global and local transformations");
////    }
////
////    private void testEmptyQuotes() {
////        ParseTransformer transformer = new ParseTransformer();
////        transformer.addLocalStringTransformation("test", match -> "TRANSFORMED");
////
////        String input = "test '' \"\" `` 'test' \"test\" `test`";
////        String expected = "TRANSFORMED '' \"\" `` 'test' \"test\" `test`";
////
////        System.out.println("\nEmpty Quotes Test:");
////        System.out.println("Input:    " + input);
////        String result = transformer.transform(input);
////        System.out.println("Output:   " + result);
////        assertEquals(expected, result, "Should handle empty quotes correctly");
////    }
////
////    private void testComplexPatternMatching() {
////        ParseTransformer transformer = new ParseTransformer();
////        transformer.addLocalTransformation(
////                "\\w+\\([^()]*\\)",
////                matchResult -> {
////                    String content = matchResult.group();
////                    String functionName = content.substring(0, content.indexOf('('));
////                    String args = content.substring(content.indexOf('(') + 1, content.length() - 1);
////                    return functionName.toUpperCase() + "[" + args + "]";
////                }
////        );
////
////        String input = "func(x) func(y,z) 'func(a)' \"func(b,c)\"";
////        String expected = "FUNC[x] FUNC[y,z] 'func(a)' \"func(b,c)\"";
////
////        System.out.println("\nComplex Pattern Matching Test:");
////        System.out.println("Input:    " + input);
////        String result = transformer.transform(input);
////        System.out.println("Output:   " + result);
////        assertEquals(expected, result, "Should handle complex pattern matching");
////    }
////
////    private void testMixedQuoteTypes() {
////        ParseTransformer transformer = new ParseTransformer();
////        transformer.addLocalStringTransformation("mix", match -> "MIXED");
////
////        String input = "mix 'mix\"quote`' \"mix'quote`\" `mix'quote\"` ";
////        String expected = "MIXED 'mix\"quote`' \"mix'quote`\" `mix'quote\"` ";
////
////        System.out.println("\nMixed Quote Types Test:");
////        System.out.println("Input:    " + input);
////        String result = transformer.transform(input);
////        System.out.println("Output:   " + result);
////        assertEquals(expected, result, "Should handle mixed quote types correctly");
////    }
//}