package tools.dscode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.mappings.ParsingMap.MappingDirectiveResolver;
import tools.dscode.common.reporting.logging.Entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;

import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logError;

/**
 * Consumer-facing values, soft failure, throttle, and custom reference prefixes.
 * One static import covers the methods. Thread safety is internal.
 */
public final class Values {
    public static final Context CONTEXT = new Context();

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
    private static final ConcurrentHashMap<String, BiFunction<Context, String, PrefixValue>> PREFIXES =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object NULL_CACHE = new Object();
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, ThrottleGate> THROTTLES = new ConcurrentHashMap<>();
    private static final ThreadLocal<ParsingMap> STANDALONE = new ThreadLocal<>();

    private Values() {
    }

    public static Object getValue(String query) {
        requireQuery(query);
        return activeMap().resolveWholeValue("<" + query + ">");
    }

    public static <T> T getValue(String query, Class<T> type) {
        return convert(getValue(query), type);
    }

    public static void saveValue(String name, Object value) {
        saveRunValue(name, value);
    }

    public static Object getRunValue(String query) {
        return read(threadMap("RUN"), query);
    }

    public static <T> T getRunValue(String query, Class<T> type) {
        return convert(getRunValue(query), type);
    }

    public static void saveRunValue(String name, Object value) {
        write(threadMap("RUN"), name, value, true);
    }

    public static Object getScenarioValue(String query) {
        return read(requireMap("SCENARIO"), query);
    }

    public static <T> T getScenarioValue(String query, Class<T> type) {
        return convert(getScenarioValue(query), type);
    }

    public static void saveScenarioValue(String name, Object value) {
        write(requireMap("SCENARIO"), name, value, false);
    }

    public static Object getRootScenarioValue(String query) {
        return read(requireMap("ROOT SCENARIO"), query);
    }

    public static <T> T getRootScenarioValue(String query, Class<T> type) {
        return convert(getRootScenarioValue(query), type);
    }

    public static void saveRootScenarioValue(String name, Object value) {
        write(requireMap("ROOT SCENARIO"), name, value, false);
    }

    public static Object getStepValue(String query) {
        return read(requireMap("STEP"), query);
    }

    public static <T> T getStepValue(String query, Class<T> type) {
        return convert(getStepValue(query), type);
    }

    public static void saveStepValue(String name, Object value) {
        write(requireMap("STEP"), name, value, false);
    }

    public static Object getDefaultValue(String query) {
        return read(threadMap("DEFAULT"), query);
    }

    public static <T> T getDefaultValue(String query, Class<T> type) {
        return convert(getDefaultValue(query), type);
    }

    public static void saveDefaultValue(String name, Object value) {
        write(threadMap("DEFAULT"), name, value, false);
    }

    public static Object getOverrideValue(String query) {
        return read(threadMap("OVERRIDE"), query);
    }

    public static <T> T getOverrideValue(String query, Class<T> type) {
        return convert(getOverrideValue(query), type);
    }

    public static void saveOverrideValue(String name, Object value) {
        write(threadMap("OVERRIDE"), name, value, false);
    }

    public static Object getSingletonValue(String query) {
        return read(threadMap("SINGLETON"), query);
    }

    public static <T> T getSingletonValue(String query, Class<T> type) {
        return convert(getSingletonValue(query), type);
    }

    public static void saveSingletonValue(String name, Object value) {
        write(threadMap("SINGLETON"), name, value, false);
    }

    /**
     * Records a soft failure the way a failing soft assert is logged.
     * The calling step continues. The scenario is soft-failed, so
     * {@code RUN IF SCENARIO SOFT FAILED} runs, and Cucumber fails the scenario
     * after the remaining steps.
     */
    public static void softFail(String message) {
        String text = message == null || message.isBlank()
                ? "Soft failure"
                : message.trim();
        logError("SOFT ASSERT failed: " + text);
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null) {
            throw new IllegalStateException(
                    "softFail requires a running scenario."
            );
        }
        state.recordDeferredSoftFailure(text);
    }

    /**
     * Blocks until at least {@code seconds} have passed since the previous
     * {@code throttle} call with the same key in this JVM. The first call
     * does not wait.
     */
    public static void throttle(String key, int seconds) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("throttle key must not be blank.");
        }
        if (seconds < 0) {
            throw new IllegalArgumentException(
                    "throttle seconds must not be negative."
            );
        }
        ThrottleGate gate = THROTTLES.computeIfAbsent(key, ignored -> new ThrottleGate());
        synchronized (gate.lock) {
            long now = System.nanoTime();
            if (gate.lastReleasedNanos != Long.MIN_VALUE && seconds > 0) {
                long deadline = gate.lastReleasedNanos + java.util.concurrent.TimeUnit.SECONDS.toNanos(seconds);
                while (now < deadline) {
                    LockSupport.parkNanos(deadline - now);
                    if (Thread.currentThread().isInterrupted()) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("throttle was interrupted.");
                    }
                    now = System.nanoTime();
                }
            }
            gate.lastReleasedNanos = System.nanoTime();
        }
    }

    public static void registerPrefix(
            String prefix,
            BiFunction<Context, String, PrefixValue> function
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
        BiFunction<Context, String, PrefixValue> previous =
                PREFIXES.putIfAbsent(key, function);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Prefix '" + prefix.trim() + "' is already registered."
            );
        }
    }

    public static Object cacheGet(String key) {
        requireCacheKey(key);
        Object value = CACHE.get(key);
        return value == NULL_CACHE ? null : value;
    }

    public static void cachePut(String key, Object value) {
        requireCacheKey(key);
        CACHE.put(key, value == null ? NULL_CACHE : value);
    }

    public static PrefixValue secret(Object value) {
        rememberSecret(value);
        return PrefixValue.secret(unmask(value));
    }

    public static Object callPrefix(String source) {
        int colon = source == null ? -1 : source.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String prefix = source.substring(0, colon).trim();
        BiFunction<Context, String, PrefixValue> function =
                PREFIXES.get(prefix.toLowerCase(Locale.ROOT));
        if (function == null) {
            return null;
        }
        PrefixValue returned = function.apply(CONTEXT, source.substring(colon + 1));
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

    private static Object read(NodeMap map, String query) {
        requireQuery(query);
        MappingDirectiveResolver.DirectiveSpec spec =
                MappingDirectiveResolver.parseDirectiveSuffix(query);
        String base = spec.base() == null ? "" : spec.base();
        Object value = map.get(base);
        if (!spec.directives().isEmpty()) {
            value = MappingDirectiveResolver.applyDirectives(value, spec.directives());
        }
        return value;
    }

    private static void write(NodeMap map, String name, Object value, boolean runPut) {
        requireName(name);
        if (runPut) {
            activeMap().put(name, value);
            return;
        }
        Object[] holder = {value};
        String key = isMaskKey(name) ? storageKey(name, holder) : name;
        map.put(key, holder[0]);
    }

    private static ParsingMap activeMap() {
        if (GlobalState.getCurrentScenarioState() != null) {
            return ParsingMap.getRunningParsingMap();
        }
        if (MappingProcessor.getRunMap() == null) {
            MappingProcessor.resetCommonMaps();
        }
        ParsingMap local = STANDALONE.get();
        // A ParsingMap keeps the NodeMaps it captured at construction. resetCommonMaps()
        // replaces those ThreadLocals, so a cached standalone map would read and write a
        // different run map than getRunValue and saveRunValue.
        if (local == null || local.getPrimaryRunMap() != MappingProcessor.getRunMap()) {
            local = new ParsingMap();
            STANDALONE.set(local);
        }
        return local;
    }

    private static NodeMap threadMap(String name) {
        NodeMap map = switch (name) {
            case "RUN" -> MappingProcessor.getRunMap();
            case "DEFAULT" -> MappingProcessor.getDefaultsMap();
            case "OVERRIDE" -> MappingProcessor.getOverridesMap();
            case "SINGLETON" -> MappingProcessor.getSingletonMap();
            default -> null;
        };
        if (map == null) {
            MappingProcessor.resetCommonMaps();
            map = switch (name) {
                case "RUN" -> MappingProcessor.getRunMap();
                case "DEFAULT" -> MappingProcessor.getDefaultsMap();
                case "OVERRIDE" -> MappingProcessor.getOverridesMap();
                case "SINGLETON" -> MappingProcessor.getSingletonMap();
                default -> null;
            };
        }
        if (map == null) {
            throw new IllegalStateException("No " + name + " map is available.");
        }
        return map;
    }

    private static NodeMap requireMap(String name) {
        try {
            NodeMap map = NodeMap.getNodeMap(name);
            if (map == null) {
                throw new IllegalStateException("No " + name + " map is available.");
            }
            return map;
        } catch (NullPointerException exception) {
            throw new IllegalStateException(
                    "No " + name + " map is available.",
                    exception
            );
        }
    }

    private static void requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Value query must not be blank.");
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Value name must not be blank.");
        }
    }

    private static void requireCacheKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Cache key must not be blank.");
        }
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

    private static final class ThrottleGate {
        private final Object lock = new Object();
        private long lastReleasedNanos = Long.MIN_VALUE;
    }

    /**
     * Running-test view passed to a prefix function.
     * The methods are the value API.
     */
    public static final class Context {
        private Context() {
        }

        public Object getValue(String query) {
            return Values.getValue(query);
        }

        public <T> T getValue(String query, Class<T> type) {
            return Values.getValue(query, type);
        }

        public void saveValue(String name, Object value) {
            Values.saveValue(name, value);
        }

        public Object getRunValue(String query) {
            return Values.getRunValue(query);
        }

        public <T> T getRunValue(String query, Class<T> type) {
            return Values.getRunValue(query, type);
        }

        public void saveRunValue(String name, Object value) {
            Values.saveRunValue(name, value);
        }

        public Object getScenarioValue(String query) {
            return Values.getScenarioValue(query);
        }

        public <T> T getScenarioValue(String query, Class<T> type) {
            return Values.getScenarioValue(query, type);
        }

        public void saveScenarioValue(String name, Object value) {
            Values.saveScenarioValue(name, value);
        }

        public Object getRootScenarioValue(String query) {
            return Values.getRootScenarioValue(query);
        }

        public <T> T getRootScenarioValue(String query, Class<T> type) {
            return Values.getRootScenarioValue(query, type);
        }

        public void saveRootScenarioValue(String name, Object value) {
            Values.saveRootScenarioValue(name, value);
        }

        public Object getStepValue(String query) {
            return Values.getStepValue(query);
        }

        public <T> T getStepValue(String query, Class<T> type) {
            return Values.getStepValue(query, type);
        }

        public void saveStepValue(String name, Object value) {
            Values.saveStepValue(name, value);
        }

        public Object getDefaultValue(String query) {
            return Values.getDefaultValue(query);
        }

        public <T> T getDefaultValue(String query, Class<T> type) {
            return Values.getDefaultValue(query, type);
        }

        public void saveDefaultValue(String name, Object value) {
            Values.saveDefaultValue(name, value);
        }

        public Object getOverrideValue(String query) {
            return Values.getOverrideValue(query);
        }

        public <T> T getOverrideValue(String query, Class<T> type) {
            return Values.getOverrideValue(query, type);
        }

        public void saveOverrideValue(String name, Object value) {
            Values.saveOverrideValue(name, value);
        }

        public Object getSingletonValue(String query) {
            return Values.getSingletonValue(query);
        }

        public <T> T getSingletonValue(String query, Class<T> type) {
            return Values.getSingletonValue(query, type);
        }

        public void saveSingletonValue(String name, Object value) {
            Values.saveSingletonValue(name, value);
        }
    }
}
