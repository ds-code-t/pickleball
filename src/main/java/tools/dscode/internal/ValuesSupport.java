package tools.dscode.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import tools.dscode.PrefixValue;
import tools.dscode.Values;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap.MappingDirectiveResolver;
import tools.dscode.common.reporting.logging.Entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

/**
 * Framework helpers for {@link Values}. These methods stay off {@code Values}
 * so a consumer static import does not see them.
 */
public final class ValuesSupport {
    private static final Set<String> BUILT_IN_PREFIXES = Set.of(
            "file",
            "classpath",
            "wd-file",
            "data",
            "config",
            "configs",
            "value",
            "run",
            "step",
            "scenario",
            "scenario root",
            "root scenario",
            "default",
            "override",
            "singleton",
            "parent",
            "global",
            "phrase",
            "passed",
            "example",
            "datatable",
            "docstring"
    );
    private static final ConcurrentHashMap<String, BiFunction<Values.Context, String, PrefixValue>> PREFIXES =
            new ConcurrentHashMap<>();
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static volatile Values.Context context;

    private ValuesSupport() {
    }

    public static void bindContext(Values.Context value) {
        if (value == null) {
            throw new IllegalArgumentException("Values context is required.");
        }
        context = value;
    }

    public static void registerPrefix(
            String prefix,
            BiFunction<Values.Context, String, PrefixValue> function
    ) {
        if (function == null) {
            throw new IllegalArgumentException("Prefix function is required.");
        }
        String key = normalizePrefix(prefix);
        if (BUILT_IN_PREFIXES.contains(key)) {
            throw new IllegalArgumentException(
                    "Prefix '" + prefix.trim()
                            + "' is built in and cannot be registered."
            );
        }
        BiFunction<Values.Context, String, PrefixValue> previous =
                PREFIXES.putIfAbsent(key, function);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Prefix '" + prefix.trim() + "' is already registered."
            );
        }
    }

    public static Object callPrefix(String source) {
        int colon = source == null ? -1 : source.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String prefix = source.substring(0, colon).trim();
        BiFunction<Values.Context, String, PrefixValue> function =
                PREFIXES.get(prefix.toLowerCase(Locale.ROOT));
        if (function == null) {
            return null;
        }
        PrefixValue returned = function.apply(context(), source.substring(colon + 1));
        if (returned == null) {
            throw new IllegalArgumentException(
                    "Prefix '" + prefix + "' returned null. "
                            + "Prefix functions must return a PrefixValue."
            );
        }
        if (returned.secret()) {
            rememberSecret(returned.value());
        }
        return returned;
    }

    public static boolean hasRegisteredPrefix(String source) {
        int colon = source == null ? -1 : source.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String prefix = source.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        return PREFIXES.containsKey(prefix);
    }

    public static boolean containsRegisteredPrefixReference(String input) {
        if (input == null || input.isEmpty() || PREFIXES.isEmpty()) {
            return false;
        }
        int angle = input.indexOf('<');
        while (angle >= 0) {
            int colon = input.indexOf(':', angle + 1);
            int close = input.indexOf('>', angle + 1);
            if (colon > angle + 1 && (close < 0 || colon < close)) {
                String prefix = input.substring(angle + 1, colon).trim().toLowerCase(Locale.ROOT);
                if (PREFIXES.containsKey(prefix)) {
                    return true;
                }
            }
            angle = input.indexOf('<', angle + 1);
        }
        int book = input.indexOf("~[~");
        while (book >= 0) {
            int start = book + 3;
            int colon = input.indexOf(':', start);
            if (colon > start) {
                String prefix = input.substring(start, colon).trim().toLowerCase(Locale.ROOT);
                if (PREFIXES.containsKey(prefix)) {
                    return true;
                }
            }
            book = input.indexOf("~[~", book + 3);
        }
        return false;
    }

    public static Object mask(Object value) {
        Object plain = unmask(value);
        rememberSecret(plain);
        return PrefixValue.secret(plain);
    }

    public static Object unmask(Object value) {
        Object current = value;
        while (current instanceof PrefixValue prefix) {
            current = prefix.value();
        }
        return current;
    }

    public static String xmlEscape(Object value) {
        String text = plainText(unmask(value));
        if (text == null) {
            text = "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    public static boolean isMaskKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        try {
            return MappingDirectiveResolver.parseDirectiveSuffix(key).has("MASK");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static String storageKey(String key, Object[] valueHolder) {
        MappingDirectiveResolver.DirectiveSpec spec =
                MappingDirectiveResolver.parseDirectiveSuffix(key);
        if (!spec.has("MASK")) {
            return key;
        }
        valueHolder[0] = mask(valueHolder[0]);
        String base = spec.base() == null ? "" : spec.base().trim();
        if (base.isBlank()) {
            throw new IllegalArgumentException(
                    "A masked destination key must include a name before ~MASK;."
            );
        }
        if (spec.has("MERGE")) {
            return base + NodeMap.MERGE_SUFFIX;
        }
        return base;
    }

    public static String maskLog(String text) {
        if (text == null || text.isEmpty() || SECRETS.isEmpty()) {
            return text == null ? "" : text;
        }
        String result = text;
        List<String> secrets = new ArrayList<>(SECRETS);
        secrets.sort(Comparator.comparingInt(String::length).reversed());
        for (String secret : secrets) {
            if (secret == null || secret.isBlank() || PrefixValue.MASK.equals(secret)) {
                continue;
            }
            if (result.contains(secret)) {
                result = result.replace(secret, PrefixValue.MASK);
            }
        }
        return result;
    }

    public static void rememberSecret(Object value) {
        String text = plainText(unmask(value));
        if (text == null || text.isBlank() || PrefixValue.MASK.equals(text)) {
            return;
        }
        if (!SECRETS.add(text)) {
            return;
        }
        rewriteScenarioLog(text);
    }

    public static <T> T convert(Object value, Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Conversion type is required.");
        }
        Object plain = unmask(value);
        if (plain == null
                || plain instanceof NullNode
                || (plain instanceof JsonNode node && (node.isNull() || node.isMissingNode()))) {
            throw new IllegalArgumentException(
                    "Cannot convert a null value to " + type.getName() + "."
            );
        }
        try {
            if (type == String.class && plain instanceof String text) {
                return type.cast(text);
            }
            if (plain instanceof String text) {
                JsonNode parsed = MAPPER.readTree(text);
                return requireConverted(MAPPER.convertValue(parsed, type), type);
            }
            if (plain instanceof JsonNode node) {
                if (node.isTextual() && type != JsonNode.class && type != String.class) {
                    JsonNode parsed = MAPPER.readTree(node.textValue());
                    return requireConverted(MAPPER.convertValue(parsed, type), type);
                }
                if (node.isTextual() && type == String.class) {
                    return type.cast(node.textValue());
                }
                return requireConverted(MAPPER.convertValue(node, type), type);
            }
            if (type.isInstance(plain)) {
                return type.cast(plain);
            }
            return requireConverted(MAPPER.convertValue(plain, type), type);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().startsWith("Cannot convert")) {
                throw exception;
            }
            throw conversionFailure(type, exception);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw conversionFailure(type, exception);
        }
    }

    private static Values.Context context() {
        Values.Context bound = context;
        if (bound != null) {
            return bound;
        }
        try {
            Class.forName("tools.dscode.Values", true, ValuesSupport.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Values is not available.", exception);
        }
        bound = context;
        if (bound == null) {
            throw new IllegalStateException("Values context is not bound.");
        }
        return bound;
    }

    private static <T> T requireConverted(T converted, Class<T> type) {
        if (converted == null) {
            throw new IllegalArgumentException(
                    "Cannot convert a null value to " + type.getName() + "."
            );
        }
        return converted;
    }

    private static IllegalArgumentException conversionFailure(Class<?> type, Exception exception) {
        String detail = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        return new IllegalArgumentException(
                "Cannot convert value to " + type.getName() + ": " + detail,
                exception
        );
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || prefix.indexOf(':') >= 0) {
            throw new IllegalArgumentException(
                    "Prefix must be a non-blank name without a colon."
            );
        }
        return prefix.trim().toLowerCase(Locale.ROOT);
    }

    private static String plainText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof JsonNode node) {
            if (node.isTextual()) {
                return node.textValue();
            }
            if (node.isValueNode()) {
                return node.asText();
            }
        }
        return String.valueOf(value);
    }

    private static void rewriteScenarioLog(String secret) {
        try {
            CurrentScenarioState state = GlobalState.getCurrentScenarioState();
            if (state == null || state.scenarioLog == null) {
                return;
            }
            rewrite(state.scenarioLog, secret);
        } catch (RuntimeException ignored) {
            // Log masking must not fail the scenario.
        }
    }

    private static void rewrite(Entry entry, String secret) {
        if (entry == null) {
            return;
        }
        if (entry.text != null && entry.text.contains(secret)) {
            entry.text = entry.text.replace(secret, PrefixValue.MASK);
        }
        for (Entry child : entry.children) {
            rewrite(child, secret);
        }
    }
}
