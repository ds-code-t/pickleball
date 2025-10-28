//package io.cucumber.core.runner;
//
////import com.google.common.collect.LinkedListMultimap;
//
//import com.google.common.collect.LinkedListMultimap;
//import io.cucumber.core.eventbus.EventBus;
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.core.runner.util.CucumberQueryUtil;
//import io.cucumber.core.runner.util.CucumberTagUtils;
//import io.cucumber.gherkin.GherkinDialect;
//import io.cucumber.gherkin.GherkinDialects;
//import tools.dscode.common.mappings.NodeMap;
//import tools.dscode.common.mappings.ParsingMap;
//
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.Objects;
//import java.util.concurrent.ConcurrentHashMap;
//
//import static io.cucumber.core.runner.util.KeyFunctions.getUniqueKey;
//import static io.cucumber.core.runner.util.TableUtils.exampleHeaderValueMap;
//import static tools.dscode.registry.GlobalRegistry.localOrGlobalOf;
//import static tools.dscode.common.util.Reflect.getProperty;
//import static tools.dscode.common.util.Reflect.nameOf;
//
////import static tools.dscode.common.SelfRegistering.localOrGlobalOf;
////import static tools.dscode.common.util.Reflect.getProperty;
////import static tools.dscode.common.util.Reflect.nameOf;
////import static tools.dscode.util.KeyFunctions.getUniqueKey;
////import static tools.dscode.util.TableUtils.exampleHeaderValueMap;
//
//public final class ScenarioState {
//
//    private static final ThreadLocal<ScenarioState> STATE_TL = ThreadLocal.withInitial(ScenarioState::new);
//
//    private ScenarioState() {
//        // private constructor, to enforce controlled creation
//    }
//
//    public static ScenarioState getScenarioState() {
//        return STATE_TL.get();
//    }
//
//    private final Map<String, NodeMap> scenarioMaps = new HashMap<>();
//
//    public NodeMap getScenarioMap(Pickle pickle) {
//        return scenarioMaps.computeIfAbsent(getUniqueKey(pickle), k -> {
//            LinkedListMultimap<String, String> map = exampleHeaderValueMap(pickle);
//            if (map == null)
//                return null;
//            return new NodeMap(map);
//        });
//    }
//
//    public static StepExtension getCurrentStep() {
//        return getScenarioState().currentStep;
//    }
//
//    public static void setCurrentStep(StepExtension currentStep) {
//        getScenarioState().currentStep = currentStep;
//    }
//
//    private StepExtension currentStep;
//
//    public ParsingMap getParsingMap() {
//        return parsingMap;
//    }
//
//    public NodeMap getRunMap() {
//        return runMap;
//    }
//
//    public void mergeToRunMap(LinkedListMultimap<?, ?> obj) {
//        runMap.merge(obj);
//    }
//
//    public void put(Object key, Object value) {
//        if (key == null || (key instanceof String && ((String) key).isBlank()))
//            throw new RuntimeException("key cannot be null or blank");
//        if (key instanceof String stringKey) {
//
//        }
//
//        runMap.put(String.valueOf(key), value);
//    }
//
//    public Object get(Object key) {
//        return parsingMap.get(key);
//    }
//
//    public Object resolve(String key) {
//        return parsingMap.resolveWholeText(key);
//    }
//
//    public void setParsingMap(ParsingMap parsingMap) {
//        this.parsingMap = parsingMap;
//    }
//
//    private ParsingMap parsingMap = new ParsingMap();
//    private final NodeMap runMap = parsingMap.getPrimaryRunMap();
//
//    /**
//     * Per-thread store lives inside the ScenarioState instance.
//     */
//    private final Map<Object, Object> store = new ConcurrentHashMap<>();
//    private final Map<Object, Object> templateStore = new ConcurrentHashMap<>();
//
//    public CucumberQueryUtil.GherkinView gherkinView;
//    public EventBus bus;
//
//    public TestCaseExtension testCaseExtension;
//    public Runner runner;
//
//    public static EventBus getBus() {
//        return getRunner().getBus();
//    }
//
//    public static io.cucumber.core.runner.TestCaseState getTestCaseState() {
//        return localOrGlobalOf(io.cucumber.core.runner.TestCaseState.class);
//    }
//
//    public TestCaseExtension getStepExecution() {
//        return testCaseExtension;
//    }
//
//    /**
//     * Replace with a provided instance (rare). Cleans up any previous one.
//     */
//    public static void set(ScenarioState replacement) {
//        Objects.requireNonNull(replacement, "replacement");
//        ScenarioState prev = STATE_TL.get();
//        if (prev != null && prev != replacement)
//            prev.onClose();
//        STATE_TL.set(replacement);
//    }
//
//    /**
//     * End of scenario: clear and detach from thread to avoid leaks.
//     */
//    public static void end() {
//        ScenarioState prev = STATE_TL.get();
//        if (prev != null)
//            prev.onClose();
//        STATE_TL.remove();
//    }
//
//    /**
//     * Optional: clear contents but keep this instance bound to the thread.
//     */
//    public void clear() {
//        store.clear();
//    }
//
//    /**
//     * Internal cleanup hook.
//     */
//    private void onClose() {
//        store.clear();
//    }
//
//    /*
//     * ========================= registry ops (thread-local)
//     * =========================
//     */
//
//    /**
//     * Register the same value under each provided key for this thread.
//     */
//    public void register(Object value, Object... keys) {
//        if (value == null)
//            return;
//        if (keys == null || keys.length == 0) {
//            store.put(value.getClass(), value);
//            return;
//        }
//        for (Object k : keys) {
//            if (k != null)
//                store.put(k, value);
//        }
//    }
//
//    public Object getInstance(Object key) {
//        return (key == null) ? null : store.get(key);
//    }
//
//    private static final String keyFlag = "_TemplateKey_\u206AMETA";
//
//    public Map<Object, Object> getKeyedTemplateMap(String key) {
//        if (key == null || key.isBlank())
//            return null;
//        key = keyFlag + key.trim();
//        return (Map<Object, Object>) store.computeIfAbsent(key, k -> new HashMap<>());
//    }
//
//    // public static void setKeyedTemplate(String uniqueObjectKey, Object key,
//    // Object value) {
//    // Map<Object, Object> map =
//    // getScenarioState().getKeyedTemplateMap(uniqueObjectKey);
//    // if (map == null)
//    // throw new RuntimeException("uniqueObjectKey is null or empty. Cannot put
//    // " + value);
//    // map.put(key, value);
//    // }
//    //
//    // public Object getKeyedTemplate(String uniqueObjectKey, Object key) {
//    // Map<Object, Object> map = (Map<Object, Object>)
//    // getScenarioState().store.get(keyFlag + uniqueObjectKey);
//    // if (map == null)
//    // return null;
//    // return map.get(key);
//    // }
//
//    public static void setKeyedTemplate(Object object, Object key, Object value) {
//        Map<Object, Object> map = getScenarioState().getKeyedTemplateMap(getUniqueKey(object));
//        if (map == null)
//            throw new RuntimeException("uniqueObjectKey is null or empty.  Cannot put " + value);
//        map.put(key, value);
//    }
//
//    public Object getKeyedTemplate(Object object, Object key) {
//        Map<Object, Object> map = (Map<Object, Object>) getScenarioState().store.get(keyFlag + getUniqueKey(object));
//        if (map == null)
//            return null;
//        return map.get(key);
//    }
//
//    public <T> T getInstance(Object key, Class<T> type) {
//        if (key == null || type == null)
//            return null;
//        Object v = store.get(key);
//        return type.isInstance(v) ? type.cast(v) : null;
//    }
//
//    public void remove(Object key) {
//        if (key == null)
//            return;
//        store.remove(key);
//    }
//
//    /*
//     * ========================= convenience getters =========================
//     */
//    public static Pickle getScenarioPickle() {
//        return (Pickle) getProperty(getTestCase(), "pickle");
//    }
//
//    public static Runner getRunner() {
//        return localOrGlobalOf(Runner.class);
//    }
//
//    public static String getTestCaseName() {
//        return nameOf(getTestCase());
//    }
//
//    public static String getPickleName() {
//        return nameOf(getScenarioPickle());
//    }
//
//    public static String getPickleLanguage() {
//        return getScenarioPickle().getLanguage();
//    }
//
//    public static GherkinDialect getDialect() {
//        return GherkinDialects.getDialect(getPickleLanguage())
//                .orElse(GherkinDialects.getDialect("en").get());
//    }
//
//    public static String getKeyword() {
//        return getDialect().getGivenKeywords().get(0);
//    }
//
//    public static io.cucumber.core.runner.Options getRuntimeOptions() {
//        return (io.cucumber.core.runner.Options) getProperty(getRunner(), "runnerOptions");
//    }
//
//    public static io.cucumber.core.runtime.Runtime getRuntime() {
//        return localOrGlobalOf(io.cucumber.core.runtime.Runtime.class);
//    }
//
//    public static io.cucumber.core.feature.FeatureParser getFeatureParser() {
//        return localOrGlobalOf(io.cucumber.core.feature.FeatureParser.class);
//    }
//
//    public static io.cucumber.core.runtime.FeatureSupplier getFeatureSupplier() {
//        return localOrGlobalOf(io.cucumber.core.runtime.FeaturePathFeatureSupplier.class);
//    }
//
//    public static TestCase getTestCase() {
//        return localOrGlobalOf(TestCase.class);
//    }
//
//
//
//    public static List<String> getTags() {
//        return CucumberTagUtils.extractTags(getRuntimeOptions());
//    }
//
//    public static CucumberQueryUtil.GherkinView getCucumberQuery() {
//        return CucumberQueryUtil.describe(getScenarioPickle());
//    }
//
//}
