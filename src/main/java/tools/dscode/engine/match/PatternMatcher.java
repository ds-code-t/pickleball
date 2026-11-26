package tools.dscode.engine.match;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches a step pattern the way cucumber-expressions 18.0.1 does.
 * A pattern that starts with {@code ^}, ends with {@code $}, or is wrapped in
 * {@code /.../} is a regular expression. Anything else is a Cucumber expression.
 * Captures are the top-level groups, and a nested capturing group replaces its parent
 * value the way {@code Group.getValues} does. This class does not import Cucumber.
 */
public final class PatternMatcher {
    private static final Pattern PARAMETER_PATTERN = Pattern.compile("((?:\\\\){0,2})\\{([^}]*)\\}");
    private static final List<String> INTEGER_REGEXPS = List.of(
            Pattern.compile("-?\\d+").pattern(),
            Pattern.compile("\\d+").pattern());
    private static final List<String> WORD_REGEXPS = List.of(Pattern.compile("[^\\s]+").pattern());
    private static final List<String> STRING_REGEXPS = List.of(
            Pattern.compile("\"([^\"\\\\]*(\\\\.[^\"\\\\]*)*)\"").pattern(),
            Pattern.compile("'([^'\\\\]*(\\\\.[^'\\\\]*)*)'").pattern());
    private static final String ANONYMOUS = Pattern.compile(".*").pattern();
    private static final String FLOAT_TEMPLATE =
            Pattern.compile("(?=.*\\d.*)[-+]?(?:\\d+(?:[{group}]?\\d+)*)*(?:[{decimal}](?=\\d.*))?\\d*(?:\\d+[{expnt}]-?\\d+)?").pattern();

    private PatternMatcher() {
    }

    /** A translated pattern. {@code rejected} leaves the previous row alone. */
    public record Translation(String regex, String parameterTypesJson, boolean rejected, String message) {
    }

    /**
     * Matches {@code pattern} against {@code text}.
     * {@code storedTypes} is the JSON array from {@code step_definitions.parameter_types}.
     * Null or {@code []} classifies the pattern. A non-empty array is a translated
     * Cucumber expression and those names are the parameter types.
     * Returns null when the text does not match. A null element is an unmatched group.
     */
    public static List<String> match(String pattern, String text, String storedTypes, Type... hints) {
        String body = text == null ? "" : text;
        List<String> names = namesOf(storedTypes);
        if (!names.isEmpty()) {
            return matchTyped(pattern == null ? "" : pattern, body, names, hints, Pattern.UNICODE_CHARACTER_CLASS);
        }
        Compiled compiled = compile(pattern == null ? "" : pattern);
        if (compiled.regex) {
            return matchRegex(compiled.pattern, body, hints);
        }
        return matchTyped(compiled.pattern, body, compiled.names, hints, Pattern.UNICODE_CHARACTER_CLASS);
    }

    public static List<String> match(String pattern, String text, Type... hints) {
        return match(pattern, text, null, hints);
    }

    /**
     * Translates a glue annotation into a stored regex and a parameter-type array.
     * A {@code /pattern/} literal is stored with the slashes. {@link #match} still
     * treats that form as a regular expression. Storing only the inside would make
     * the next hydrate classify it as a Cucumber expression.
     */
    public static Translation translation(String pattern) {
        String source = pattern == null ? "" : pattern;
        try {
            Compiled compiled = compile(source);
            String stored = slashWrapped(source) ? source : compiled.pattern;
            return new Translation(stored, json(compiled.names), false, "");
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage() == null ? "pattern was not translated" : failure.getMessage();
            return new Translation(source, "[]", true, message);
        }
    }

    private static boolean slashWrapped(String expression) {
        return expression.length() >= 2
                && expression.charAt(0) == '/'
                && expression.charAt(expression.length() - 1) == '/';
    }

    private static List<String> matchRegex(String pattern, String text, Type... hints) {
        Pattern compiled = Pattern.compile(pattern);
        Captured captured = capture(compiled, text);
        if (captured == null) return null;
        List<String> values = new ArrayList<>();
        int hintIndex = 0;
        for (Node node : captured.nodes) {
            Type hint = hintIndex < hints.length ? hints[hintIndex++] : String.class;
            String type = lookup(node.text);
            if (type != null && !strong(type) && !typeOf(type).equals(hint)) type = null;
            if (type == null || "anonymous".equals(type)) type = "anonymous";
            values.add(transform(type, node.group.values(), hint));
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> matchTyped(String pattern, String text, List<String> names, Type[] hints, int flags) {
        Captured captured = capture(Pattern.compile(pattern, flags), text);
        if (captured == null) return null;
        if (captured.nodes.size() != names.size()) {
            throw new IllegalArgumentException("Group has " + captured.nodes.size()
                    + " capture groups, but there were " + names.size() + " parameter types");
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) {
            Type hint = hints != null && index < hints.length ? hints[index] : String.class;
            String type = names.get(index);
            if (type == null || type.isEmpty() || "anonymous".equals(type)) type = "anonymous";
            values.add(transform(type, captured.nodes.get(index).group.values(), hint));
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String transform(String type, List<String> groups, Type hint) {
        if ("string".equals(type)) {
            String arg = groups.isEmpty() ? null : groups.get(0);
            if (arg == null && groups.size() > 1) arg = groups.get(1);
            if (arg == null) return null;
            return arg.replace("\\\"", "\"").replace("\\'", "'");
        }
        if ("anonymous".equals(type)) {
            if (groups.size() > 1) {
                throw new IllegalArgumentException(
                        "Anonymous ParameterType has multiple capture groups. "
                                + "You can only use a single capture group in an anonymous ParameterType.");
            }
            String arg = groups.isEmpty() ? null : groups.get(0);
            return stringify(convert(arg, hint == null ? String.class : hint));
        }
        if (groups.size() > 1) {
            throw new IllegalArgumentException("ParameterType {" + type + "} has multiple capture groups");
        }
        String arg = groups.isEmpty() ? null : groups.get(0);
        if (arg == null) return null;
        return stringify(convert(arg, typeOf(type)));
    }

    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Object convert(String value, Type type) {
        if (value == null) return null;
        Class<?> target = type instanceof Class<?> clazz ? clazz : String.class;
        if (target == String.class || target == Object.class) return value;
        if (target == Integer.class || target == int.class) return Integer.decode(value);
        if (target == Long.class || target == long.class) return Long.decode(value);
        if (target == Short.class || target == short.class) return Short.decode(value);
        if (target == Byte.class || target == byte.class) return Byte.decode(value);
        if (target == BigInteger.class) return new BigInteger(value);
        if (target == BigDecimal.class) return new BigDecimal(value);
        if (target == Float.class || target == float.class) return Float.parseFloat(value);
        if (target == Double.class || target == double.class) return Double.parseDouble(value);
        if (target == Boolean.class || target == boolean.class) return Boolean.parseBoolean(value);
        return value;
    }

    private static Class<?> typeOf(String name) {
        return switch (name) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "biginteger" -> BigInteger.class;
            case "bigdecimal" -> BigDecimal.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            case "word", "string" -> String.class;
            default -> String.class;
        };
    }

    /** Built-in types other than a user type use a weak hint, matching 18.0.1. */
    private static boolean strong(String type) {
        return false;
    }

    /**
     * Preferential regexp match. {@code int} wins {@code -?\\d+} and {@code \\d+}.
     * {@code double} wins the localized float regexp. {@code .*} is anonymous.
     */
    private static String lookup(String source) {
        if (source == null) return null;
        if (INTEGER_REGEXPS.contains(source)) return "int";
        if (floatRegex().equals(source)) return "double";
        if (WORD_REGEXPS.contains(source)) return "word";
        if (STRING_REGEXPS.contains(source)) return "string";
        if (ANONYMOUS.equals(source)) return "anonymous";
        return null;
    }

    private static String floatRegex() {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.ENGLISH);
        return FLOAT_TEMPLATE
                .replace("{decimal}", String.valueOf(symbols.getDecimalSeparator()))
                .replace("{group}", String.valueOf(symbols.getGroupingSeparator()))
                .replace("{expnt}", symbols.getExponentSeparator());
    }

    private static Compiled compile(String expression) {
        if (expression.isEmpty()) {
            return cucumber(expression);
        }
        char first = expression.charAt(0);
        char last = expression.charAt(expression.length() - 1);
        if (first == '^' || last == '$') {
            try {
                Pattern.compile(expression);
            } catch (PatternSyntaxException failure) {
                if (PARAMETER_PATTERN.matcher(expression).find()) {
                    throw new IllegalArgumentException(
                            "You cannot use anchors (^ or $) in Cucumber Expressions. Please remove them from " + expression,
                            failure);
                }
                throw failure;
            }
            return new Compiled(true, expression, List.of());
        }
        if (first == '/' && last == '/') {
            return new Compiled(true, expression.substring(1, expression.length() - 1), List.of());
        }
        return cucumber(expression);
    }

    private static Compiled cucumber(String expression) {
        List<String> names = new ArrayList<>();
        String regex = rewrite(parse(expression), names);
        return new Compiled(false, regex, List.copyOf(names));
    }

    private static String rewrite(Node node, List<String> names) {
        return switch (node.kind) {
            case TEXT -> escapeRegex(node.text);
            case OPTIONAL -> "(?:" + join(node.children, names, "") + ")?";
            case ALTERNATION -> "(?:" + join(node.children, names, "|") + ")";
            case PARAMETER -> {
                names.add(node.text);
                yield capture(regexps(node.text));
            }
            case EXPRESSION -> "^" + join(node.children, names, "") + "$";
            case ALTERNATIVE -> join(node.children, names, "");
        };
    }

    private static String join(List<Node> nodes, List<String> names, String separator) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < nodes.size(); index++) {
            if (index > 0) out.append(separator);
            out.append(rewrite(nodes.get(index), names));
        }
        return out.toString();
    }

    private static String capture(List<String> regexps) {
        if (regexps.size() == 1) return "(" + regexps.get(0) + ")";
        return "((?:" + String.join(")|(?:", regexps) + "))";
    }

    private static List<String> regexps(String name) {
        if (name == null || name.isEmpty()) return List.of(ANONYMOUS);
        return switch (name) {
            case "int", "long", "short", "byte", "biginteger" -> INTEGER_REGEXPS;
            case "float", "double", "bigdecimal" -> List.of(floatRegex());
            case "word" -> WORD_REGEXPS;
            case "string" -> STRING_REGEXPS;
            default -> throw new IllegalArgumentException(
                    "Undefined parameter type {" + name + "}. "
                            + "Please register a ParameterType for {" + name + "}");
        };
    }

    private static Node parse(String expression) {
        return new Node(Kind.EXPRESSION, "", List.of(parseAlternation(expression, 0, expression.length())));
    }

    private static Node parseAlternation(String expression, int from, int to) {
        List<Node> alternatives = new ArrayList<>();
        int start = from;
        int depth = 0;
        for (int index = from; index < to; index++) {
            char current = expression.charAt(index);
            if (current == '\\' && index + 1 < to) {
                index++;
                continue;
            }
            if (current == '{') {
                int end = expression.indexOf('}', index + 1);
                if (end < 0 || end >= to) break;
                index = end;
                continue;
            }
            if (current == '(') depth++;
            else if (current == ')') depth = Math.max(0, depth - 1);
            else if (current == '/' && depth == 0) {
                alternatives.add(parseAlternative(expression, start, index));
                start = index + 1;
            }
        }
        alternatives.add(parseAlternative(expression, start, to));
        if (alternatives.size() == 1) return alternatives.get(0);
        for (Node alternative : alternatives) {
            if (!hasText(alternative)) {
                throw new IllegalArgumentException("Alternative may not be empty in " + expression);
            }
        }
        return new Node(Kind.ALTERNATION, "", alternatives);
    }

    private static Node parseAlternative(String expression, int from, int to) {
        List<Node> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int index = from; index < to; index++) {
            char current = expression.charAt(index);
            if (current == '\\' && index + 1 < to) {
                text.append(expression.charAt(index + 1));
                index++;
                continue;
            }
            if (current == '{') {
                int end = expression.indexOf('}', index + 1);
                if (end < 0 || end > to) {
                    throw new IllegalArgumentException("Undefined parameter type in " + expression);
                }
                flush(parts, text);
                String name = expression.substring(index + 1, end);
                if (!validName(name)) {
                    throw new IllegalArgumentException("Undefined parameter type {" + name + "}");
                }
                if (!name.isEmpty() && regexpsOrNull(name) == null) {
                    throw new IllegalArgumentException(
                            "Undefined parameter type {" + name + "}. "
                                    + "Please register a ParameterType for {" + name + "}");
                }
                parts.add(new Node(Kind.PARAMETER, name, List.of()));
                index = end;
                continue;
            }
            if (current == '(') {
                int end = matchingParen(expression, index, to);
                flush(parts, text);
                String inner = expression.substring(index + 1, end);
                if (inner.isEmpty() || !containsText(inner)) {
                    throw new IllegalArgumentException("Optional may not be empty in " + expression);
                }
                if (inner.indexOf('{') >= 0 || inner.indexOf('(') >= 0) {
                    throw new IllegalArgumentException("Optional may not contain a parameter or optional in " + expression);
                }
                parts.add(new Node(Kind.OPTIONAL, "", List.of(parseAlternative(inner, 0, inner.length()))));
                index = end;
                continue;
            }
            text.append(current);
        }
        flush(parts, text);
        if (parts.isEmpty()) return new Node(Kind.TEXT, "", List.of());
        if (parts.size() == 1) return parts.get(0);
        return new Node(Kind.ALTERNATIVE, "", parts);
    }

    private static boolean containsText(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' && index + 1 < value.length()) {
                return true;
            }
            if (current != '(' && current != ')' && current != '{' && current != '}') return true;
        }
        return false;
    }

    private static boolean hasText(Node node) {
        if (node.kind == Kind.TEXT) return !node.text.isEmpty();
        if (node.kind == Kind.PARAMETER) return true;
        for (Node child : node.children) {
            if (hasText(child)) return true;
        }
        return false;
    }

    private static int matchingParen(String expression, int open, int limit) {
        int depth = 0;
        for (int index = open; index < limit; index++) {
            char current = expression.charAt(index);
            if (current == '\\' && index + 1 < limit) {
                index++;
                continue;
            }
            if (current == '(') depth++;
            else if (current == ')') {
                depth--;
                if (depth == 0) return index;
            }
        }
        throw new IllegalArgumentException("Unclosed optional in " + expression);
    }

    private static void flush(List<Node> parts, StringBuilder text) {
        if (text.isEmpty()) return;
        parts.add(new Node(Kind.TEXT, text.toString(), List.of()));
        text.setLength(0);
    }

    private static List<String> regexpsOrNull(String name) {
        try {
            return regexps(name);
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static boolean validName(String name) {
        return name.isEmpty() || !name.chars().anyMatch(ch -> ch == '{' || ch == '}' || ch == '(' || ch == ')' || ch == '\\' || ch == '/');
    }

    static String escapeRegex(String text) {
        StringBuilder out = null;
        int block = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if ("^$[](){}\\.|?*+".indexOf(current) >= 0) {
                if (out == null) out = new StringBuilder(text.length() * 2);
                if (block < index) out.append(text, block, index);
                out.append('\\').append(current);
                block = index + 1;
            }
        }
        if (out == null) return text;
        if (block < text.length()) out.append(text, block, text.length());
        return out.toString();
    }

    private static Captured capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.matches()) return null;
        Builder root = groupTree(pattern.pattern());
        Iterator<Integer> indexes = java.util.stream.IntStream.rangeClosed(0, matcher.groupCount()).iterator();
        Group group = root.build(matcher, indexes);
        List<Node> nodes = new ArrayList<>();
        for (Builder child : root.children) {
            nodes.add(new Node(Kind.TEXT, child.source, List.of()));
        }
        List<Node> paired = new ArrayList<>();
        for (int index = 0; index < group.children.size(); index++) {
            paired.add(new Node(Kind.TEXT, root.children.get(index).source, List.of(), group.children.get(index)));
        }
        return new Captured(paired);
    }

    private static Builder groupTree(String source) {
        java.util.ArrayDeque<Builder> stack = new java.util.ArrayDeque<>();
        stack.push(new Builder(0));
        boolean escaping = false;
        boolean charClass = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '[' && !escaping) charClass = true;
            else if (current == ']' && !escaping) charClass = false;
            else if (current == '(' && !escaping && !charClass) {
                Builder builder = new Builder(index);
                if (nonCapturing(source, index)) builder.capturing = false;
                stack.push(builder);
            } else if (current == ')' && !escaping && !charClass) {
                Builder builder = stack.pop();
                if (builder.capturing) {
                    builder.source = source.substring(builder.start + 1, index);
                    stack.peek().children.add(builder);
                } else {
                    stack.peek().children.addAll(builder.children);
                }
            }
            escaping = current == '\\' && !escaping;
        }
        return stack.pop();
    }

    private static boolean nonCapturing(String source, int index) {
        if (source.charAt(index + 1) != '?') return false;
        if (source.charAt(index + 2) != '<') return true;
        return source.charAt(index + 3) == '=' || source.charAt(index + 3) == '!';
    }

    private static List<String> namesOf(String stored) {
        if (stored == null || stored.isBlank() || "[]".equals(stored.trim())) return List.of();
        String body = stored.trim();
        if (!body.startsWith("[") || !body.endsWith("]")) return List.of();
        String inner = body.substring(1, body.length() - 1).trim();
        if (inner.isEmpty()) return List.of();
        List<String> names = new ArrayList<>();
        for (String part : inner.split(",")) {
            String token = part.trim();
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                names.add(token.substring(1, token.length() - 1));
            } else {
                names.add(token);
            }
        }
        return names;
    }

    private static String json(List<String> names) {
        if (names.isEmpty()) return "[]";
        StringBuilder out = new StringBuilder("[");
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) out.append(',');
            out.append('"').append(names.get(index)).append('"');
        }
        return out.append(']').toString();
    }

    private enum Kind { TEXT, OPTIONAL, ALTERNATION, ALTERNATIVE, PARAMETER, EXPRESSION }

    private static final class Node {
        final Kind kind;
        final String text;
        final List<Node> children;
        final Group group;

        Node(Kind kind, String text, List<Node> children) {
            this(kind, text, children, null);
        }

        Node(Kind kind, String text, List<Node> children, Group group) {
            this.kind = kind;
            this.text = text == null ? "" : text;
            this.children = children;
            this.group = group;
        }
    }

    private static final class Compiled {
        final boolean regex;
        final String pattern;
        final List<String> names;

        Compiled(boolean regex, String pattern, List<String> names) {
            this.regex = regex;
            this.pattern = pattern;
            this.names = names;
        }
    }

    private static final class Captured {
        final List<Node> nodes;

        Captured(List<Node> nodes) {
            this.nodes = nodes;
        }
    }

    private static final class Builder {
        final int start;
        boolean capturing = true;
        String source = "";
        final List<Builder> children = new ArrayList<>();

        Builder(int start) {
            this.start = start;
        }

        Group build(Matcher matcher, Iterator<Integer> indexes) {
            int groupIndex = indexes.next();
            List<Group> built = new ArrayList<>();
            for (Builder child : children) built.add(child.build(matcher, indexes));
            return new Group(matcher.group(groupIndex), built);
        }
    }

    private static final class Group {
        final String value;
        final List<Group> children;

        Group(String value, List<Group> children) {
            this.value = value;
            this.children = children;
        }

        List<String> values() {
            List<Group> groups = children.isEmpty() ? List.of(this) : children;
            List<String> out = new ArrayList<>();
            for (Group group : groups) out.add(group.value);
            return out;
        }
    }
}
