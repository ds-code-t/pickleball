//package tools.dscode.cucumberextended.dynamicparsing;
//
//import java.util.List;
//
//public class Main {
//    public static void main(String[] args) {
//        // Test 1: default delimiters (',', ';', ':', '.') with quotes + nested
//        // brackets
//        String input1 = """
//                hello, "quoted, inner; ignored": world.
//                'single, inner', backtick: `semi;ignored`, '''triple;ignored''', end;
//                mix (a, (b; c)), and {d: [e.f, g]}; <h(i), {j[k]}>: done.
//                """;
//        runDemo("Default delimiters", new Sentence(input1));
//
//        // Test 2: custom delimiters (only '|'), verify others are ignored for
//        // splitting
//        String input2 = """
//                A|B,C;D:E.F | 'x,y' | [z;w] | (u.v)
//                """;
//        runDemo("Custom delimiters: '|'", new Sentence(input2, List.of('|')));
//    }
//
//    private static void runDemo(String title, Sentence sentence) {
//        System.out.println("=== " + title + " ===");
//        System.out.println("Original:");
//        System.out.println(sentence.original());
//        System.out.println("--- After Quote masking ---");
//        System.out.println(sentence.quoteMasker().masked());
//        System.out.println("--- After Bracket masking ---");
//        System.out.println(sentence.bracketMasker().masked());
//
//        System.out.println("--- Phrases ---");
//        int i = 1;
//        for (Phrase p : sentence) {
//            String delim = p.delimiter().map(Object::toString).orElse("<none>");
//            System.out.printf("[%02d] text=\"%s\"  delimiter=%s%n", i++, p.text().trim(), delim);
//        }
//
//        System.out.println("--- Rejoined (proof of round-trip) ---");
//        StringBuilder rebuilt = new StringBuilder();
//        for (Phrase p : sentence) {
//            rebuilt.append(p.text());
//            p.delimiter().ifPresent(rebuilt::append);
//        }
//        System.out.println(rebuilt);
//        System.out.println();
//    }
//}
