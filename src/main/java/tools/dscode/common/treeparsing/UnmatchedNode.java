package tools.dscode.common.treeparsing;

import java.util.List;

/**
 * Fills any text gaps not matched by explicit children.
 * Convention: keyName = "unmatched". Not used in regex alternation; the engine
 * synthesizes MatchNodes for gaps when this node is present among a parent's children.
 *
 * onCapture = identity by default (you may override to transform unmatched text globally).
 */
public class UnmatchedNode extends ParseNode {
    public UnmatchedNode() {
        super("unmatched", "", null, List.of(), List.of(), null);
    }
}
