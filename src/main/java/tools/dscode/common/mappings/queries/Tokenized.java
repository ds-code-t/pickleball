package tools.dscode.common.mappings.queries;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static tools.dscode.common.mappings.NodeMap.toSafeJsonNode;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.mappings.ValueFormatting.fromSafeJsonNode;

/**
 * Compiles a NodeMap query for reading and writing.
 *
 * <p>Read queries are normal JSONata with three small conveniences:</p>
 * <ul>
 *   <li>{@code #N} uses one-based indexes and is converted to JSONata indexes.</li>
 *   <li>Unambiguous path properties containing spaces are backticked.</li>
 *   <li>A plain non-underscore root property selects its last collection item.</li>
 * </ul>
 *
 * <p>Write queries are either a direct writable path or a JSONata selector
 * followed by a direct writable suffix. Ordinary root properties are stored as
 * arrays; underscore-prefixed root properties are stored as singleton values.</p>
 */
public final class Tokenized {
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");
    private static final Pattern SPACED_PROPERTY = Pattern.compile(
            "[\\p{L}\\p{N}_]+(?:\\h+[\\p{L}\\p{N}_]+)+");
    private static final Set<String> ROOT_LITERALS = Set.of("true", "false", "null");
    private static final Set<String> WORD_OPERATORS = Set.of("and", "or", "in");

    private final String readExpression;
    private final String listExpression;
    private final WritePlan writePlan;
    private final boolean singletonRoot;

    public Tokenized(String query) {
        this(query, false);
    }

    private Tokenized(String query, boolean singletonRoot) {
        if (query == null) {
            throw new IllegalArgumentException("Query cannot be null");
        }

        String source = query.strip();
        this.readExpression = normalizeRead(source, true);
        this.listExpression = normalizeRead(source, false);
        this.writePlan = parseWritePlan(listExpression);
        this.singletonRoot = singletonRoot;
    }

    /** Creates an explicit singleton write without encoding it in query punctuation. */
    public static Tokenized singletonWrite(String query) {
        return new Tokenized(query, true);
    }

    /** Returns the JSONata expression used by a normal read. */
    public static String preprocessReadQuery(String query) {
        return normalizeRead(query, true);
    }

    public Object get(JsonNode root) {
        JsonNode result = evaluate(root, readExpression);
        return result == null ? null : fromSafeJsonNode(result);
    }

    /** Evaluates without implicit last-root selection and adapts the result to a list. */
    public List<JsonNode> getList(JsonNode root) {
        JsonNode result = evaluate(root, listExpression);
        if (result == null) {
            return null;
        }
        if (result instanceof ArrayNode array) {
            return array.valueStream().toList();
        }
        return List.of(result);
    }

    /** Applies this query as a write against a NodeMap root. */
    public void put(ObjectNode root, Object value) {
        if (root == null) {
            throw new IllegalArgumentException("Write root cannot be null");
        }
        if (writePlan instanceof ReadOnly) {
            throw new IllegalArgumentException(
                    "The expression is readable but is not a writable NodeMap path: " + listExpression);
        }

        JsonNode safeValue = toSafeJsonNode(value);
        safeValue = safeValue == null ? NullNode.instance : safeValue;

        switch (writePlan) {
            case Direct direct -> applyDirectRoot(root, direct.steps(), safeValue);
            case Selected selected -> applySelected(root, selected, safeValue);
            case ReadOnly ignored -> throw new IllegalStateException("Unexpected read-only write plan");
        }
    }

    /** Evaluates a JSONata expression and returns {@code null} for invalid or missing results. */
    public static JsonNode evaluate(JsonNode root, String expression) {
        if (root == null || expression == null || expression.isBlank()) {
            return null;
        }
        try {
            return Expressions.parse(expression).evaluate(root);
        } catch (ParseException | IOException | EvaluateException ex) {
            return null;
        }
    }

    /* ------------------------------------------------------------------
       Read normalization
       ------------------------------------------------------------------ */

    private static String normalizeRead(String query, boolean selectLastRoot) {
        if (query == null) {
            return null;
        }

        String indexed = rewriteCustomIndexes(query.strip());
        SimplePath path = parseSimplePath(indexed);
        if (path == null) {
            return indexed;
        }

        String expression = path.expression();
        if (!selectLastRoot
                || path.rootIndexed()
                || path.rootFunction()
                || path.rootName().startsWith("_")) {
            return expression;
        }

        return expression.substring(0, path.rootEnd())
                + "[][-1]"
                + expression.substring(path.rootEnd());
    }

    private static String rewriteCustomIndexes(String input) {
        StringBuilder result = new StringBuilder(input.length());

        for (int index = 0; index < input.length();) {
            int protectedEnd = protectedEnd(input, index);
            if (protectedEnd > index) {
                result.append(input, index, protectedEnd);
                index = protectedEnd;
                continue;
            }

            Selector selector = input.charAt(index) == '#' ? parseSelector(input, index) : null;
            if (selector == null) {
                result.append(input.charAt(index++));
            } else {
                result.append(selector.replacement());
                index = selector.end();
            }
        }
        return result.toString();
    }

    private static Selector parseSelector(String input, int hashIndex) {
        int start = hashIndex + 1;

        if (input.regionMatches(true, start, "first", 0, 5)
                && isTokenBoundary(input, start + 5)) {
            return new Selector("[0]", start + 5);
        }
        if (input.regionMatches(true, start, "last", 0, 4)
                && isTokenBoundary(input, start + 4)) {
            return new Selector("[-1]", start + 4);
        }

        NumberToken first = parseSignedInteger(input, start);
        if (first == null) {
            return null;
        }

        int cursor = skipWhitespace(input, first.end());
        if (cursor < input.length() && input.charAt(cursor) == ',') {
            List<Long> positions = new ArrayList<>();
            positions.add(toJsonataIndex(first.value()));

            while (cursor < input.length() && input.charAt(cursor) == ',') {
                NumberToken next = parseSignedInteger(input, skipWhitespace(input, cursor + 1));
                if (next == null) {
                    return null;
                }
                positions.add(toJsonataIndex(next.value()));
                cursor = skipWhitespace(input, next.end());
            }

            if (!isTokenBoundary(input, cursor)) {
                return null;
            }
            String values = positions.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            return new Selector("[[" + values + "]]", cursor);
        }

        if (cursor < input.length() && input.charAt(cursor) == '-') {
            NumberToken last = parseSignedInteger(input, skipWhitespace(input, cursor + 1));
            if (last == null || !isTokenBoundary(input, last.end())) {
                return null;
            }
            return new Selector(
                    "[[" + toJsonataIndex(first.value()) + ".." + toJsonataIndex(last.value()) + "]]",
                    last.end());
        }

        if (!isTokenBoundary(input, first.end())) {
            return null;
        }
        return new Selector("[" + toJsonataIndex(first.value()) + "]", first.end());
    }

    private static NumberToken parseSignedInteger(String input, int start) {
        int cursor = start;
        if (cursor < input.length() && (input.charAt(cursor) == '-' || input.charAt(cursor) == '+')) {
            cursor++;
        }

        int digits = cursor;
        while (cursor < input.length() && Character.isDigit(input.charAt(cursor))) {
            cursor++;
        }
        if (cursor == digits) {
            return null;
        }

        try {
            return new NumberToken(Long.parseLong(input.substring(start, cursor)), cursor);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Index is outside the supported numeric range", ex);
        }
    }

    private static long toJsonataIndex(long oneBasedIndex) {
        try {
            return Math.subtractExact(oneBasedIndex, 1L);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Index is outside the supported numeric range", ex);
        }
    }

    /**
     * Normalizes only a clear path prefix. Once the expression becomes
     * ambiguous, the remaining JSONata is copied unchanged.
     */
    private static SimplePath parseSimplePath(String input) {
        if (input.isEmpty() || input.charAt(0) == '(') {
            return null;
        }

        StringBuilder output = new StringBuilder(input.length());
        int cursor = 0;
        int rootEnd = -1;
        String rootName = null;
        boolean rootIndexed = false;
        boolean rootFunction = false;
        boolean root = true;

        while (true) {
            cursor = skipWhitespace(input, cursor);
            int propertyStart = cursor;
            Property property = parseReadableProperty(input, cursor, root);
            if (property == null) {
                if (root) {
                    return null;
                }
                output.append(input.substring(propertyStart));
                return new SimplePath(output.toString(), rootEnd, rootName, rootIndexed, rootFunction);
            }

            output.append(property.expression());
            cursor = property.end();
            if (root) {
                rootName = property.name();
                rootEnd = output.length();
            }

            int next = skipWhitespace(input, cursor);
            while (next < input.length() && input.charAt(next) == '[') {
                int bracketEnd = balancedBracketEnd(input, next);
                if (bracketEnd < 0) {
                    output.append(input.substring(cursor));
                    return new SimplePath(output.toString(), rootEnd, rootName, rootIndexed, rootFunction);
                }
                if (root) {
                    rootIndexed = true;
                }
                output.append(input, next, bracketEnd);
                cursor = bracketEnd;
                next = skipWhitespace(input, cursor);
            }

            if (root && next < input.length() && input.charAt(next) == '(') {
                rootFunction = true;
            }
            if (next == input.length()) {
                return new SimplePath(output.toString(), rootEnd, rootName, rootIndexed, rootFunction);
            }
            if (input.charAt(next) != '.'
                    || (next + 1 < input.length() && input.charAt(next + 1) == '.')) {
                output.append(input.substring(cursor));
                return new SimplePath(output.toString(), rootEnd, rootName, rootIndexed, rootFunction);
            }

            output.append('.');
            cursor = next + 1;
            root = false;
        }
    }

    private static Property parseReadableProperty(String input, int start, boolean root) {
        if (start >= input.length()) {
            return null;
        }

        if (input.charAt(start) == '`') {
            int end = quotedEnd(input, start, '`');
            if (end < 0) {
                return null;
            }
            String expression = input.substring(start, end);
            return new Property(expression, unquoteProperty(expression), end);
        }

        int end = findReadablePropertyEnd(input, start);
        String property = input.substring(start, end).strip();
        if (property.isEmpty()) {
            return null;
        }
        if (IDENTIFIER.matcher(property).matches()) {
            if (root && ROOT_LITERALS.contains(property)) {
                return null;
            }
            return new Property(property, property, end);
        }
        if (!root && (property.equals("*") || property.equals("**") || property.equals("%"))) {
            return new Property(property, property, end);
        }
        if (SPACED_PROPERTY.matcher(property).matches()) {
            return new Property("`" + property + "`", property, end);
        }
        return null;
    }

    private static int findReadablePropertyEnd(String input, int start) {
        int cursor = start;
        while (cursor < input.length()) {
            char current = input.charAt(cursor);
            if (".[](){};,?:=<>!&|~+-*/%^@#'\"`".indexOf(current) >= 0) {
                break;
            }

            if (Character.isWhitespace(current)) {
                int wordStart = skipWhitespace(input, cursor);
                int wordEnd = wordStart;
                while (wordEnd < input.length()
                        && (Character.isLetterOrDigit(input.charAt(wordEnd))
                        || input.charAt(wordEnd) == '_')) {
                    wordEnd++;
                }
                if (wordEnd > wordStart
                        && WORD_OPERATORS.contains(input.substring(wordStart, wordEnd))
                        && isTokenBoundary(input, wordEnd)) {
                    break;
                }
            }
            cursor++;
        }

        while (cursor > start && Character.isWhitespace(input.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    /* ------------------------------------------------------------------
       Write planning
       ------------------------------------------------------------------ */

    private static WritePlan parseWritePlan(String expression) {
        if (expression == null || expression.isBlank()) {
            return ReadOnly.INSTANCE;
        }

        DirectPath completePath = parseDirectPath(expression);
        if (completePath != null) {
            return new Direct(completePath.steps());
        }

        for (int dot : topLevelDots(expression)) {
            String selector = expression.substring(0, dot).stripTrailing();
            DirectPath suffix = parseDirectPath(expression.substring(dot + 1).stripLeading());
            if (!selector.isBlank() && suffix != null && !suffix.steps().isEmpty()) {
                return new Selected(selector, suffix.steps());
            }
        }
        return ReadOnly.INSTANCE;
    }

    private static DirectPath parseDirectPath(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        List<WriteStep> steps = new ArrayList<>();
        int cursor = 0;
        boolean needProperty = true;

        while (cursor < expression.length()) {
            cursor = skipWhitespace(expression, cursor);
            if (cursor >= expression.length()) {
                break;
            }

            if (needProperty) {
                DirectProperty property = parseWritableProperty(expression, cursor);
                if (property == null) {
                    return null;
                }
                steps.add(property.step());
                cursor = property.end();
                needProperty = false;
            }

            cursor = skipWhitespace(expression, cursor);
            while (cursor < expression.length() && expression.charAt(cursor) == '[') {
                BracketStep bracket = parseWritableBracket(expression, cursor);
                if (bracket == null) {
                    return null;
                }
                steps.add(bracket.step());
                cursor = skipWhitespace(expression, bracket.end());
            }

            if (cursor >= expression.length()) {
                break;
            }
            if (expression.charAt(cursor) != '.'
                    || (cursor + 1 < expression.length() && expression.charAt(cursor + 1) == '.')) {
                return null;
            }
            cursor++;
            needProperty = true;
        }

        return needProperty || steps.isEmpty() ? null : new DirectPath(List.copyOf(steps));
    }

    private static DirectProperty parseWritableProperty(String expression, int start) {
        if (start >= expression.length()) {
            return null;
        }

        if (expression.charAt(start) == '`') {
            int end = quotedEnd(expression, start, '`');
            if (end < 0) {
                return null;
            }
            return new DirectProperty(
                    new PropertyStep(unquoteProperty(expression.substring(start, end))), end);
        }

        int end = findWritablePropertyEnd(expression, start);
        String property = expression.substring(start, end).strip();
        if (IDENTIFIER.matcher(property).matches() || SPACED_PROPERTY.matcher(property).matches()) {
            return new DirectProperty(new PropertyStep(property), end);
        }
        return null;
    }

    private static int findWritablePropertyEnd(String expression, int start) {
        int cursor = start;
        while (cursor < expression.length()) {
            char current = expression.charAt(cursor);
            if (current == '.' || current == '[') {
                break;
            }
            if ("(){};,?:=<>!&|~+-*/%^@#'\"`".indexOf(current) >= 0) {
                return start;
            }
            cursor++;
        }
        while (cursor > start && Character.isWhitespace(expression.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    private static BracketStep parseWritableBracket(String expression, int start) {
        int end = balancedBracketEnd(expression, start);
        if (end < 0) {
            return null;
        }

        String content = expression.substring(start + 1, end - 1).strip();
        if (content.isEmpty()) {
            return new BracketStep(AppendStep.INSTANCE, end);
        }
        if (content.equals("*")) {
            return new BracketStep(WildcardStep.INSTANCE, end);
        }
        if (!content.matches("[+-]?\\d+")) {
            return null;
        }

        try {
            return new BracketStep(new IndexStep(Integer.parseInt(content)), end);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "Array index is outside the supported integer range: " + content, ex);
        }
    }

    private static List<Integer> topLevelDots(String expression) {
        List<Integer> dots = new ArrayList<>();
        int brackets = 0;
        int parentheses = 0;
        int braces = 0;

        for (int index = 0; index < expression.length();) {
            int protectedEnd = protectedEnd(expression, index);
            if (protectedEnd > index) {
                index = protectedEnd;
                continue;
            }

            switch (expression.charAt(index)) {
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '(' -> parentheses++;
                case ')' -> parentheses--;
                case '{' -> braces++;
                case '}' -> braces--;
                case '.' -> {
                    if (brackets == 0 && parentheses == 0 && braces == 0
                            && !(index + 1 < expression.length()
                            && expression.charAt(index + 1) == '.')) {
                        dots.add(index);
                    }
                }
                default -> { }
            }
            index++;
        }
        return dots;
    }

    /* ------------------------------------------------------------------
       Write execution
       ------------------------------------------------------------------ */

    private void applyDirectRoot(ObjectNode root, List<WriteStep> steps, JsonNode value) {
        if (steps.isEmpty() || !(steps.getFirst() instanceof PropertyStep property)) {
            throw new IllegalArgumentException("A writable NodeMap path must start with a property name");
        }

        List<WriteStep> remaining = steps.subList(1, steps.size());
        if (singletonRoot || property.name().startsWith("_")) {
            applySingletonRoot(root, property.name(), remaining, value);
        } else {
            applyCollectionRoot(root, property.name(), remaining, value);
        }
    }

    private static void applySingletonRoot(
            ObjectNode root,
            String name,
            List<WriteStep> remaining,
            JsonNode value) {

        if (remaining.isEmpty()) {
            root.set(name, copy(value));
            return;
        }

        JsonNode current = root.get(name);
        WriteStep first = remaining.getFirst();
        if (isMissing(current) || !accepts(current, first)) {
            if (creationBlockedByWildcard(remaining, 0)) {
                return;
            }
            current = containerFor(first);
            root.set(name, current);
        }
        applySteps(current, remaining, 0, value);
    }

    private static void applyCollectionRoot(
            ObjectNode root,
            String name,
            List<WriteStep> remaining,
            JsonNode value) {

        JsonNode current = root.get(name);
        if (remaining.isEmpty()) {
            ArrayNode collection = asCollection(root, name, current);
            collection.add(copy(value));
            return;
        }

        WriteStep first = remaining.getFirst();
        ArrayNode collection;
        if (current instanceof ArrayNode existingCollection) {
            collection = existingCollection;
        } else {
            if (first instanceof WildcardStep || creationBlockedByWildcard(remaining, 0)) {
                return;
            }
            collection = MAPPER.createArrayNode();
            root.set(name, collection);
        }

        if (first instanceof IndexStep || first instanceof AppendStep || first instanceof WildcardStep) {
            applySteps(collection, remaining, 0, value);
            return;
        }

        if (collection.isEmpty()) {
            if (creationBlockedByWildcard(remaining, 0)) {
                return;
            }
            collection.add(containerFor(first));
        }

        int lastIndex = collection.size() - 1;
        JsonNode selected = collection.get(lastIndex);
        if (isMissing(selected) || !accepts(selected, first)) {
            if (creationBlockedByWildcard(remaining, 0)) {
                return;
            }
            selected = containerFor(first);
            collection.set(lastIndex, selected);
        }
        applySteps(selected, remaining, 0, value);
    }

    private static ArrayNode asCollection(ObjectNode root, String name, JsonNode current) {
        if (current instanceof ArrayNode array) {
            return array;
        }

        ArrayNode array = MAPPER.createArrayNode();
        if (!isMissing(current)) {
            array.add(current);
        }
        root.set(name, array);
        return array;
    }

    private static void applySelected(ObjectNode root, Selected selected, JsonNode value) {
        JsonNode result = evaluate(root, selected.selector());
        if (isMissing(result)) {
            return;
        }

        IdentityHashMap<JsonNode, Boolean> attached = new IdentityHashMap<>();
        collectAttached(root, attached);

        List<JsonNode> matches = result instanceof ArrayNode array && !attached.containsKey(result)
                ? array.valueStream().toList()
                : List.of(result);

        WriteStep first = selected.suffix().getFirst();
        for (JsonNode match : matches) {
            if (isMissing(match)) {
                continue;
            }
            if (!attached.containsKey(match)) {
                throw new IllegalArgumentException(
                        "JSONata selector returned a detached or constructed value: " + selected.selector());
            }
            if (accepts(match, first)) {
                applySteps(match, selected.suffix(), 0, value);
            }
        }
    }

    private static void collectAttached(JsonNode node, IdentityHashMap<JsonNode, Boolean> attached) {
        if (node == null || attached.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (node instanceof ObjectNode object) {
            object.elements().forEachRemaining(child -> collectAttached(child, attached));
        } else if (node instanceof ArrayNode array) {
            array.forEach(child -> collectAttached(child, attached));
        }
    }

    private static boolean applySteps(
            JsonNode current,
            List<WriteStep> steps,
            int index,
            JsonNode value) {

        WriteStep step = steps.get(index);
        boolean last = index == steps.size() - 1;

        if (step instanceof PropertyStep property) {
            if (!(current instanceof ObjectNode object)) {
                throw typeError(property.name(), "ObjectNode", current);
            }
            if (last) {
                object.set(property.name(), copy(value));
                return true;
            }

            WriteStep next = steps.get(index + 1);
            JsonNode child = object.get(property.name());
            if (isMissing(child) || !accepts(child, next)) {
                if (creationBlockedByWildcard(steps, index + 1)) {
                    return false;
                }
                child = containerFor(next);
                object.set(property.name(), child);
            }
            return applySteps(child, steps, index + 1, value);
        }

        if (step instanceof IndexStep arrayIndex) {
            if (!(current instanceof ArrayNode array)) {
                throw typeError("[" + arrayIndex.index() + "]", "ArrayNode", current);
            }

            int resolved = resolveIndex(array, arrayIndex.index());
            if (last) {
                if (resolved < 0) {
                    return false;
                }
                ensureIndex(array, resolved);
                array.set(resolved, copy(value));
                return true;
            }

            if (resolved < 0 || resolved >= array.size()) {
                if (creationBlockedByWildcard(steps, index + 1)) {
                    return false;
                }
                resolved = arrayIndex.index() < 0 ? 0 : arrayIndex.index();
                ensureIndex(array, resolved);
            }

            WriteStep next = steps.get(index + 1);
            JsonNode child = array.get(resolved);
            if (isMissing(child) || !accepts(child, next)) {
                if (creationBlockedByWildcard(steps, index + 1)) {
                    return false;
                }
                child = containerFor(next);
                array.set(resolved, child);
            }
            return applySteps(child, steps, index + 1, value);
        }

        if (step instanceof AppendStep) {
            if (!(current instanceof ArrayNode array)) {
                throw typeError("[]", "ArrayNode", current);
            }
            if (last) {
                array.add(copy(value));
                return true;
            }

            JsonNode child = containerFor(steps.get(index + 1));
            array.add(child);
            return applySteps(child, steps, index + 1, value);
        }

        if (step instanceof WildcardStep) {
            if (!(current instanceof ArrayNode array) || array.isEmpty()) {
                return false;
            }
            if (last) {
                for (int element = 0; element < array.size(); element++) {
                    array.set(element, copy(value));
                }
                return true;
            }

            boolean changed = false;
            WriteStep next = steps.get(index + 1);
            for (JsonNode child : array) {
                if (accepts(child, next)) {
                    changed |= applySteps(child, steps, index + 1, value);
                }
            }
            return changed;
        }

        throw new IllegalStateException("Unknown write step: " + step);
    }

    private static boolean creationBlockedByWildcard(List<WriteStep> steps, int start) {
        for (int index = start; index < steps.size(); index++) {
            WriteStep step = steps.get(index);
            if (step instanceof AppendStep) {
                return false;
            }
            if (step instanceof WildcardStep) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode containerFor(WriteStep step) {
        return step instanceof IndexStep || step instanceof AppendStep || step instanceof WildcardStep
                ? MAPPER.createArrayNode()
                : MAPPER.createObjectNode();
    }

    private static boolean accepts(JsonNode node, WriteStep step) {
        if (isMissing(node)) {
            return false;
        }
        if (step instanceof IndexStep || step instanceof AppendStep || step instanceof WildcardStep) {
            return node instanceof ArrayNode;
        }
        return node instanceof ObjectNode;
    }

    private static int resolveIndex(ArrayNode array, int requested) {
        return requested < 0 ? array.size() + requested : requested;
    }

    private static void ensureIndex(ArrayNode array, int index) {
        while (array.size() <= index) {
            array.add(NullNode.instance);
        }
    }

    private static IllegalArgumentException typeError(
            String location,
            String expected,
            JsonNode actual) {

        String actualType = actual == null ? "missing" : actual.getNodeType().name();
        return new IllegalArgumentException(
                "Cannot write through " + location + ": expected " + expected + " but found " + actualType);
    }

    private static boolean isMissing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? NullNode.instance : value.deepCopy();
    }

    /* ------------------------------------------------------------------
       Lexical helpers
       ------------------------------------------------------------------ */

    private static int balancedBracketEnd(String input, int start) {
        int depth = 0;
        for (int index = start; index < input.length();) {
            int protectedEnd = protectedEnd(input, index);
            if (protectedEnd > index) {
                index = protectedEnd;
                continue;
            }

            char current = input.charAt(index++);
            if (current == '[') {
                depth++;
            } else if (current == ']' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int protectedEnd(String input, int index) {
        char current = input.charAt(index);
        if (current == '\'' || current == '"' || current == '`') {
            int end = quotedEnd(input, index, current);
            return end < 0 ? input.length() : end;
        }
        if (current == '/' && index + 1 < input.length() && input.charAt(index + 1) == '*') {
            int end = input.indexOf("*/", index + 2);
            return end < 0 ? input.length() : end + 2;
        }
        if (current == '/' && isRegexStart(input, index)) {
            int end = regexEnd(input, index);
            return end < 0 ? index : end;
        }
        return index;
    }

    private static int quotedEnd(String input, int start, char quote) {
        for (int index = start + 1; index < input.length(); index++) {
            char current = input.charAt(index);
            if (quote == '`' && current == '`'
                    && index + 1 < input.length() && input.charAt(index + 1) == '`') {
                index++;
                continue;
            }
            if (current == '\\') {
                index++;
            } else if (current == quote) {
                return index + 1;
            }
        }
        return -1;
    }

    private static boolean isRegexStart(String input, int slashIndex) {
        int previous = slashIndex - 1;
        while (previous >= 0 && Character.isWhitespace(input.charAt(previous))) {
            previous--;
        }
        return previous < 0 || "([{:;,=!?&|~<>+-*%".indexOf(input.charAt(previous)) >= 0;
    }

    private static int regexEnd(String input, int start) {
        boolean characterClass = false;
        for (int index = start + 1; index < input.length(); index++) {
            char current = input.charAt(index);
            if (current == '\\') {
                index++;
            } else if (current == '[') {
                characterClass = true;
            } else if (current == ']') {
                characterClass = false;
            } else if (current == '/' && !characterClass) {
                index++;
                while (index < input.length() && Character.isLetter(input.charAt(index))) {
                    index++;
                }
                return index;
            }
        }
        return -1;
    }

    private static String unquoteProperty(String token) {
        return token.substring(1, token.length() - 1).replace("``", "`");
    }

    private static int skipWhitespace(String input, int start) {
        int cursor = start;
        while (cursor < input.length() && Character.isWhitespace(input.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isTokenBoundary(String input, int index) {
        return index >= input.length()
                || (!Character.isLetterOrDigit(input.charAt(index)) && input.charAt(index) != '_');
    }

    /* ------------------------------------------------------------------
       Internal model
       ------------------------------------------------------------------ */

    private sealed interface WritePlan permits Direct, Selected, ReadOnly { }
    private record Direct(List<WriteStep> steps) implements WritePlan { }
    private record Selected(String selector, List<WriteStep> suffix) implements WritePlan { }
    private enum ReadOnly implements WritePlan { INSTANCE }

    private sealed interface WriteStep permits PropertyStep, IndexStep, AppendStep, WildcardStep { }
    private record PropertyStep(String name) implements WriteStep { }
    private record IndexStep(int index) implements WriteStep { }
    private enum AppendStep implements WriteStep { INSTANCE }
    private enum WildcardStep implements WriteStep { INSTANCE }

    private record DirectPath(List<WriteStep> steps) { }
    private record DirectProperty(WriteStep step, int end) { }
    private record BracketStep(WriteStep step, int end) { }
    private record SimplePath(
            String expression,
            int rootEnd,
            String rootName,
            boolean rootIndexed,
            boolean rootFunction) { }
    private record Property(String expression, String name, int end) { }
    private record Selector(String replacement, int end) { }
    private record NumberToken(long value, int end) { }
}
