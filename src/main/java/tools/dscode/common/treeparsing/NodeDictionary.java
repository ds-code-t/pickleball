package tools.dscode.common.treeparsing;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base class for declaring ParseNode fields and wiring them via a tiny YAML-like format.
 * <p>
 * Usage:
 * public class MyDict extends NodeDictionary {
 * ParseNode A = new ParseNode("a+");
 * ParseNode B = new ParseNode("b+");
 * ParseNode root = buildFromYaml("""
 * A:
 * - B
 * """);
 * }
 */
public abstract class    NodeDictionary {

    /**
     * Root node; typically assigned by buildFromYaml(...) in the subclass.
     */
    protected ParseNode root;

    private Map<String, ParseNode> fieldMap; // lazy cached reflection map


    /**
     * Parse with this dictionary's root node.
     */
    public MatchNode parse(String input) {
        if (root == null) throw new IllegalStateException("No root assigned. Call buildFromYaml(...) or set 'root'.");
        return root.initiateParsing(input);
    }

    /**
     * Name -> ParseNode map based on declared fields in subclass chain (subclass-first).
     */
    public synchronized Map<String, ParseNode> nodesByName() {
        if (fieldMap != null) return fieldMap;

        Map<String, ParseNode> map = new LinkedHashMap<>();
        // subclass-first linearization up to NodeDictionary (exclusive)
        Class<?> c = getClass();
        List<Class<?>> chain = new ArrayList<>();
        while (c != null && c != NodeDictionary.class) {
            chain.add(c);
            c = c.getSuperclass();
        }
        for (Class<?> k : chain) {
            for (Field f : k.getDeclaredFields()) {
                if (!ParseNode.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    ParseNode pn = (ParseNode) f.get(this);
                    if (pn != null) {
                        String name = f.getName();
                        // first declaration wins; also set a friendly name for printing/tokens
                        if (!map.containsKey(name)) {
                            pn.setName(name);
                            map.put(name, pn);
                        }
                    }
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Cannot access ParseNode field: " + f.getName(), e);
                }
            }
        }
        fieldMap = Collections.unmodifiableMap(map);
        for (ParseNode parseNode : fieldMap.values()) {
            if (!parseNode.useRegexTemplating)
                continue;
            Pattern pattern = Pattern.compile("<<(.+?)>>");

            Matcher m = pattern.matcher(parseNode.selfRegex);
            parseNode.selfRegex = m.replaceAll((MatchResult mr) -> {
                String key = mr.group(1);
                if (!fieldMap.containsKey(key))
                    return mr.group(0);
                return Matcher.quoteReplacement(fieldMap.get(key).tokenKeyPattern());
            });
        }
        return fieldMap;
    }

    /**
     * Tiny YAML-like wiring (indent 2 spaces; list items start with "- ").
     * Example:
     * RootName:
     * - childA
     * - childB:
     * - grand
     */
    public ParseNode buildFromYaml(String yaml) {
        Objects.requireNonNull(yaml, "yaml");

        Map<String, ParseNode> dict = nodesByName();
        List<String> lines = normalizeToLines(yaml);
        if (lines.isEmpty()) throw new IllegalArgumentException("YAML is empty.");

        int i = 0;
        while (i < lines.size() && lines.get(i).isBlank()) i++;
        if (i >= lines.size()) throw new IllegalArgumentException("YAML has no content.");

        String header = lines.get(i).trim();
        if (!header.endsWith(":")) {
            throw new IllegalArgumentException("Expected 'RootName:' at top, got: " + header);
        }
        String rootName = header.substring(0, header.length() - 1).trim();
        ParseNode root = dict.get(rootName);
        if (root == null) {
            throw new IllegalArgumentException("Unknown node name for root: " + rootName);
        }
        root.parseChildren.clear();

        Deque<ParseNode> parentStack = new ArrayDeque<>();
        Deque<Integer> depthStack = new ArrayDeque<>();
        parentStack.push(root);
        depthStack.push(0);

        for (int line = i + 1; line < lines.size(); line++) {
            String raw = lines.get(line);
            if (raw.isBlank()) continue;

            int leading = countLeadingSpaces(raw);
            if ((leading % 2) != 0) {
                throw new IllegalArgumentException("Indentation must be multiples of 2 spaces: '" + raw + "'");
            }
            int depth = leading / 2;
            String trimmed = raw.trim();

            if (!trimmed.startsWith("- ")) {
                throw new IllegalArgumentException("Expected list item '- name' at: '" + raw + "'");
            }
            String name = trimmed.substring(2).trim();
            if (name.endsWith(":")) name = name.substring(0, name.length() - 1).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Empty node name at: '" + raw + "'");
            }

            ParseNode node = dict.get(name);
            if (node == null) {
                throw new IllegalArgumentException("Unknown node name: " + name);
            }

            // Pop up until we're at the correct parent for this depth (parent depth = depth, child depth = depth+1)
            while (!depthStack.isEmpty() && depthStack.peek() >= depth + 1) {
                depthStack.pop();
                parentStack.pop();
            }
            ParseNode parent = parentStack.peek();
            if (parent == null) throw new IllegalStateException("No parent available for line: '" + raw + "'");

            parent.parseChildren.add(node);

            // This node might be a parent for deeper items
            parentStack.push(node);
            depthStack.push(depth + 1);
        }

        this.root = root;
        return root;
    }

    // ---- helpers ----
    private static int countLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private static List<String> normalizeToLines(String s) {
        String[] arr = s.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        return Arrays.asList(arr);
    }
}
