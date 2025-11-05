package tools.dscode.common.treeparsing;

/** One sequential phase run (successor) executed over a node's resolved span. */
public record PhaseRun(
        ParseNode parseNode,
        String inputText,
        MatchNode rootMatch,
        String outputText,
        int index
) { }
