//package tools.dscode.common.treeparsing;
//
//import java.util.List;
//import java.util.Objects;
//
///**
// * Minimal console test for old vs new behavior:
// *
// * OLD: Without UnmatchedNode -> only explicit matches become children; maskedText still contains literals.
// * NEW: With UnmatchedNode    -> gaps become 'unmatched' children; siblings are linked; maskedText is token-only.
// */
//public final class TreeParsingTest {
//
//    public static void main(String[] args) {
//        System.out.println("Running TreeParsingTest…");
//
//        testOldBehavior_noUnmatched();
//        testNewBehavior_withUnmatched();
//
//        System.out.println("\nAll tests finished.");
//    }
//
//    /* ============================= Tests ============================= */
//
//    private static void testOldBehavior_noUnmatched() {
//        // Grammar: TOP -> (DQ | BR)
//        ParseNode dq = new DQNode();
//        ParseNode br = new BRNode();
//        ParseNode top = new TopNode(List.of(dq, br));
//
//        String input    = "Hello \"x\" [y] Z!";
//        String expected = "Hello <q>x</q> (BR:y) Z!";
//
//        PhaseTrace trace = top.initiateParsing(input);
//        MatchNode root   = trace.topLevelRootMatch();
//        String actual    = trace.topLevelOutput();
//
//        // Assertions
//        section("OLD: no UnmatchedNode");
//        assertEq("Output text", expected, actual);
//
//        // Only two explicit children (DQ, BR)
//        assertEq("Child count (only explicit matches)", 2, root.children.size());
//        assertEq("Child[0] type", "DQ", root.children.get(0).parseNode.keyName);
//        assertEq("Child[1] type", "BR", root.children.get(1).parseNode.keyName);
//
//        // MaskedText should still contain literals (no gap-filling)
//        String masked = root.maskedText;
//        assertTrue("maskedText contains literal 'Hello '", masked.contains("Hello "));
//        assertTrue("maskedText contains literal ' Z!'", masked.contains(" Z!"));
//
//        // Sibling links: NOT guaranteed to be contiguous over full text (only between explicit children)
//        // Still, between the two children, links should exist
//        MatchNode c0 = root.children.get(0);
//        MatchNode c1 = root.children.get(1);
//        assertNull("Prev of first explicit child is null", c0.previousSibling);
//        assertSame("Next of first explicit child is second", c1, c0.nextSibling);
//        assertSame("Prev of second explicit child is first", c0, c1.previousSibling);
//        assertNull("Next of second explicit child is null", c1.nextSibling);
//
//        pass();
//    }
//
//    private static void testNewBehavior_withUnmatched() {
//        // Grammar: TOP -> (DQ | BR | unmatched)
//        ParseNode dq = new DQNode();
//        ParseNode br = new BRNode();
//        ParseNode unmatched = new UnmatchedNode(); // new filler
//        ParseNode top = new TopNode(List.of(dq, br, unmatched));
//
//        String input    = "Hello \"x\" [y] Z!";
//        String expected = "Hello <q>x</q> (BR:y) Z!";
//
//        PhaseTrace trace = top.initiateParsing(input);
//        MatchNode root   = trace.topLevelRootMatch();
//        String actual    = trace.topLevelOutput();
//
//        // Assertions
//        section("NEW: with UnmatchedNode");
//        assertEq("Output text", expected, actual);
//
//        // Now every gap is a child: gaps + DQ + gap + BR + gap -> 5 children
//        assertEq("Child count (gaps filled)", 5, root.children.size());
//
//        // Expected tiling:
//        // 0: unmatched("Hello ")
//        // 1: DQ("\"x\"")
//        // 2: unmatched(" ")
//        // 3: BR("[y]")
//        // 4: unmatched(" Z!")
//        assertEq("Child[0] type", "unmatched", root.children.get(0).parseNode.keyName);
//        assertEq("Child[1] type", "DQ",        root.children.get(1).parseNode.keyName);
//        assertEq("Child[2] type", "unmatched", root.children.get(2).parseNode.keyName);
//        assertEq("Child[3] type", "BR",        root.children.get(3).parseNode.keyName);
//        assertEq("Child[4] type", "unmatched", root.children.get(4).parseNode.keyName);
//
//        // Sibling links must form a full double-linked chain across all 5 children
//        for (int i = 0; i < root.children.size(); i++) {
//            MatchNode cur = root.children.get(i);
//            MatchNode prev = (i == 0) ? null : root.children.get(i - 1);
//            MatchNode next = (i == root.children.size() - 1) ? null : root.children.get(i + 1);
//            assertSame("previousSibling at " + i, prev, cur.previousSibling);
//            assertSame("nextSibling at " + i, next, cur.nextSibling);
//        }
//
//        // With unmatched filler, maskedText should be concatenation of tokens only (no raw literals)
//        String masked = root.maskedText;
//        assertFalse("maskedText does NOT contain 'Hello '", masked.contains("Hello "));
//        assertFalse("maskedText does NOT contain ' Z!'", masked.contains(" Z!"));
//
//        // And unmasked reconstruction equals expected (already checked above, but reiterate)
//        assertEq("unmasked() === expected", expected, root.unmasked());
//
//        pass();
//    }
//
//    /* ====================== Concrete grammar nodes ====================== */
//
//    /** Top-level node delegating to children via alternation; no phases needed here. */
//    static final class TopNode extends ParseNode {
//        TopNode(List<ParseNode> children) {
//            super("TOP", "", null, children, List.of(), null);
//        }
//    }
//
//    /** Double-quoted string leaf: " ... " -> <q>...</q> */
//    static final class DQNode extends ParseNode {
//        private static final String P = "\"(?:\\\\.|[^\"\\\\])*\"";
//        DQNode() { super("DQ", "", null, List.of(), List.of(), P); }
//
//        @Override public String onCapture(String captured) {
//            if (captured.length() >= 2 && captured.startsWith("\"") && captured.endsWith("\"")) {
//                String inner = captured.substring(1, captured.length() - 1);
//                return "<q>" + inner + "</q>";
//            }
//            return captured;
//        }
//    }
//
//    /** Square-bracketed leaf: [ ... ] -> (BR:...) */
//    static final class BRNode extends ParseNode {
//        private static final String P = "\\[(?:\\\\.|[^\\]\\\\])*\\]";
//        BRNode() { super("BR", "", null, List.of(), List.of(), P); }
//
//        @Override public String onCapture(String captured) {
//            if (captured.length() >= 2 && captured.startsWith("[") && captured.endsWith("]")) {
//                String inner = captured.substring(1, captured.length() - 1);
//                return "(BR:" + inner + ")";
//            }
//            return captured;
//        }
//    }
//
//    /* ============================= Tiny assert helpers ============================= */
//
//    private static void section(String title) {
//        System.out.println();
//        System.out.println("=== " + title + " ===");
//    }
//
//    private static void pass() {
//        System.out.println("Result: [PASS]");
//    }
//
//    private static void assertEq(String msg, Object exp, Object act) {
//        if (!Objects.equals(exp, act)) {
//            fail(msg + " — expected:\n  " + exp + "\n  actual:\n  " + act);
//        } else {
//            System.out.println(msg + " — OK");
//        }
//    }
//
//    private static void assertTrue(String msg, boolean cond) {
//        if (!cond) fail(msg + " — expected TRUE but was FALSE");
//        else System.out.println(msg + " — OK");
//    }
//
//    private static void assertFalse(String msg, boolean cond) {
//        if (cond) fail(msg + " — expected FALSE but was TRUE");
//        else System.out.println(msg + " — OK");
//    }
//
//    private static void assertNull(String msg, Object obj) {
//        if (obj != null) fail(msg + " — expected NULL but was " + obj);
//        else System.out.println(msg + " — OK");
//    }
//
//    private static void assertSame(String msg, Object exp, Object act) {
//        if (exp != act) {
//            fail(msg + " — expected same reference");
//        } else {
//            System.out.println(msg + " — OK");
//        }
//    }
//
//    private static void fail(String reason) {
//        System.out.println("Result: [FAIL]");
//        throw new AssertionError(reason);
//    }
//}
