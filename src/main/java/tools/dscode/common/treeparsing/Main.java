package tools.dscode.common.treeparsing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) {
        System.out.println("Running NodeDictionary/ParseNode integration tests …\n");

        testQuotesAndBrackets();
        testOrderOfOccurrence();
        testPhasesChainWordCount();
        testImplicitUnmatchedAndSiblings();

        System.out.println("\nAll tests done.");
    }

    /* ---------------------------- Tests ---------------------------- */

    private static void testQuotesAndBrackets() {
        System.out.println("=== Quotes + Brackets (row-0 parsing with implicit-unmatched) ===");
        var dict = new GrammarDictionary();
        var top = dict.buildFromYaml("""
Top:
  - [DQ, BR]
""");
        String input = "Hello \"world\" and [box]!";
        String expected = "Hello <q>world</q> and (BR:box)!";
        var trace = top.initiateParsing(input);
        String actual = trace.topLevelOutput();

        printBlock("Input", input);
        printBlock("Expected", expected);
        printBlock("Actual", actual);
        assertPrint(expected.equals(actual), "Output equals expected");

        // Show top child sequence types to prove implicit unmatched tiling
        var kids = trace.topLevelRootMatch().children();
        List<String> types = new ArrayList<>();
        for (var k : kids) types.add(k.parseNode.keyName);
        System.out.println("Child types (in order): " + types);
        assertPrint(types.size() >= 3, "Tiling produced unmatched + DQ + BR segments");
        System.out.println();
    }

    private static void testOrderOfOccurrence() {
        System.out.println("=== Order of occurrence ===");
        var dict = new GrammarDictionary();
        var top = dict.buildFromYaml("""
Top:
  - [DQ, BR]
""");
        String input = "\"a\"[b]\"c\"";
        String expected = "<q>a</q>(BR:b)<q>c</q>";
        var trace = top.initiateParsing(input);
        String actual = trace.topLevelOutput();

        printBlock("Input", input);
        printBlock("Expected", expected);
        printBlock("Actual", actual);
        assertPrint(expected.equals(actual), "Flat alternation preserves left→right order");
        System.out.println();
    }

    private static void testPhasesChainWordCount() {
        System.out.println("=== Phases chain: CountWords + CollapseSpaces + CollapseBangs + UpperWOW ===");
        var dict = new GrammarDictionary();
        var top = dict.buildFromYaml("""
Top:
  - []
  - [CountWords, CollapseSpaces, CollapseBangs, UpperWOW]
""");
        String input = "This   is wow!! Right?";
        String expected = "This is WOW! Right?";

        var trace = top.initiateParsing(input, Map.of()); // no seed needed
        String actual = trace.topLevelOutput();

        printBlock("Input", input);
        printBlock("Expected", expected);
        printBlock("Actual", actual);
        assertPrint(expected.equals(actual), "Phase output equals expected");

        // Fetch the wordCount from the top-level root's globalState
        var root = trace.topLevelRootMatch();
        var g = root.globalState();
        int wordCount = 0;
        if (g.get("wordCount") != null && !g.get("wordCount").isEmpty() && g.get("wordCount").get(0) instanceof Integer i) {
            wordCount = i;
        }
        printKV("wordCount (expected)", "4");
        printKV("wordCount (actual)  ", String.valueOf(wordCount));
        assertPrint(wordCount == 4, "CountWords phase counted 4 words");
        System.out.println();
    }

    private static void testImplicitUnmatchedAndSiblings() {
        System.out.println("=== Implicit unmatched & sibling links ===");
        var dict = new GrammarDictionary();
        var top = dict.buildFromYaml("""
Top:
  - [DQ]
""");
        String input = "aaa \"X\" bbb";
        var trace = top.initiateParsing(input);

        var kids = trace.topLevelRootMatch().children();
        printKV("Child count (expected)", "3");
        printKV("Child count (actual)  ", String.valueOf(kids.size()));
        assertPrint(kids.size() == 3, "Tiled as unmatched, DQ, unmatched");

        // Check sibling pointers
        var a = kids.get(0), b = kids.get(1), c = kids.get(2);
        assertPrint(a.previousSibling() == null, "First.previousSibling == null");
        assertPrint(a.nextSibling() == b, "First.nextSibling == second");
        assertPrint(b.previousSibling() == a, "Second.previousSibling == first");
        assertPrint(b.nextSibling() == c, "Second.nextSibling == third");
        assertPrint(c.nextSibling() == null, "Third.nextSibling == null");

        System.out.println();
    }

    /* ---------------------------- tiny assert+print helpers ---------------------------- */

    private static void printBlock(String title, String text) {
        System.out.println(title + ":\n  " + text);
    }
    private static void printKV(String k, String v) {
        System.out.println(k + "  " + v);
    }
    private static void assertPrint(boolean ok, String label) {
        System.out.println("Result: " + (ok ? "[PASS] " : "[FAIL] ") + label);
    }
}
