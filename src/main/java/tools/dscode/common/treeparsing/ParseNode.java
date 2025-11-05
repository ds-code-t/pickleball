package tools.dscode.common.treeparsing;

import com.google.common.collect.LinkedListMultimap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless grammar node: holds structure (parent/children/phases) and an optional regexPattern.
 * Per-run state lives in MatchNode. Parsing is recursive, order-of-occurrence, regex-only.
 */
public abstract class ParseNode {

    public static final char TOKEN_L = '\uF115';
    public static final char TOKEN_R = '\uF116';

    public final String keyName;
    public final String keySuffix;
    public final ParseNode parent;
    public final List<ParseNode> children;
    public final List<ParseNode> phaseNodes;
    public final String regexPattern;

    protected ParseNode(String keyName,
                        String keySuffix,
                        ParseNode parent,
                        List<ParseNode> children,
                        List<ParseNode> phaseNodes,
                        String regexPattern) {
        this.keyName = Objects.requireNonNull(keyName, "keyName");
        this.keySuffix = keySuffix;
        this.parent = parent;
        this.children = children == null ? List.of() : List.copyOf(children);
        this.phaseNodes = phaseNodes == null ? List.of() : List.copyOf(phaseNodes);
        this.regexPattern = regexPattern;
    }

    public String getRegexPattern() {
        if (regexPattern != null) return regexPattern;
        if (children.isEmpty()) return null;
        // Build alternation from children EXCEPT the special "unmatched" filler child
        String alt = alternationOfChildren(this);
        return alt;
    }

    public String onCapture(String captured) { return captured; }
    public void beforeCapture(MatchNode self) { }
    public void afterCapture(MatchNode self) { }

    public String getUniqueKey(int matchCount) {
        return keyName + (keySuffix == null || keySuffix.isBlank() ? "" : "_" + keySuffix) + "#" + matchCount;
    }

    public MatchNode createMatchNode(ParseNode node,
                                     MatchNode parent,
                                     String originalText,
                                     String token,
                                     LinkedListMultimap<String,Object> globalState) {
        return new MatchNode(node, parent, originalText, token, globalState);
    }

    /* ======================= New public entry points ======================= */

    public PhaseTrace initiateParsing(String originalText) {
        return initiateParsing(originalText, (LinkedListMultimap<String,Object>) null);
    }

    public PhaseTrace initiateParsing(String originalText,
                                      LinkedListMultimap<String,Object> seedState) {
        Objects.requireNonNull(originalText, "originalText");
        detectPhaseCycles(this);

        var ctx = new Context();
        var global = LinkedListMultimap.<String,Object>create();

        String topToken = tokenFor(this, ctx.bump(this));
        MatchNode top = this.createMatchNode(this, null, originalText, topToken, global);

        if (seedState != null) {
            seedState.entries().forEach(e -> top.stateMap.put(e.getKey(), e.getValue()));
        }

        processNode(top, ctx);

        String output = top.unmasked();
        return new PhaseTrace(top, output, List.copyOf(top.phaseRuns));
    }

    public PhaseTrace initiateParsing(String originalText,
                                      Map<String,Object> seedMap) {
        LinkedListMultimap<String,Object> mm = null;
        if (seedMap != null && !seedMap.isEmpty()) {
            mm = LinkedListMultimap.create();
            for (var e : seedMap.entrySet()) mm.put(e.getKey(), e.getValue());
        }
        return initiateParsing(originalText, mm);
    }

    /* ============================ Engine ============================ */

    private static void processNode(MatchNode m, Context ctx) {
        m.parseNode.beforeCapture(m);
        m.modifiedText = m.parseNode.onCapture(m.originalText);

        // Optional unmatched filler child (NOT part of alternation)
        ParseNode unmatchedChild = findUnmatchedChild(m.parseNode);

        String alt = alternationOfChildren(m.parseNode); // excludes unmatched
        if (alt == null) {
            // No explicit children: leaf
            m.maskedText = m.modifiedText;
        } else {
            Pattern p = Pattern.compile(alt, Pattern.DOTALL);
            Matcher matcher = p.matcher(m.modifiedText);
            StringBuilder buf = new StringBuilder();
            int cursor = 0;

            while (matcher.find()) {
                int start = matcher.start();
                int end   = matcher.end();

                // Fill gap BEFORE the match with an 'unmatched' node (if configured)
                if (unmatchedChild != null && cursor < start) {
                    appendGapAsUnmatched(m, unmatchedChild, m.modifiedText.substring(cursor, start), buf, ctx);
                } else if (unmatchedChild == null && cursor < start) {
                    // Default behavior: keep literal text (legacy behavior)
                    buf.append(m.modifiedText, cursor, start);
                }

                // Identify which explicit child matched
                int idx = matchedChildIndex(matcher, explicitChildCount(m.parseNode));
                if (idx < 0) {
                    // Shouldn't happen with our alternation; skip defensively
                    cursor = end;
                    continue;
                }
                ParseNode childNode = explicitChildAt(m.parseNode, idx);
                String hit = matcher.group(0);

                // Create and recurse child
                String token = tokenFor(childNode, ctx.bump(childNode));
                MatchNode child = childNode.createMatchNode(childNode, m, hit, token, m.globalState);
                processNode(child, ctx);

                // Append token instead of literal hit
                buf.append(token);
                linkAndTrackChild(m, child);
                cursor = end;
            }

            // Tail gap after the final match
            if (unmatchedChild != null && cursor < m.modifiedText.length()) {
                appendGapAsUnmatched(m, unmatchedChild, m.modifiedText.substring(cursor), buf, ctx);
            } else if (unmatchedChild == null && cursor < m.modifiedText.length()) {
                buf.append(m.modifiedText, cursor, m.modifiedText.length());
            }

            m.maskedText = buf.toString();
        }

        // Resolve recursively
        String resolved = m.unmasked();

        // Phase chain over resolved span (seed root state into each phase)
        if (!m.parseNode.phaseNodes.isEmpty()) {
            MatchNode root = m;
            while (root.parent != null) root = root.parent;
            Map<String,Object> seedForPhases = new HashMap<>();
            root.stateMap.asMap().forEach((k, vs) -> { if (!vs.isEmpty()) seedForPhases.put(k, vs.iterator().next()); });

            for (int i = 0; i < m.parseNode.phaseNodes.size(); i++) {
                ParseNode phaseRoot = m.parseNode.phaseNodes.get(i);
                PhaseTrace run = phaseRoot.initiateParsing(resolved, seedForPhases);
                var pr = new PhaseRun(phaseRoot, resolved, run.topLevelRootMatch(), run.topLevelOutput(), i);
                m.phaseRuns.add(pr);
                resolved = run.topLevelOutput();
            }
            m.maskedText = resolved;
        }

        m.parseNode.afterCapture(m);
    }

    /* ===== Helpers: unmatched filler, alternation excluding it, sibling links ===== */

    private static ParseNode findUnmatchedChild(ParseNode node) {
        for (ParseNode c : node.children) {
            if (c instanceof UnmatchedNode) return c;
            if ("unmatched".equals(c.keyName)) return c;
        }
        return null;
    }

    private static int explicitChildCount(ParseNode node) {
        int n = 0;
        for (ParseNode c : node.children) {
            if (!(c instanceof UnmatchedNode) && !"unmatched".equals(c.keyName)) n++;
        }
        return n;
    }

    private static ParseNode explicitChildAt(ParseNode node, int index) {
        int i = 0;
        for (ParseNode c : node.children) {
            if ((c instanceof UnmatchedNode) || "unmatched".equals(c.keyName)) continue;
            if (i == index) return c;
            i++;
        }
        throw new IndexOutOfBoundsException("Explicit child index out of range: " + index);
    }

    private static String alternationOfChildren(ParseNode node) {
        // Build alternation from explicit children only (exclude unmatched filler)
        List<ParseNode> list = node.children;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        int gi = 0;
        for (ParseNode c : list) {
            if ((c instanceof UnmatchedNode) || "unmatched".equals(c.keyName)) continue;
            String pat = c.getRegexPattern();
            if (pat == null) continue;
            if (first) {
                sb.append("(?:");
                first = false;
            } else {
                sb.append('|');
            }
            sb.append("(?<").append(groupName(gi)).append(">").append(pat).append(")");
            gi++;
        }
        if (first) return null; // no explicit children
        sb.append(')');
        return sb.toString();
    }

    private static void appendGapAsUnmatched(MatchNode parent,
                                             ParseNode unmatchedChild,
                                             String gapText,
                                             StringBuilder buf,
                                             Context ctx) {
        String token = tokenFor(unmatchedChild, ctx.bump(unmatchedChild));
        MatchNode gap = unmatchedChild.createMatchNode(unmatchedChild, parent, gapText, token, parent.globalState);
        // Leaf: no recursion (unless you give UnmatchedNode its own children)
        gap.modifiedText = unmatchedChild.onCapture(gap.originalText);
        gap.maskedText = gap.modifiedText;
        buf.append(token);
        linkAndTrackChild(parent, gap);
    }

    private static void linkAndTrackChild(MatchNode parent, MatchNode child) {
        // sibling links
        if (!parent.children.isEmpty()) {
            MatchNode prev = parent.children.get(parent.children.size() - 1);
            prev.nextSibling = child;
            child.previousSibling = prev;
        }
        parent.children.add(child);
        parent.matchMap.put(child.token, child);
    }

    private static int matchedChildIndex(Matcher m, int explicitCount) {
        for (int i = 0; i < explicitCount; i++) {
            if (m.group(groupName(i)) != null) return i;
        }
        return -1;
    }

    private static String groupName(int i) { return "G" + i; }

    private static String tokenFor(ParseNode n, int count) {
        return new StringBuilder().append(TOKEN_L).append(n.getUniqueKey(count)).append(TOKEN_R).toString();
    }

    /* ===== Phase cycle guard and run context (unchanged) ===== */

    private static void detectPhaseCycles(ParseNode root) {
        Set<ParseNode> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ParseNode> visited  = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<ParseNode> stack = new ArrayDeque<>();
        dfs(root, visiting, visited, stack);
    }

    private static void dfs(ParseNode n, Set<ParseNode> visiting, Set<ParseNode> visited, Deque<ParseNode> stack) {
        if (visited.contains(n)) return;
        if (!visiting.add(n)) {
            List<String> cycle = new ArrayList<>();
            for (ParseNode x : stack) cycle.add(x.keyName);
            cycle.add(n.keyName);
            throw new IllegalArgumentException("Cycle in phaseNodes: " + String.join("->", cycle));
        }
        stack.push(n);
        for (ParseNode p : n.phaseNodes) dfs(p, visiting, visited, stack);
        stack.pop();
        visiting.remove(n);
        visited.add(n);
        for (ParseNode c : n.children) dfs(c, visiting, visited, new ArrayDeque<>());
    }

    private static final class Context {
        private final IdentityHashMap<ParseNode, Integer> counters = new IdentityHashMap<>();
        int bump(ParseNode n) {
            int next = counters.getOrDefault(n, 0) + 1;
            counters.put(n, next);
            return next;
        }
    }
}
