package tools.dscode.common.treeparsing;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Multiple matches per ParseNode + phases at two levels.
 */
public class Main {

    /* ---------------------- External mutable object ---------------------- */
    public static final class Stats {
        public int tagCount;
        public int numCount;
        public int evenCount; // numbers marked even by phase
        @Override public String toString() {
            return "Stats{tagCount=" + tagCount + ", numCount=" + numCount + ", evenCount=" + evenCount + "}";
        }
    }

    /* ---------------------- Helpers ---------------------- */
    private static Stats getStatsFromRoot(MatchNode self) {
        MatchNode root = self;
        while (root.parent != null) root = root.parent;
        var vals = root.stateMap.get("stats");
        return vals.isEmpty() ? null : (Stats) vals.get(0);
    }
    private static String indent(String s) { return "  " + s.replace("\n", "\n  "); }
    private static void assertStacked(String name, String expectedText, String actualText,
                                      int expTags, int actTags,
                                      int expNums, int actNums,
                                      int expEvens, int actEvens) {
        boolean pass = Objects.equals(expectedText, actualText)
                && expTags == actTags && expNums == actNums && expEvens == actEvens;

        System.out.println();
        System.out.println("=== " + name + " ===");
        System.out.println("Text — Expected:\n" + indent(expectedText));
        System.out.println("Text — Actual:\n"   + indent(actualText));
        System.out.println("Stats — Expected:");
        System.out.println(indent("tagCount=" + expTags + ", numCount=" + expNums + ", evenCount=" + expEvens));
        System.out.println("Stats — Actual:");
        System.out.println(indent("tagCount=" + actTags + ", numCount=" + actNums + ", evenCount=" + actEvens));
        System.out.println("Result: " + (pass ? "[PASS]" : "[FAIL]"));
    }

    /* ---------------------- Grammar: many matches per node ---------------------- */

    /** Matches hashtags: #word ; counts tags; emits <tag:lower>. */
    static final class TagNode extends ParseNode {
        private static final String P = "#[A-Za-z0-9_]+";
        TagNode() { super("TAG", "", null, List.of(), List.of(), P); }
        @Override public void beforeCapture(MatchNode self) {
            Stats s = getStatsFromRoot(self);
            if (s != null) s.tagCount++;
        }
        @Override public String onCapture(String captured) {
            String inner = captured.substring(1);
            return "<tag:" + inner.toLowerCase() + ">";
        }
    }

    /** Matches numbers: \b\d+\b ; counts; pads 1-digit; emits [num:XX]. */
    static final class NumberNode extends ParseNode {
        private static final String P = "\\b\\d+\\b";
        NumberNode(List<ParseNode> phases) { super("NUM", "", null, List.of(), phases, P); }
        @Override public void beforeCapture(MatchNode self) {
            Stats s = getStatsFromRoot(self);
            if (s != null) s.numCount++;
        }
        @Override public String onCapture(String captured) {
            String digits = captured.length() == 1 ? "0" + captured : captured;
            return "[num:" + digits + "]";
        }
    }

    /** Top-level delegator; children: TAG | NUM; hosts global phases. */
    static final class TopNode extends ParseNode {
        TopNode(List<ParseNode> children, List<ParseNode> phases) {
            super("TOP", "", null, children, phases, null);
        }
    }

    /* ---------------------- Phase containers with replacer leaves ---------------------- */

    /** Tiny utility: a leaf that replaces any match with onCapture(...) */
    static abstract class ReplacerLeaf extends ParseNode {
        protected ReplacerLeaf(String key, String pattern) { super(key, "", null, List.of(), List.of(), pattern); }
        @Override public abstract String onCapture(String captured);
    }

    /** Per-number phase: [num:NN...] -> append <even> when even */
    static final class NumberEvenPhase extends ParseNode {
        NumberEvenPhase() {
            super("NUM_EVEN", "", null,
                    List.of(new ReplacerLeaf("NUM_LEAF", "\\[num:(\\d+)\\]") {
                        @Override public String onCapture(String c) {
                            int start = c.indexOf(':') + 1, end = c.indexOf(']');
                            String digits = c.substring(start, end);
                            boolean even = digits.charAt(digits.length() - 1) % 2 == 0;
                            return even ? "[num:" + digits + "]<even>" : c;
                        }
                        @Override public void afterCapture(MatchNode self) {
                            if (self.modifiedText.endsWith("<even>")) {
                                Stats s = getStatsFromRoot(self);
                                if (s != null) s.evenCount++;
                            }
                        }
                    }),
                    List.of(), null);
        }
    }

    /** Global phase: collapse multiple spaces -> one space. */
    static final class CollapseSpacesPhase extends ParseNode {
        CollapseSpacesPhase() {
            super("COLLAPSE_SPACES", "", null,
                    List.of(new ReplacerLeaf("SPACES", "\\s{2,}") {
                        @Override public String onCapture(String c) { return " "; }
                    }),
                    List.of(), null);
        }
    }

    /** Global phase: collapse "!!!" -> "!" */
    static final class CollapseExclamPhase extends ParseNode {
        CollapseExclamPhase() {
            super("COLLAPSE_BANG", "", null,
                    List.of(new ReplacerLeaf("BANGS", "!{2,}") {
                        @Override public String onCapture(String c) { return "!"; }
                    }),
                    List.of(), null);
        }
    }

    /** Global phase: convert <tag:foo> -> <TAG:FOO> (after all other work) */
    static final class TagUpperPhase extends ParseNode {
        TagUpperPhase() {
            super("TAG_UPPER", "", null,
                    List.of(new ReplacerLeaf("TAG_LEAF", "<tag:([a-z0-9_]+)>") {
                        @Override public String onCapture(String c) {
                            String inner = c.substring(5, c.length() - 1);
                            return "<TAG:" + inner.toUpperCase() + ">";
                        }
                    }),
                    List.of(), null);
        }
    }

    /* ---------------------- Demo ---------------------- */

    public static void main(String[] args) {
        // Build phases
        ParseNode numEven       = new NumberEvenPhase();     // per-number
        ParseNode collapseSpace = new CollapseSpacesPhase(); // global
        ParseNode collapseBang  = new CollapseExclamPhase(); // global
        ParseNode tagUpper      = new TagUpperPhase();       // global

        // Children that will each produce MULTIPLE matches
        ParseNode numberNode = new NumberNode(List.of(numEven));
        ParseNode tagNode    = new TagNode();

        // Top with children and ALL global phases (no mutation after)
        ParseNode top = new TopNode(
                List.of(tagNode, numberNode),
                List.of(collapseSpace, collapseBang, tagUpper)
        );

        // Seed external stats
        Stats stats = new Stats();
        Map<String, Object> seed = Map.of("stats", stats);

        String input =
                "Tags #one #Two #three and   numbers 1 23 456!!! Wow!!! #last 7?!";

        String expected =
                "Tags <TAG:ONE> <TAG:TWO> <TAG:THREE> and numbers [num:01] [num:23] [num:456]<even>! Wow! <TAG:LAST> [num:07]?!";

        // Run (requires your earlier phase-state propagation fix)
        PhaseTrace trace = top.initiateParsing(input, seed);

        // Actual
        String actual = trace.topLevelOutput();
        int actTags   = stats.tagCount;  // 4 tags
        int actNums   = stats.numCount;  // 4 numbers
        int actEvens  = stats.evenCount; // 1 even (456)

        // Expected stats
        int expTags = 4, expNums = 4, expEvens = 1;

        assertStacked("Multiple matches per ParseNode + Two-level Phases",
                expected, actual,
                expTags, actTags,
                expNums, actNums,
                expEvens, actEvens);
    }
}
