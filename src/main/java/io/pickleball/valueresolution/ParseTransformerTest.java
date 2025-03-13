package io.pickleball.valueresolution;



class ParseTransformerTest {
    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            System.err.println(String.format("✗ FAILED: %s%nExpected: %s%nActual: %s%n",
                    message, expected, actual));
            return;
        }
        System.out.println("✓ " + message);
    }

    public static void main(String[] args) {
        ParseTransformerTest test = new ParseTransformerTest();
        test.testMixedCaptureGroups();
        test.testStringTransformation();
        test.testSwapQuotes();
        test.testDuplicateQuote();
    }

    private void testMixedCaptureGroups() {
        ParseTransformer transformer = new ParseTransformer();
        transformer.addLocalTransformation(
                "(?<type>[A-Z]\\w+)?\\s*(?<name>[a-z]\\w*)\\s*=\\s*(\\u2405[\\u2404]+\\u2405)(?<space>\\s*)",
                match -> {
                    String type = match.group("type");
                    String name = match.group("name");
                    String space = match.group("space");
                    String masked = match.group(3);  // The full masked quote section
                    return (type != null ? type + " " : "") +
                            name.toUpperCase() + " = " + masked + space;
                }
        );

        String input = "name = 'John' String age = \"25\" count = `10`";
        String expected = "NAME = 'John' String AGE = \"25\" COUNT = `10`";

        System.out.println("\nMixed Capture Groups Test:");
        System.out.println("Input:    " + input);
        String result = transformer.transform(input);
        System.out.println("Output:   " + result);
        assertEquals(expected, result, "Mixed named and numbered capture groups should work correctly");
    }

    private void testStringTransformation() {
        ParseTransformer transformer = new ParseTransformer();
        transformer.addLocalStringTransformation(
                "hello\\s+" + ParseTransformer.MASKED_QUOTE_PATTERN,
                match -> "greeting" + match.substring(5)
        );

        String input = "hello 'world' goodbye \"universe\"";
        String expected = "greeting 'world' goodbye \"universe\"";

        System.out.println("\nString Transformation Test:");
        System.out.println("Input:    " + input);
        String result = transformer.transform(input);
        System.out.println("Output:   " + result);
        assertEquals(expected, result, "Simple string transformation should work");
    }

    private void testSwapQuotes() {
        ParseTransformer transformer = new ParseTransformer();
        transformer.addLocalTransformation(
                "(" + ParseTransformer.MASKED_QUOTE_PATTERN + ")\\s+(" + ParseTransformer.MASKED_QUOTE_PATTERN + ")",
                match -> match.group(2) + " " + match.group(1)
        );

        String input = "Swapping 'first' \"second\"";
        String expected = "Swapping \"second\" 'first'";

        System.out.println("\nQuote Swap Test:");
        System.out.println("Input:    " + input);
        String result = transformer.transform(input);
        System.out.println("Output:   " + result);
        assertEquals(expected, result, "Quote swapping should work");
    }

    private void testDuplicateQuote() {
        ParseTransformer transformer = new ParseTransformer();
        transformer.addLocalTransformation(
                "duplicate\\s+(" + ParseTransformer.MASKED_QUOTE_PATTERN + ")",
                match -> "duplicated " + match.group(1) + " " + match.group(1)
        );

        String input = "duplicate 'test' other \"text\"";
        String expected = "duplicated 'test' 'test' other \"text\"";

        System.out.println("\nQuote Duplication Test:");
        System.out.println("Input:    " + input);
        String result = transformer.transform(input);
        System.out.println("Output:   " + result);
        assertEquals(expected, result, "Quote duplication should work");
    }
}