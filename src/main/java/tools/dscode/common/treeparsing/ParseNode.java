package tools.dscode.common.treeparsing;

import com.google.common.collect.LinkedListMultimap;
import org.intellij.lang.annotations.Language;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.common.treeparsing.MatchNode.createMatchNode;
import static tools.dscode.common.treeparsing.RegexUtil.TOKEN_END;
import static tools.dscode.common.treeparsing.RegexUtil.TOKEN_START;
import static tools.dscode.common.util.DebugUtils.printDebug;


public class ParseNode {


    private String keyName;         // pretty/debug name (also used in tokens)
    private String keySuffix;       // optional suffix
    String selfRegex;       // null => this node doesn't match by itself
    boolean useRegexTemplating = true;
    /**
     * Ordered children; each is a sequential pass over this node's maskedText.
     */
    public final List<ParseNode> parseChildren = new ArrayList<>();

    // ----- ctors -----
    public ParseNode() {
        this(null, null, null, null, false);
    }

    public ParseNode(@Language("RegExp") String regex) {
        this(null, null, null, regex, true);
    }

    public ParseNode(@Language("RegExp") String regex, boolean useRegexTemplating) {
        this(null, null, null, regex, useRegexTemplating);

    }

    private ParseNode(String keyName, String keySuffix, List<ParseNode> children, @Language("RegExp") String regex, boolean useRegexTemplating) {
        this.useRegexTemplating = useRegexTemplating;
        this.keyName = keyName;
        this.keySuffix = keySuffix;
        this.selfRegex = regex;
        if (children != null) this.parseChildren.addAll(children);
    }

    // ----- identity / naming -----
    public String getName() {
        return (keyName != null) ? keyName : getClass().getSimpleName();
    }

    public ParseNode setName(String name) {
        this.keyName = name;
        return this;
    }

    public String getSuffix() {
        return keySuffix;
    }

    public ParseNode setSuffix(String suffix) {
        this.keySuffix = suffix;
        return this;
    }

    private String keyBase() {
        String base = getName();
        if (keySuffix != null && !keySuffix.isBlank()) base = base + "_" + keySuffix;
        return base;
    }

//    /**
//     * Unique key BODY (without sentinels) used inside the token.
//     * Option A: "_<name>_<index>_"
//     */
//    public String getUniqueKey(int index) {
//        return  keyBase() + "_" + index;
//    }


    // ----- regex access -----
    public boolean hasRegex() {
        return selfRegex != null;
    }

    public String getRegexPattern() {
        return selfRegex;
    }

    // ----- hooks -----

    /**
     * Primary hook: capture transform with full access to the MatchNode.
     * Default delegates to onCapture(String).
     */
    public String onCapture(MatchNode self) {
        return onCapture(self.originalText());
    }

    /**
     * Legacy/compat hook: capture transform by raw string. Default: identity.
     */
    public String onCapture(String original) {
        return original;
    }

    /**
     * Decide what replaces this child's match in the parent's masked stream. Default: token.
     */
    public String onSubstitute(MatchNode self) {
        return self.token();
    }

    /**
     * Post-order hook: called after this node's descendants have completed. Default: no-op.
     */
    public void onResolve(MatchNode self) {

    }


    public void descendantsResolved(MatchNode self) {
        self.sortedChildren = new ArrayList<>(self.children.values());
        self.sortedChildren.sort(Comparator.comparingInt(m -> m.start));

        for (int i = 0; i < self.sortedChildren.size(); i++) {
            var n = self.sortedChildren.get(i);
            n.position = i;
            n.previousSibling = (i > 0) ? self.sortedChildren.get(i - 1) : null;
            n.nextSibling = (i + 1 < self.sortedChildren.size()) ? self.sortedChildren.get(i + 1) : null;
        }
        onResolve(self);
    }

    // ----- entry point for parsing -----
    public MatchNode initiateParsing(String input) {
        // Root "captures" the entire input once (no regex groups here)
        MatchNode top = createMatchNode(0, input.length(), this, null, input, null, 0, LinkedListMultimap.create());

        String transformed = onCapture(top);
        top.setModifiedText(transformed);
        top.setMaskedText(transformed);

        // Children run sequentially over top.maskedText
        applyChildrenOverMasked(this, top);

        // Root post-order
        descendantsResolved(top);
        return top;
    }

    // ----- core engine: children scan parent's maskedText -----
    protected void applyChildrenOverMasked(ParseNode parentDef, MatchNode parentMatch) {
        List<Object> skipAllChildren = parentMatch.localState().get("skipAllChildren");
        if (!skipAllChildren.isEmpty() && String.valueOf(skipAllChildren.getLast()).equalsIgnoreCase("true")) return;
        for (ParseNode childDef : parentDef.parseChildren) {
//            Object obj = parentMatch.getFromLocalState("skip:"+childDef.getName());
//            printDebug("@@parentMatch: " + parentMatch.localState());
//            if(obj != null) {
//                printDebug("@@parentMatch: " + parentMatch);
//                printDebug("@@childDef: " + childDef);
//            }
            if (parentMatch.localStateBoolean("skip:" + childDef.getName())) continue;

            String parentSnapshot = parentMatch.maskedText();
            if (parentSnapshot == null) parentSnapshot = "";
            if (!childDef.hasRegex()) continue;

            // Keep the Pattern so we can build NamedGroupMap later
            Pattern pattern = Pattern.compile(childDef.getRegexPattern(), Pattern.DOTALL);
            Matcher m = pattern.matcher(parentSnapshot);

            printDebug("@@-AttemptingMatch: " + pattern + "  ::  " + parentSnapshot);

            StringBuilder newMasked = new StringBuilder(parentSnapshot.length());
            int lastEnd = 0;
            int occurrence = 1;
            while (m.find()) {
                if (m.start() > lastEnd) {
                    newMasked.append(parentSnapshot, lastEnd, m.start());
                }

                String childOriginal = m.group(0);
                // Build token with Option A format
                String childToken = childDef.keyBase();
                // Provide shared global state from parent; localState is fresh.
                MatchNode childMatch = createMatchNode(
                        m.start(), m.end(),
                        childDef,
                        parentMatch,
                        childOriginal,
                        childToken,
                        occurrence++,
                        parentMatch.globalState()
                );

                // Capture and store regex groups for this match
                childMatch.setGroups(new NamedGroupMap(pattern, m.toMatchResult()));

                parentMatch.addChildMatchNode(childMatch);

                // Transform & initialize child texts
                String childTransformed = childDef.onCapture(childMatch);
                childMatch.setModifiedText(childTransformed);
                childMatch.setMaskedText(childTransformed);

                // Recurse to resolve grandchildren over child's masked text
                childDef.applyChildrenOverMasked(childDef, childMatch);

                // Child post-order (its subtree is now complete)
                childDef.descendantsResolved(childMatch);

                // Substitute into parent's masked stream
                String substitute = childDef.onSubstitute(childMatch);
                if (substitute == null) substitute = "";
                newMasked.append(substitute);

                lastEnd = m.end();
            }
            if (lastEnd < parentSnapshot.length()) {
                newMasked.append(parentSnapshot, lastEnd, parentSnapshot.length());
            }
            parentMatch.setMaskedText(newMasked.toString());
        }
    }

    // ===== resolution helper =====

    /**
     * Fully resolves a node's masked text by recursively expanding child tokens.
     */
    protected static String fullyResolve(MatchNode m) {
        String out = (m.maskedText() == null) ? "" : m.maskedText();
        if (out.isEmpty()) return out;

        for (MatchNode c : m.children()) {
            String token = c.token();
            if (token == null || token.isEmpty()) continue;
            String childResolved = fullyResolve(c);
            out = out.replace(token, childResolved);
        }
        return out;
    }

    // ===== regex helpers & toString =====


    public String tokenKeyPattern() {
        // literal prefix up to the digits
        String literalPrefix = TOKEN_START + "_" + keyBase() + "_";
        // literal suffix after the digits
        String literalSuffix = "_" + TOKEN_END;
        // quote only the literal parts, keep \d+ as regex
        return Pattern.quote(literalPrefix) + "\\d+_\\d+" + Pattern.quote(literalSuffix);
    }

    public String definedRegex() {
        return hasRegex() ? getRegexPattern() : "(?!)";
    }

    @Override
    public String toString() {
        return keyName + " " + fullRegex();
    }


    public String fullRegex() {
        String keys = tokenKeyPattern();
        if (!hasRegex()) return keys;
        return "(?:" + definedRegex() + ")|(?:" + keys + ")";
    }
}
