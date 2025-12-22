package tools.dscode.common.treeparsing;

import com.google.common.collect.LinkedListMultimap;
import tools.dscode.common.assertions.ValueWrapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.treeparsing.RegexUtil.TOKEN_BODY;
import static tools.dscode.common.treeparsing.RegexUtil.TOKEN_END;
import static tools.dscode.common.treeparsing.RegexUtil.TOKEN_START;


/**
 * Runtime node created for each match.
 */
public final class MatchNode {
    public MatchNode nextSibling;
    public MatchNode previousSibling;
    public int position;
    //    public String name;
    private final ParseNode parseNode;
    private final MatchNode parent;
    private final String token;

    /**
     * Per-run shared state (inherited from root).
     */
    private final LinkedListMultimap<String, Object> globalState;

    /**
     * Per-node local state (unique to this match).
     */
    private final LinkedListMultimap<String, Object> localState;

    /**
     * Named and numbered capture groups for this match (null for root).
     */
    private NamedGroupMap groups;

    //    private final List<MatchNode> children = new ArrayList<>();
    protected final LinkedListMultimap<String, MatchNode> children = LinkedListMultimap.create();
    protected List<MatchNode> sortedChildren;

    private String originalText;
    private String modifiedText;
    private String maskedText;
    public int start;
    private int end;


    public static MatchNode createMatchNode(int start, int ends, ParseNode parseNode, MatchNode parent, String originalText, String baseToken, int occurrence, LinkedListMultimap<String, Object> globalState) {
        if (baseToken == null) return new MatchNode(start, ends, parseNode, parent, originalText, null, globalState);
        int size = globalState.get(baseToken).size();
        String token = TOKEN_START + "_" + baseToken + "_" + occurrence + "_" + size + "_" + TOKEN_END;
        MatchNode matchNode = new MatchNode(start, ends, parseNode, parent, originalText, token, globalState);
        globalState.put(baseToken, matchNode);
        if (parent != null)
            ((Map<String, MatchNode>) globalState.get(matchNodeMapKey).getFirst()).put(token, matchNode);
        return matchNode;
    }

    private MatchNode(int start, int ends,
                      ParseNode parseNode,
                      MatchNode parent,
                      String originalText,
                      String token,
                      LinkedListMultimap<String, Object> globalState) {
        this.parseNode = Objects.requireNonNull(parseNode, "parseNode");
        this.parent = parent;
        this.originalText = originalText;
        this.token = token;
        this.globalState = (globalState != null) ? globalState : LinkedListMultimap.create();
        if (!globalState.containsKey(matchNodeMapKey))
            this.globalState.put(matchNodeMapKey, new HashMap<String, MatchNode>());
//        if (this.globalState.isEmpty())
//            this.globalState.put("_MatchNodeMap", new HashMap<String, MatchNode>());
        this.localState = LinkedListMultimap.create();
        this.groups = null; // set by engine when created from a regex match
        this.start = start;
        this.end = ends;
    }

    static final String matchNodeMapKey = "_MatchNodeMap";

    public void putMatchNode(String key, MatchNode value) {
        ((Map<String, MatchNode>) globalState.get(matchNodeMapKey).getFirst()).put(key, value);
    }

    public MatchNode getMatchNode(String key) {
        try {
            return ((Map<String, MatchNode>) globalState.get(matchNodeMapKey).getFirst()).get(key);
        } catch (Exception e) {
            return null;
        }
    }


    @Override
    public String toString() {
        if (globalState != null) return maskedText;
        return Pattern.compile(TOKEN_BODY).matcher(maskedText)
                .replaceAll(mr -> {
                    Object replacement = getFromGlobalState(mr.group(0));
                    return replacement == null ? mr.group(0) : replacement.toString();
                });
    }

    // ---- getters for printer/engine ----
    public String name() {
        return parseNode.getName();
    }

    public String regex() {
        return parseNode.getRegexPattern();
    }

    public String token() {
        return token;
    }

    public String originalText() {
        return originalText;
    }

    public String modifiedText() {
        return modifiedText;
    }

    public String maskedText() {
        return maskedText;
    }

    public LinkedListMultimap<String, MatchNode> childrenMap() {
        return children;
    }

    public List<MatchNode> children() {
        return children.values();
    }

    public void addChildMatchNode(MatchNode child) {
        children.put(child.name(), child);
    }

    /**
     * Shared across the entire parse run.
     */
    public LinkedListMultimap<String, Object> globalState() {
        return globalState;
    }

    /**
     * Specific to this node instance only.
     */
    public LinkedListMultimap<String, Object> localState() {
        return localState;
    }

    public Object getFromGlobalState(String key) {
        List<Object> list = globalState.get(key);
        if (list.isEmpty() || list.getLast() == null) return null;
        return list.getLast();
    }

    public boolean putToGlobalState(String key, Object value) {
        if (value == null || value.toString().isBlank()) return false;
        return globalState.put(key, value);
    }

    public String getStringFromLocalState(String key) {
        Object o = getFromLocalState(key);
        if (o == null) return "";
        return o.toString();
    }

    public ValueWrapper getValueWrapper(String key) {
        Object o = getFromLocalState(key);
        if (o == null) return null;
        return createValueWrapper(o);
    }

    public Object getFromLocalState(String key) {
        List<Object> list = localState.get(key);
        if (list.isEmpty() || list.getLast() == null) return null;
        return list.getLast();
    }

    public boolean localStateBoolean(String key) {
        List<Object> list = localState.get(key);
        if (list.isEmpty() || list.getLast() == null) return false;
        String s = list.getLast().toString().toLowerCase().strip();
        return !s.isBlank() && !s.equals("false") && !s.equals("0");
    }

    public boolean globalStateBoolean(String key) {
        List<Object> list = globalState.get(key);
        if (list.isEmpty() || list.getLast() == null) return false;
        String s = list.getLast().toString().toLowerCase().strip();
        return !s.isBlank() && !s.equals("false") && !s.equals("0");
    }

    public boolean putToLocalState(String key, Object value) {
        if (value == null) return false;
        return localState.put(key, value);
    }


    /**
     * Iterate global replacements until fixed point so sibling tokens inside expansions are handled.
     */
    public String unmask(String s) {
        if (s == null) return null;
        Map<String, MatchNode> matchNodeMap = ((Map<String, MatchNode>) globalState.get(matchNodeMapKey).getFirst());
        if (s.isEmpty() || matchNodeMap.isEmpty()) return s;
        String prev;
        String cur = s;
        do {
            prev = cur;
            for (Map.Entry<String, MatchNode> m : matchNodeMap.entrySet()) {
                if (m.getValue().modifiedText == null) continue;
                cur = cur.replaceAll(
                        Pattern.quote(m.getKey()),
                        Matcher.quoteReplacement(m.getValue().modifiedText)
                );
            }
        } while (!cur.equals(prev));
        return cur;
    }

    public String resolvedGroupText(String groupName) {
        String returnString = unmask(groups().get(groupName));
        if (returnString == null) returnString = "";
        return returnString.trim();
    }

    public String resolvedGroupText(int groupNum) {
        String returnString = unmask(groups().get(groupNum));
        if (returnString == null) returnString = "";
        return returnString.trim();
    }


    /**
     * Named/numbered groups for this match (may be null for the root).
     */
    public NamedGroupMap groups() {
        return groups;
    }

    // ---- setters used by engine ----
    public void setModifiedText(String s) {
        this.modifiedText = s;
    }

    public void setMaskedText(String s) {
        this.maskedText = s;
    }

    public void setGroups(NamedGroupMap g) {
        this.groups = g;
    }

    // (optional) accessors if needed elsewhere
    public ParseNode parseNode() {
        return parseNode;
    }

    public MatchNode parent() {
        return parent;
    }

    public MatchNode getAncestor(String name) {
        MatchNode current = parent;
        while (current != null && !current.name().equals(name)) current = current.parent;
        return current;
    }

    public List<MatchNode> getDescendants(String name) {
        List<MatchNode> descendants = new ArrayList<>();
        for (MatchNode child : children.values()) {
            if (child.name().equals(name)) {
                descendants.add(child);
                descendants.addAll(child.getDescendants(name));
            }
        }
        return descendants;
    }

    public MatchNode getChild(String name) {
        List<MatchNode> matches = getChildList(name);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    public MatchNode getChild(String name, int index) {
        List<MatchNode> matches = getChildList(name);
        return index >= matches.size() ? null : matches.get(index);
    }

    public List<MatchNode> getChildList(String name) {
        return children.get(name);
    }

    /**
     * Preceding siblings, nearest-first; filter by any of the names (or all if none provided).
     */
    public List<MatchNode> getPreviousSiblings(String... names) {
        var allow = nameFilter(names);
        var out = new ArrayList<MatchNode>();
        for (MatchNode p = this.previousSibling; p != null; p = p.previousSibling) {
            if (allow.test(p)) out.add(p);
        }
        return out; // already nearest -> farthest
    }

    /**
     * Following siblings, nearest-first; filter by any of the names (or all if none provided).
     */
    public List<MatchNode> getNextSiblings(String... names) {
        var allow = nameFilter(names);
        var out = new ArrayList<MatchNode>();
        for (MatchNode n = this.nextSibling; n != null; n = n.nextSibling) {
            if (allow.test(n)) out.add(n);
        }
        return out; // already nearest -> farthest
    }

    public List<MatchNode> getOrderedChildren(String... names) {
        var allow = nameFilter(names);
        var out = new ArrayList<MatchNode>();

        for (MatchNode n : sortedChildren) {

            if (allow.test(n)) out.add(n);
        }
        return out; // already nearest -> farthest
    }

    private static Predicate<MatchNode> nameFilter(String... names) {
        if (names == null || names.length == 0) return mn -> true;

        var set = Arrays.stream(names)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());


        return mn -> {
            if (mn == null) return false;
            boolean match = mn.name() != null && set.contains(mn.name());
            return match;
        };
    }


}
