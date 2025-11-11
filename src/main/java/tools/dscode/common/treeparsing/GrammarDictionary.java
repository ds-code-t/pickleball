package tools.dscode.common.treeparsing;

/** A small palette of nodes used by tests. */
public class GrammarDictionary extends NodeDictionary {

    /* ---------- Parsing (row-0) leaves ---------- */

    // Double-quoted string → <q>content</q>
    ParseNode DQ = new ParseNode() {
        String regex = "\"(?:\\\\.|[^\"\\\\])*\"";
        @Override public String onCapture(String s) {
            String inner = s.length() >= 2 ? s.substring(1, s.length()-1) : s;
            return "<q>" + inner + "</q>";
        }
    };

    // [brackets] → (BR:content)
    ParseNode BR = new ParseNode() {
        String regex = "\\[(?:\\\\.|[^\\]\\\\])*]";
        @Override public String onCapture(String s) {
            String inner = s.length() >= 2 ? s.substring(1, s.length()-1) : s;
            return "(BR:" + inner + ")";
        }
    };

    // Simple phone 123-4567 → 1234567
    ParseNode Phone = new ParseNode() {
        String regex = "\\b\\d\\d\\d-\\d\\d\\d\\d\\b";
        @Override public String onCapture(String s) { return s.replace("-", ""); }
    };

    // Any punctuation .,!? (identity)
    ParseNode Punct = new ParseNode() {
        String regex = "[.,!?]";
    };

    /* ---------- Phase helpers (rows ≥1) ---------- */

    // Collapse 2+ spaces → single space
    ParseNode CollapseSpaces = new ParseNode() {
        String regex = "\\s{2,}";
        @Override public String onCapture(String s) { return " "; }
    };

    // Collapse '!!...' → '!'
    ParseNode CollapseBangs = new ParseNode() {
        String regex = "!{2,}";
        @Override public String onCapture(String s) { return "!"; }
    };

    // wow → WOW (word boundary)
    ParseNode UpperWOW = new ParseNode() {
        String regex = "\\bwow\\b";
        @Override public String onCapture(String s) { return "WOW"; }
    };

    // Count words (letters/digits/underscore). Identity transform but bumps global counter.
    ParseNode CountWords = new ParseNode() {
        String regex = "\\b[\\p{L}\\p{N}_]+\\b";
        @Override public void beforeCapture(MatchNode self) {
            var g = self.globalState;
            var cur = g.get("wordCount");
            int x = 0;
            if (cur != null && !cur.isEmpty() && cur.get(0) instanceof Integer i) x = i;
            g.removeAll("wordCount"); g.put("wordCount", x + 1);
        }
    };
}
