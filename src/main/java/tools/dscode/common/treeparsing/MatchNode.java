package tools.dscode.common.treeparsing;

import com.google.common.collect.LinkedListMultimap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Per-match, per-run state container. Minimal logic; recursion does the work.
 */
public final class MatchNode {

    public final ParseNode parseNode;
    public final MatchNode parent; // nullable

    public final String originalText;   // exact group(0)
    public String modifiedText;         // onCapture(originalText)
    public String maskedText;           // modifiedText with child tokens substituted
    public final String token;          // ⟦unique⟧

    public final List<MatchNode> children = new ArrayList<>();
    public final LinkedListMultimap<String, MatchNode> matchMap = LinkedListMultimap.create();

    // NEW: sibling pointers (within the same parent.children list)
    public MatchNode previousSibling;   // nullable
    public MatchNode nextSibling;       // nullable

    // Scratch state maps (per-match and global shared-by-reference)
    public final LinkedListMultimap<String, Object> stateMap = LinkedListMultimap.create();
    public final LinkedListMultimap<String, Object> globalState;

    public final List<PhaseRun> phaseRuns = new ArrayList<>();

    public MatchNode(ParseNode parseNode,
                     MatchNode parent,
                     String originalText,
                     String token,
                     LinkedListMultimap<String,Object> globalState) {
        this.parseNode = Objects.requireNonNull(parseNode, "parseNode");
        this.parent = parent;
        this.originalText = Objects.requireNonNull(originalText, "originalText");
        this.token = Objects.requireNonNull(token, "token");
        this.globalState = Objects.requireNonNull(globalState, "globalState");
        this.modifiedText = originalText;
        this.maskedText = originalText;
    }

    /** Recursively resolve: replace each child token (in appearance order) with child.unmasked(). */
    public String unmasked() {
        String out = maskedText;
        for (MatchNode child : children) {
            out = out.replace(child.token, child.unmasked());
        }
        return out;
    }
}
