package tools.dscode;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.mappings.ParsingMap.MappingDirectiveResolver;
import tools.dscode.internal.ValuesSupport;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;

import static tools.dscode.common.reporting.logging.LogForwarder.logError;

/**
 * Consumer-facing values, soft failure, throttle, and custom reference prefixes.
 * One static import covers the methods. Thread safety is internal.
 */
public final class Values {
    private static final Context CONTEXT = new Context();

    static {
        ValuesSupport.bindContext(CONTEXT);
    }

    private static final ConcurrentHashMap<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object NULL_CACHE = new Object();
    private static final ConcurrentHashMap<String, ThrottleGate> THROTTLES = new ConcurrentHashMap<>();
    private static final ThreadLocal<ParsingMap> STANDALONE = new ThreadLocal<>();

    private Values() {
    }

    public static Object getValue(String query) {
        requireQuery(query);
        return activeMap().resolveWholeValue("<" + query + ">");
    }

    public static <T> T getValue(String query, Class<T> type) {
        return ValuesSupport.convert(getValue(query), type);
    }

    public static void saveValue(String name, Object value) {
        saveRunValue(name, value);
    }

    public static Object getRunValue(String query) {
        return read(threadMap("RUN"), query);
    }

    public static <T> T getRunValue(String query, Class<T> type) {
        return ValuesSupport.convert(getRunValue(query), type);
    }

    public static void saveRunValue(String name, Object value) {
        write(threadMap("RUN"), name, value, true);
    }

    public static Object getScenarioValue(String query) {
        return read(requireMap("SCENARIO"), query);
    }

    public static <T> T getScenarioValue(String query, Class<T> type) {
        return ValuesSupport.convert(getScenarioValue(query), type);
    }

    public static void saveScenarioValue(String name, Object value) {
        write(requireMap("SCENARIO"), name, value, false);
    }

    public static Object getRootScenarioValue(String query) {
        return read(requireMap("ROOT SCENARIO"), query);
    }

    public static <T> T getRootScenarioValue(String query, Class<T> type) {
        return ValuesSupport.convert(getRootScenarioValue(query), type);
    }

    public static void saveRootScenarioValue(String name, Object value) {
        write(requireMap("ROOT SCENARIO"), name, value, false);
    }

    public static Object getStepValue(String query) {
        return read(requireMap("STEP"), query);
    }

    public static <T> T getStepValue(String query, Class<T> type) {
        return ValuesSupport.convert(getStepValue(query), type);
    }

    public static void saveStepValue(String name, Object value) {
        write(requireMap("STEP"), name, value, false);
    }

    public static Object getDefaultValue(String query) {
        return read(threadMap("DEFAULT"), query);
    }

    public static <T> T getDefaultValue(String query, Class<T> type) {
        return ValuesSupport.convert(getDefaultValue(query), type);
    }

    public static void saveDefaultValue(String name, Object value) {
        write(threadMap("DEFAULT"), name, value, false);
    }

    public static Object getOverrideValue(String query) {
        return read(threadMap("OVERRIDE"), query);
    }

    public static <T> T getOverrideValue(String query, Class<T> type) {
        return ValuesSupport.convert(getOverrideValue(query), type);
    }

    public static void saveOverrideValue(String name, Object value) {
        write(threadMap("OVERRIDE"), name, value, false);
    }

    public static Object getSingletonValue(String query) {
        return read(threadMap("SINGLETON"), query);
    }

    public static <T> T getSingletonValue(String query, Class<T> type) {
        return ValuesSupport.convert(getSingletonValue(query), type);
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
        ValuesSupport.registerPrefix(prefix, function);
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
        ValuesSupport.rememberSecret(value);
        return PrefixValue.secret(ValuesSupport.unmask(value));
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
        String key = ValuesSupport.isMaskKey(name) ? ValuesSupport.storageKey(name, holder) : name;
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
