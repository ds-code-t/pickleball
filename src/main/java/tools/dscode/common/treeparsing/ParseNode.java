package tools.dscode.common.treeparsing;

import com.google.common.collect.LinkedListMultimap;

import java.lang.reflect.Field;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless grammar node.
 * Row 0 = parse children (alternation). Rows >=1 = sequential successor passes (fresh parses).
 * Implicit unmatched: row 0 tiles parent span with explicit matches + synthesized gap nodes.
 */
public abstract class ParseNode {

    public static final char TOKEN_L = '\uF115';
    public static final char TOKEN_R = '\uF116';

    /** Semantic name; defaults to subclass simple name. */
    public final String keyName;

    /** Optional salt for uniqueness; default "" */
    public final String keySuffix;

    /** 2-D grid: row 0 = parsing children; rows 1..N = phase rows (successor passes). */
    public final List<List<ParseNode>> rows;

    /** Optional leaf pattern if row 0 has no children. Can be provided by subclasses or auto-discovered. */
    protected final String selfRegex;

    /* ---------------------------- Constructors ---------------------------- */

    /** Most concise: subclasses may just declare a String field named 'regex', which will be auto-discovered. */
    protected ParseNode() {
        this(null, "", null, null);
    }

    /** Provide explicit pieces (rows may be null ⇒ empty row0; regex may be null ⇒ try field 'regex'). */
    protected ParseNode(String keyName,
                        String keySuffix,
                        List<List<ParseNode>> rows,
                        String regexPattern) {
        this.keyName = (keyName == null || keyName.isBlank()) ? getClass().getSimpleName() : keyName;
        this.keySuffix = (keySuffix == null) ? "" : keySuffix;
        this.rows = rows == null ? List.of(List.of()) : deepCopy(rows);
        this.selfRegex = (regexPattern != null) ? regexPattern : reflectRegexFieldOrNull(this);
    }

    private static List<List<ParseNode>> deepCopy(List<List<ParseNode>> src) {
        List<List<ParseNode>> out = new ArrayList<>(src.size());
        for (List<ParseNode> r : src) out.add(List.copyOf(r));
        return List.copyOf(out);
    }

    /** If row 0 has children, build alternation of their patterns; else return this node's leaf regex (if any). */
    public String getRegexPattern() {
        if (!rows.isEmpty() && !rows.get(0).isEmpty()) {
            String alt = alternationOf(rows.get(0));
            return alt;
        }
        return selfRegex;
    }

    /* ---------------------------- Hooks (override as needed) ---------------------------- */

    public void beforeCapture(MatchNode self) { }
    public String onCapture(String captured) { return captured; }
    public void afterCapture(MatchNode self) { }

    public String getUniqueKey(int matchCount) {
        return keyName + (keySuffix.isBlank() ? "" : "_" + keySuffix) + "#" + matchCount;
    }

    public MatchNode createMatchNode(ParseNode node,
                                     MatchNode parent,
                                     String originalText,
                                     String token,
                                     LinkedListMultimap<String,Object> globalState) {
        return new MatchNode(node, parent, originalText, token, globalState);
    }

    /* ---------------------------- Entry points ---------------------------- */

    public PhaseTrace initiateParsing(String originalText) {
        return initiateParsing(originalText, (LinkedListMultimap<String,Object>) null);
    }

    public PhaseTrace initiateParsing(String originalText, Map<String,Object> seed) {
        LinkedListMultimap<String,Object> mm = null;
        if (seed != null && !seed.isEmpty()) {
            mm = LinkedListMultimap.create();
            for (var e : seed.entrySet()) mm.put(e.getKey(), e.getValue());
        }
        return initiateParsing(originalText, mm);
    }

    public PhaseTrace initiateParsing(String originalText,
                                      LinkedListMultimap<String,Object> seedState) {
        Objects.requireNonNull(originalText, "originalText");

        var ctx = new Context();
        var global = LinkedListMultimap.<String,Object>create();

        String topToken = tokenFor(this, ctx.bump(this));
        MatchNode top = this.createMatchNode(this, null, originalText, topToken, global);
        if (seedState != null) seedState.entries().forEach(e -> top.stateMap.put(e.getKey(), e.getValue()));

        processNode(top, ctx);

        String output = top.unmasked();
        return new PhaseTrace(top, output, List.copyOf(top.phaseRuns));
    }

    /* ---------------------------- Engine ---------------------------- */

    private static void processNode(MatchNode m, Context ctx) {
        m.parseNode.beforeCapture(m);
        m.modifiedText = m.parseNode.onCapture(m.originalText);

        // Parent vs leaf
        boolean hasRow0 = !m.parseNode.rows.isEmpty() && !m.parseNode.rows.get(0).isEmpty();
        String row0Alt = hasRow0 ? alternationOf(m.parseNode.rows.get(0)) : null;

        if (row0Alt == null) {
            // Leaf: maskedText == modifiedText
            m.maskedText = m.modifiedText;
        } else {
            // Parent: scan row-0 with implicit unmatched tiling
            Pattern p = Pattern.compile(row0Alt, Pattern.DOTALL);
            Matcher matcher = p.matcher(m.modifiedText);
            StringBuilder buf = new StringBuilder();
            int cursor = 0;

            while (matcher.find()) {
                int start = matcher.start(), end = matcher.end();

                // gap before
                if (cursor < start) {
                    appendImplicitGap(m, m.modifiedText.substring(cursor, start), buf, ctx);
                }

                int index = matchedGroupIndex(matcher, m.parseNode.rows.get(0));
                ParseNode childNode = m.parseNode.rows.get(0).get(index);
                String hit = matcher.group(0);

                String token = tokenFor(childNode, ctx.bump(childNode));
                MatchNode child = childNode.createMatchNode(childNode, m, hit, token, m.globalState);
                processNode(child, ctx);

                buf.append(token);
                linkAndTrackChild(m, child);
                cursor = end;
            }

            // trailing gap
            if (cursor < m.modifiedText.length()) {
                appendImplicitGap(m, m.modifiedText.substring(cursor), buf, ctx);
            }

            m.maskedText = buf.toString();
        }

        // Resolve, then run successor rows (fresh parses)
        String resolved = m.unmasked();
        if (m.parseNode.rows.size() > 1) {
            // Seed phases from root state (first value per key)
            MatchNode root = m;
            while (root.parent != null) root = root.parent;
            Map<String,Object> seed = new HashMap<>();
            root.stateMap.asMap().forEach((k, vs) -> { if (!vs.isEmpty()) seed.put(k, vs.iterator().next()); });

            for (int r = 1; r < m.parseNode.rows.size(); r++) {
                List<ParseNode> phaseChildren = m.parseNode.rows.get(r);
                if (phaseChildren.isEmpty()) continue;

                // Synthetic phase-root with row-0 = this phase's children
                ParseNode phaseRoot = new ParseNode("PHASE_"+m.parseNode.keyName+"_"+r, "", List.of(List.copyOf(phaseChildren)), null) {};
                PhaseTrace run = phaseRoot.initiateParsing(resolved, seed);
                var pr = new PhaseRun(phaseRoot, resolved, run.topLevelRootMatch(), run.topLevelOutput(), r-1);
                m.phaseRuns.add(pr);
                resolved = run.topLevelOutput();
            }
            m.maskedText = resolved;
        }

        m.parseNode.afterCapture(m);
    }

    private static String alternationOf(List<ParseNode> kids) {
        StringBuilder sb = null;
        int gi = 0; boolean any = false;
        for (ParseNode c : kids) {
            String p = c.getRegexPattern();
            if (p == null) continue;
            if (!any) { sb = new StringBuilder().append("(?:"); any = true; }
            else sb.append('|');
            sb.append("(?<G").append(gi++).append(">").append(p).append(")");
        }
        if (!any) return null;
        return sb.append(')').toString();
        // Note: groups are positional; matchedGroupIndex uses the same order
    }

    private static int matchedGroupIndex(Matcher m, List<ParseNode> kids) {
        for (int i = 0; i < kids.size(); i++) {
            String p = kids.get(i).getRegexPattern();
            if (p == null) continue;
            if (m.group("G"+i) != null) return i;
        }
        for (int i = 0; i < kids.size(); i++) {
            try { if (m.group("G"+i) != null) return i; } catch (Exception ignored) { }
        }
        throw new IllegalStateException("No matching child group");
    }

    private static void appendImplicitGap(MatchNode parent, String gapText, StringBuilder buf, Context ctx) {
        ParseNode gapNode = new ParseNode("unmatched", "", List.of(), null) {};
        String token = tokenFor(gapNode, ctx.bump(gapNode));
        MatchNode gap = gapNode.createMatchNode(gapNode, parent, gapText, token, parent.globalState);
        gap.modifiedText = gapText;
        gap.maskedText = gapText;
        buf.append(token);
        linkAndTrackChild(parent, gap);
    }

    private static void linkAndTrackChild(MatchNode parent, MatchNode child) {
        if (!parent.children.isEmpty()) {
            MatchNode prev = parent.children.get(parent.children.size() - 1);
            prev.nextSibling = child;
            child.previousSibling = prev;
        }
        parent.children.add(child);
        parent.matchMap.put(child.token, child);
    }

    private static String tokenFor(ParseNode n, int count) {
        return new StringBuilder().append(TOKEN_L).append(n.getUniqueKey(count)).append(TOKEN_R).toString();
    }

    private static final class Context {
        private final IdentityHashMap<ParseNode, Integer> counters = new IdentityHashMap<>();
        int bump(ParseNode n) { int next = counters.getOrDefault(n, 0) + 1; counters.put(n, next); return next; }
    }

    /* ---------------------------- Helpers ---------------------------- */

    /** Allow super-concise subclasses: String field named 'regex' (any visibility). */
    private static String reflectRegexFieldOrNull(Object obj) {
        Class<?> c = obj.getClass();
        while (c != null && ParseNode.class.isAssignableFrom(c)) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getName().equals("regex")) continue;
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (IllegalAccessException ignored) { }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
