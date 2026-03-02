// file: tools/dscode/common/variables/YamlBooleanRulesDsl.java
package tools.dscode.common.variables;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;

import java.util.Iterator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML Boolean Rules DSL evaluator (Jackson tree in, ObjectNode out).
 *
 * Root:
 * - DSL root MUST be a YAML list (ArrayNode) of statements.
 *
 * Statements (each statement object MUST have exactly one key):
 * - set
 * - set_and_break
 * - set_defaults
 * - set_defaults_and_break
 * - if
 * - else_if   (ONLY as sibling chaining immediately after if/else_if)
 * - else      (ONLY as sibling chaining immediately after if/else_if, and must be last in chain)
 *
 * Sibling chaining grammar (single-key statements preserved):
 * - if:
 *     when: <ConditionObject>
 *     then: [ <Statement>, ... ]
 * - else_if:
 *     when: <ConditionObject>
 *     then: [ <Statement>, ... ]
 * - else:
 *     then: [ <Statement>, ... ]
 *
 * Conditions:
 * - property predicate: {propName: <value>} where <value> is scalar | list-of-scalars | {get:[...]}
 * - operator block: all/any/none/only-n
 *
 * Value expression (usable anywhere a value is needed):
 * - {get: [name1, name2, sys_FOO, env_BAR, ...]}
 * Resolution order for each name:
 *   1) working ObjectNode (returned config), supports dotted paths
 *   2) if name starts with sys_ and missing in working -> System.getProperty(suffix)
 *   3) if name starts with env_ and missing in working -> System.getenv(suffix)
 * First non-null wins. If none -> resolves to null.
 *
 * IMPORTANT: set_defaults env_/sys_ keys special behavior:
 * - If output already has NON-NULL value for that key -> do nothing
 * - Else if external (suffix) exists -> do nothing (do NOT write into output)
 * - Else set the YAML default value (which may itself be {get:[...]})
 */
public final class YamlBooleanRulesDsl {

    private static final Pattern ONLY_N = Pattern.compile("^only-(\\d+)$");
    private static final String ENV_PREFIX = "env_";
    private static final String SYS_PREFIX = "sys_";
    private static final String GET_KEY = "get";

    // if/else statement body keys
    private static final String WHEN_KEY = "when";
    private static final String THEN_KEY = "then";

    private YamlBooleanRulesDsl() {}

    public static ObjectNode evaluate(JsonNode dslRoot, ObjectNode working) {
        Objects.requireNonNull(dslRoot, "dslRoot");
        Objects.requireNonNull(working, "working");

        if (!dslRoot.isArray()) {
            throw bad("$", "Top-level MUST be a YAML list (ArrayNode) of statements.");
        }

        evalStatements((ArrayNode) dslRoot, working, "$");
        return working;
    }

    /** Executes statements. Returns true if document-level break occurred. */
    private static boolean evalStatements(ArrayNode statements, ObjectNode working, String path) {
        for (int i = 0; i < statements.size(); i++) {
            String stPath = path + "[" + i + "]";
            ObjectNode stmtObj = requireObject(statements.get(i), stPath);
            String key = requireSingleKey(stmtObj, stPath);
            JsonNode body = stmtObj.get(key);

            switch (key) {
                case "set" -> applyAssignments(requireObject(body, stPath + ".set"), working, AssignMode.ALWAYS, stPath + ".set");

                case "set_and_break" -> {
                    applyAssignments(requireObject(body, stPath + ".set_and_break"), working, AssignMode.ALWAYS, stPath + ".set_and_break");
                    return true;
                }

                case "set_defaults" -> applyAssignments(requireObject(body, stPath + ".set_defaults"), working, AssignMode.DEFAULTS_ONLY, stPath + ".set_defaults");

                case "set_defaults_and_break" -> {
                    applyAssignments(requireObject(body, stPath + ".set_defaults_and_break"), working, AssignMode.DEFAULTS_ONLY, stPath + ".set_defaults_and_break");
                    return true;
                }

                case "if" -> {
                    IfChain chain = collectSiblingIfChain(statements, i, path);
                    boolean broke = evalIfChain(chain, working, stPath);
                    if (broke) return true;
                    i = chain.lastIndexConsumed; // skip consumed else_if/else siblings
                }

                case "else_if", "else" -> throw bad(stPath,
                        "Dangling `" + key + "`: sibling `" + key + "` must immediately follow an `if` (or another `else_if` in the same chain).");

                default -> throw bad(stPath,
                        "Invalid statement key '" + key + "'. Allowed: set, set_and_break, set_defaults, set_defaults_and_break, if (plus sibling else_if/else only when chaining).");
            }
        }
        return false;
    }

    // ----------------------------
    // Sibling if / else_if / else
    // ----------------------------

    private static final class IfArm {
        final String kind; // "if" | "else_if" | "else"
        final ObjectNode when; // null for else
        final ArrayNode thenStmts;

        IfArm(String kind, ObjectNode when, ArrayNode thenStmts) {
            this.kind = kind;
            this.when = when;
            this.thenStmts = thenStmts;
        }
    }

    private static final class IfChain {
        final IfArm[] arms;
        final int lastIndexConsumed;

        IfChain(IfArm[] arms, int lastIndexConsumed) {
            this.arms = arms;
            this.lastIndexConsumed = lastIndexConsumed;
        }
    }

    private static boolean evalIfChain(IfChain chain, ObjectNode working, String ifPath) {
        for (int a = 0; a < chain.arms.length; a++) {
            IfArm arm = chain.arms[a];

            boolean matches;
            if ("else".equals(arm.kind)) {
                matches = true;
            } else {
                matches = evalCondition(arm.when, working, ifPath + "." + arm.kind + "[" + a + "].when");
            }

            if (matches) {
                return evalStatements(arm.thenStmts, working, ifPath + "." + arm.kind + "[" + a + "].then");
            }
        }
        return false;
    }

    /**
     * Consumes: if + following adjacent else_if/else siblings into a single chain.
     * Grammar:
     * - if: { when: <cond>, then: [stmts...] }
     * - else_if: { when: <cond>, then: [stmts...] }
     * - else: { then: [stmts...] }
     */
    private static IfChain collectSiblingIfChain(ArrayNode statements, int ifIndex, String path) {
        IfArm[] tmp = new IfArm[Math.max(1, statements.size() - ifIndex)];
        int count = 0;

        // parse required leading `if`
        tmp[count++] = parseArm(requireObject(statements.get(ifIndex), path + "[" + ifIndex + "]"),
                "if", path + "[" + ifIndex + "]");

        boolean seenElse = false;
        int j = ifIndex + 1;

        while (j < statements.size()) {
            ObjectNode stmtObj = asSingleKeyObject(statements.get(j));
            if (stmtObj == null) break;

            String key = requireSingleKey(stmtObj, path + "[" + j + "]");
            if (!"else_if".equals(key) && !"else".equals(key)) break;

            if ("else".equals(key)) {
                if (seenElse) throw bad(path + "[" + j + "].else", "Only one `else` is allowed in an if-chain.");
                seenElse = true;
            } else {
                if (seenElse) throw bad(path + "[" + j + "].else_if", "`else_if` cannot appear after `else` in an if-chain.");
            }

            tmp[count++] = parseArm(stmtObj, key, path + "[" + j + "]");
            j++;

            if (seenElse) break; // else ends the chain
        }

        IfArm[] arms = new IfArm[count];
        System.arraycopy(tmp, 0, arms, 0, count);
        return new IfChain(arms, j - 1);
    }

    private static IfArm parseArm(ObjectNode stmtObj, String kind, String stPath) {
        JsonNode body = stmtObj.get(kind);
        if (body == null || !body.isObject()) {
            throw bad(stPath + "." + kind, "`" + kind + "` value must be an object like {" + kind + ": {when: ..., then: [...]}}.");
        }
        ObjectNode o = (ObjectNode) body;

        // then is required for all arms
        ArrayNode thenList = requireArray(o.get(THEN_KEY), stPath + "." + kind + "." + THEN_KEY);
        if (thenList.isEmpty()) throw bad(stPath + "." + kind + "." + THEN_KEY, "`then` must contain one or more statements.");

        // validate each element is an object statement (single-key constraint enforced later when executed)
        ArrayNode normalizedThen = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < thenList.size(); i++) {
            normalizedThen.add(requireObject(thenList.get(i), stPath + "." + kind + ".then[" + i + "]"));
        }

        if ("else".equals(kind)) {
            // else must NOT have when
            if (o.has(WHEN_KEY)) {
                throw bad(stPath + "." + kind + "." + WHEN_KEY, "`else` must not have `when`.");
            }
            // also prevent unknown keys in body (optional strictness)
            requireOnlyKeys(o, stPath + "." + kind, THEN_KEY);
            return new IfArm(kind, null, normalizedThen);
        }

        // if / else_if require when
        ObjectNode when = requireObject(o.get(WHEN_KEY), stPath + "." + kind + "." + WHEN_KEY);
        requireOnlyKeys(o, stPath + "." + kind, WHEN_KEY, THEN_KEY);
        return new IfArm(kind, when, normalizedThen);
    }

    private static void requireOnlyKeys(ObjectNode obj, String path, String... allowed) {
        java.util.Set<String> allowedSet = new java.util.HashSet<>();
        for (String a : allowed) allowedSet.add(a);

        Iterator<String> it = obj.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (!allowedSet.contains(k)) {
                throw bad(path + "." + k, "Unexpected key '" + k + "'. Allowed keys here: " + allowedSet);
            }
        }
    }

    // ----------------------------
    // Conditions
    // ----------------------------

    private static boolean evalCondition(ObjectNode condition, ObjectNode working, String path) {
        String onlyKey = requireSingleKey(condition, path);
        JsonNode value = condition.get(onlyKey);

        // Operator block
        if (isOperator(onlyKey)) {
            if (!value.isArray()) throw bad(path + "." + onlyKey, "Operator '" + onlyKey + "' must have a list value.");
            ArrayNode subs = (ArrayNode) value;
            if (subs.isEmpty()) throw bad(path + "." + onlyKey, "Operator '" + onlyKey + "' must contain at least one subcondition.");

            if ("all".equals(onlyKey)) {
                for (int i = 0; i < subs.size(); i++) {
                    if (!evalCondition(requireObject(subs.get(i), path + ".all[" + i + "]"), working, path + ".all[" + i + "]")) {
                        return false;
                    }
                }
                return true;
            }

            if ("any".equals(onlyKey)) {
                for (int i = 0; i < subs.size(); i++) {
                    if (evalCondition(requireObject(subs.get(i), path + ".any[" + i + "]"), working, path + ".any[" + i + "]")) {
                        return true;
                    }
                }
                return false;
            }

            if ("none".equals(onlyKey)) {
                for (int i = 0; i < subs.size(); i++) {
                    if (evalCondition(requireObject(subs.get(i), path + ".none[" + i + "]"), working, path + ".none[" + i + "]")) {
                        return false;
                    }
                }
                return true;
            }

            Matcher m = ONLY_N.matcher(onlyKey);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                int count = 0;
                for (int i = 0; i < subs.size(); i++) {
                    if (evalCondition(requireObject(subs.get(i), path + "." + onlyKey + "[" + i + "]"),
                            working, path + "." + onlyKey + "[" + i + "]")) {
                        count++;
                    }
                }
                return count == n;
            }

            throw bad(path, "Unknown operator '" + onlyKey + "'.");
        }

        // Property predicate
        String prop = onlyKey;
        String actual = resolveScalarForCondition(prop, working);

        // Right-hand can be: scalar | list-of-scalars | {get:[...]}
        if (value.isArray()) {
            // OR shorthand list; allow scalars or {get:[...]} elements
            for (int i = 0; i < value.size(); i++) {
                JsonNode el = value.get(i);
                String expected = resolveScalarValue(el, working, path + "." + prop + "[" + i + "]");
                if (expected == null) continue;
                if (actual != null && actual.equals(expected)) return true;
            }
            return false;
        }

        String expected = resolveScalarValue(value, working, path + "." + prop);
        if (expected == null) {
            // If expected came from {get:[...]} and resolved to null, treat as no match.
            return false;
        }

        return actual != null && actual.equals(expected);
    }

    private static boolean isOperator(String key) {
        return "all".equals(key) || "any".equals(key) || "none".equals(key) || ONLY_N.matcher(key).matches();
    }

    /**
     * Left-hand property lookup for conditions:
     * - First: working config (supports dotted path)
     * - If missing and name starts with sys_/env_: consult external source using suffix
     */
    private static String resolveScalarForCondition(String prop, ObjectNode working) {
        JsonNode inWorking = getByDottedPath(working, prop);
        if (!isMissingOrNull(inWorking)) return inWorking.asText();

        if (prop.startsWith(SYS_PREFIX)) {
            String suffix = prop.substring(SYS_PREFIX.length());
            return System.getProperty(suffix); // may be null
        }
        if (prop.startsWith(ENV_PREFIX)) {
            String suffix = prop.substring(ENV_PREFIX.length());
            return System.getenv(suffix); // may be null
        }
        return null;
    }

    /**
     * Resolve a right-hand "value" (for predicates) to a scalar string:
     * - scalar -> its text
     * - {get:[...]} -> resolve first non-null and return as text
     */
    private static String resolveScalarValue(JsonNode node, ObjectNode working, String path) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;

        if (isGetExpr(node)) {
            JsonNode resolved = resolveGetExpr(node, working, path);
            if (resolved == null || resolved.isNull() || resolved.isMissingNode()) return null;
            return resolved.asText();
        }

        if (!node.isValueNode()) {
            throw bad(path, "Value must be a scalar, a list of scalars, or {get:[...]}.");
        }
        return node.asText();
    }

    // ----------------------------
    // Assignments
    // ----------------------------

    private enum AssignMode { ALWAYS, DEFAULTS_ONLY }

    private static void applyAssignments(ObjectNode assigns, ObjectNode working, AssignMode mode, String path) {
        assigns.fields().forEachRemaining(e -> {
            String dottedKey = e.getKey();
            JsonNode rawYamlValue = e.getValue() == null ? NullNode.instance : e.getValue();

            if (mode == AssignMode.ALWAYS) {
                JsonNode valueToSet = materializeValueForSet(rawYamlValue, working, path + "." + dottedKey);
                putDotted(working, dottedKey, valueToSet);
                return;
            }

            // DEFAULTS_ONLY
            JsonNode existing = getByDottedPath(working, dottedKey);

            // Special rule for env_*/sys_* defaults:
            if (dottedKey.startsWith(ENV_PREFIX)) {
                if (!isMissingOrNull(existing)) return; // already has NON-NULL
                String suffix = dottedKey.substring(ENV_PREFIX.length());
                if (System.getenv(suffix) != null) return; // external exists => do nothing
                JsonNode valueToSet = materializeValueForSet(rawYamlValue, working, path + "." + dottedKey);
                putDotted(working, dottedKey, valueToSet);
                return;
            }

            if (dottedKey.startsWith(SYS_PREFIX)) {
                if (!isMissingOrNull(existing)) return; // already has NON-NULL
                String suffix = dottedKey.substring(SYS_PREFIX.length());
                if (System.getProperty(suffix) != null) return; // external exists => do nothing
                JsonNode valueToSet = materializeValueForSet(rawYamlValue, working, path + "." + dottedKey);
                putDotted(working, dottedKey, valueToSet);
                return;
            }

            // Normal defaults for non-prefixed keys: set if missing/null/blank
            if (!isUnset(existing)) return;

            JsonNode valueToSet = materializeValueForSet(rawYamlValue, working, path + "." + dottedKey);
            putDotted(working, dottedKey, valueToSet);
        });
    }

    /**
     * Materialize a value for assignment:
     * - If value is {get:[...]} => resolve it to the first non-null JsonNode; if none, NullNode
     * - Else deepCopy the node (including scalars/objects/arrays)
     */
    private static JsonNode materializeValueForSet(JsonNode rawValue, ObjectNode working, String path) {
        if (rawValue == null || rawValue.isMissingNode()) return NullNode.instance;

        if (isGetExpr(rawValue)) {
            JsonNode resolved = resolveGetExpr(rawValue, working, path);
            if (resolved == null || resolved.isMissingNode() || resolved.isNull()) return NullNode.instance;
            return resolved.deepCopy();
        }

        return rawValue.isNull() ? NullNode.instance : rawValue.deepCopy();
    }

    // ----------------------------
    // {get:[...]} expression
    // ----------------------------

    private static boolean isGetExpr(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        ObjectNode o = (ObjectNode) node;
        Iterator<String> it = o.fieldNames();
        if (!it.hasNext()) return false;
        String k = it.next();
        return !it.hasNext() && GET_KEY.equals(k);
    }

    /**
     * Resolve {get:[name1, name2, ...]} to the first non-null value:
     * For each name:
     *  1) check working (supports dotted path)
     *  2) if name starts sys_ or env_ and missing in working, check external using suffix
     * Returns:
     *  - JsonNode from working (preserves type), or TextNode for external
     *  - MissingNode if nothing resolved
     */
    private static JsonNode resolveGetExpr(JsonNode getExpr, ObjectNode working, String path) {
        ObjectNode o = (ObjectNode) getExpr;
        JsonNode listNode = o.get(GET_KEY);
        if (listNode == null || !listNode.isArray()) {
            throw bad(path, "{get: ...} must contain a list value, e.g. {get: [a,b,sys_X]}");
        }

        ArrayNode names = (ArrayNode) listNode;
        for (int i = 0; i < names.size(); i++) {
            JsonNode n = names.get(i);
            if (n == null || n.isNull() || n.isMissingNode()) continue;
            if (!n.isValueNode()) {
                throw bad(path + ".get[" + i + "]", "Each entry in get-list must be a scalar property name.");
            }

            String name = n.asText();

            // 1) working config first
            JsonNode inWorking = getByDottedPath(working, name);
            if (!isMissingOrNull(inWorking)) {
                return inWorking;
            }

            // 2) external (only for prefixed keys), then continue to next name
            if (name.startsWith(SYS_PREFIX)) {
                String suffix = name.substring(SYS_PREFIX.length());
                String v = System.getProperty(suffix);
                if (v != null) return TextNode.valueOf(v);
            } else if (name.startsWith(ENV_PREFIX)) {
                String suffix = name.substring(ENV_PREFIX.length());
                String v = System.getenv(suffix);
                if (v != null) return TextNode.valueOf(v);
            }
        }

        return MissingNode.getInstance();
    }

    // ----------------------------
    // Dotted keys
    // ----------------------------

    private static void putDotted(ObjectNode root, String dottedKey, JsonNode value) {
        String[] parts = dottedKey.split("\\.");
        ObjectNode cur = root;

        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;

            boolean last = (i == parts.length - 1);
            if (last) {
                cur.set(p, value);
                return;
            }

            JsonNode existing = cur.get(p);
            if (existing != null && existing.isObject()) {
                cur = (ObjectNode) existing;
            } else {
                ObjectNode child = root.objectNode();
                cur.set(p, child);
                cur = child;
            }
        }
    }

    /** Read a dotted path from working config; returns MissingNode if not present. */
    private static JsonNode getByDottedPath(ObjectNode root, String dottedKey) {
        // literal first
        JsonNode direct = root.get(dottedKey);
        if (direct != null) return direct;

        JsonNode cur = root;
        for (String part : dottedKey.split("\\.")) {
            if (part.isEmpty()) continue;
            if (cur == null || !cur.isObject()) return MissingNode.getInstance();
            cur = cur.get(part);
        }
        return cur == null ? MissingNode.getInstance() : cur;
    }

    /** "Unset" means missing OR null OR blank string. */
    private static boolean isUnset(JsonNode node) {
        if (isMissingOrNull(node)) return true;
        if (node.isTextual()) return node.asText().isBlank();
        return false;
    }

    private static boolean isMissingOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    // ----------------------------
    // Validation helpers
    // ----------------------------

    private static ObjectNode requireObject(JsonNode n, String path) {
        if (n == null || !n.isObject()) throw bad(path, "Expected an object/mapping.");
        return (ObjectNode) n;
    }

    private static ArrayNode requireArray(JsonNode n, String path) {
        if (n == null || !n.isArray()) throw bad(path, "Expected a list/sequence (ArrayNode).");
        return (ArrayNode) n;
    }

    private static String requireSingleKey(ObjectNode obj, String path) {
        Iterator<String> it = obj.fieldNames();
        if (!it.hasNext()) throw bad(path, "Mapping cannot be empty.");
        String k = it.next();
        if (it.hasNext()) throw bad(path, "Mapping must contain exactly one key.");
        return k;
    }

    private static IllegalArgumentException bad(String path, String msg) {
        return new IllegalArgumentException("DSL validation error at " + path + ": " + msg);
    }

    /**
     * Returns the node as an ObjectNode iff it is an object with exactly one key, else null.
     * Useful for "lookahead" without throwing.
     */
    private static ObjectNode asSingleKeyObject(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        ObjectNode o = (ObjectNode) n;
        Iterator<String> it = o.fieldNames();
        if (!it.hasNext()) return null;
        it.next();
        if (it.hasNext()) return null;
        return o;
    }
}