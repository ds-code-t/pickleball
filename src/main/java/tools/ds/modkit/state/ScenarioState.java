package tools.ds.modkit.state;

import com.google.common.collect.LinkedListMultimap;
import io.cucumber.core.backend.TestCaseState;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.Runner;
import io.cucumber.plugin.event.TestCase;
import tools.ds.modkit.executions.StepExecution;
import tools.ds.modkit.extensions.StepExtension;
import tools.ds.modkit.mappings.NodeMap;
import tools.ds.modkit.mappings.ParsingMap;
import tools.ds.modkit.util.CucumberQueryUtil;
import tools.ds.modkit.util.CucumberTagUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


import static tools.ds.modkit.blackbox.BlackBoxBootstrap.K_RUNNER;
import static tools.ds.modkit.blackbox.BlackBoxBootstrap.K_SCENARIO;
import static tools.ds.modkit.util.KeyFunctions.getPickleKey;
import static tools.ds.modkit.util.Reflect.*;
import static tools.ds.modkit.util.TableUtils.exampleHeaderValueMap;

public final class ScenarioState {

    private static final ThreadLocal<ScenarioState> STATE_TL = ThreadLocal.withInitial(ScenarioState::new);

    private ScenarioState() {
        // private constructor, to enforce controlled creation
    }

    /** Returns the current thread's ScenarioState (may be null if not set). */
    public static ScenarioState get() {
        return STATE_TL.get();
    }

    /** Creates and sets a new ScenarioState, overwriting any existing one. */
//    public static ScenarioState resetScenarioState() {
////        System.out.println("@@resetScenarioState");
////
////        ScenarioState newScenarioState = new ScenarioState();
////        ScenarioState oldScenarioState =  STATE_TL.get();
////        if(oldScenarioState != null)
////            newScenarioState.runner = oldScenarioState.runner;
////        STATE_TL.set(newScenarioState);
////        return newScenarioState;
//        ScenarioState scenarioState = getScenarioState();
//        scenarioState.parsingMap = new ParsingMap();
//        return getScenarioState();
//    }


    public static ScenarioState getScenarioState() {
        return STATE_TL.get();
    }

    private Map<String, NodeMap> scenarioMaps = new HashMap<>();

    public NodeMap getScenarioMap(Pickle pickle) {
        return scenarioMaps.computeIfAbsent(getPickleKey(pickle), k -> {
            LinkedListMultimap<String, String> map = exampleHeaderValueMap(pickle);
            if (map == null) return null;
            return new NodeMap(map);
        });
    }

    public StepExtension getCurrentStep() {
        return currentStep;
    }


    public void setCurrentStep(StepExtension currentStep) {
        this.currentStep = currentStep;
    }

    private StepExtension currentStep;

    public ParsingMap getParsingMap() {
        return parsingMap;
    }

    public NodeMap getRunMap() {
        return runMap;
    }



    public void mergeToRunMap(LinkedListMultimap<?, ?> obj) {
        runMap.merge(obj);
    }

    public void put(Object key, Object value) {
        if (key == null || (key instanceof String && ((String) key).isBlank()))
            throw new RuntimeException("key cannot be null or blank");
        if(key instanceof String stringKey)
        {


        }

        runMap.put(String.valueOf(key), value);
    }

    public Object get(Object key) {
        return parsingMap.get(key);
    }

    public Object resolve(String key) {
        return parsingMap.resolveWholeText(key);
    }

    public void setParsingMap(ParsingMap parsingMap) {
        this.parsingMap = parsingMap;
    }

    private ParsingMap parsingMap = new ParsingMap();
    private final NodeMap runMap = parsingMap.getPrimaryRunMap();



    /**
     * Per-thread store lives inside the ScenarioState instance.
     */
    private final Map<Object, Object> store = new ConcurrentHashMap<>();
    private final Map<Object, Object> templateStore = new ConcurrentHashMap<>();


    public TestCase testCase;
    public io.cucumber.core.gherkin.Pickle scenarioPickle;
    public CucumberQueryUtil.GherkinView gherkinView;
    public EventBus bus;
    public TestCaseState state;

    public StepExecution stepExecution;
    public Runner runner;

    public EventBus getBus() {
        return bus;
    }

    public TestCaseState getTestCaseState() {
        return state;
    }

//    private ScenarioState(TestCase testCase, EventBus bus, io.cucumber.core.backend.TestCaseState state) {
//        this.bus = bus;
//        this.state = state;
//        this.testCase = testCase;
//        this.scenarioPickle = (io.cucumber.core.gherkin.Pickle) getProperty(testCase, "pickle");
//        this.stepExecution = new StepExecution(testCase);
//    }

    public static void setScenarioStateValues(TestCase testCase, EventBus bus, io.cucumber.core.backend.TestCaseState state) {
        ScenarioState scenarioState = getScenarioState();
        scenarioState.bus = bus;
        scenarioState.state = state;
        scenarioState.testCase = testCase;
        scenarioState.scenarioPickle = (io.cucumber.core.gherkin.Pickle) getProperty(testCase, "pickle");
        scenarioState.gherkinView = CucumberQueryUtil.describe(scenarioState.scenarioPickle);
        scenarioState.stepExecution = new StepExecution(testCase);
        scenarioState.runner = scenarioState.getRunner();
//        scenarioState.clear();
    }



    public StepExecution getStepExecution() {
        return stepExecution;
    }

    public TestCase getTestCase() {
        return testCase;
    }



    /* ========================= lifecycle ========================= */

//    /**
//     * Install a fresh ScenarioState on this thread, cleaning up any previous one.
//     */
//    public static ScenarioState beginNew(TestCase testCase, EventBus bus, io.cucumber.core.backend.TestCaseState state) {
//        ScenarioState prev = STATE_TL.get();
//        if (prev != null) prev.onClose();
//        ScenarioState next = new ScenarioState(testCase, bus, state);
//        STATE_TL.set(next);
//        return next;
//    }

    /**
     * Replace with a provided instance (rare). Cleans up any previous one.
     */
    public static void set(ScenarioState replacement) {
        Objects.requireNonNull(replacement, "replacement");
        ScenarioState prev = STATE_TL.get();
        if (prev != null && prev != replacement) prev.onClose();
        STATE_TL.set(replacement);
    }


    /**
     * End of scenario: clear and detach from thread to avoid leaks.
     */
    public static void end() {
        ScenarioState prev = STATE_TL.get();
        if (prev != null) prev.onClose();
        STATE_TL.remove();
    }

    /**
     * Optional: clear contents but keep this instance bound to the thread.
     */
    public void clear() {
        store.clear();
    }

    /**
     * Internal cleanup hook.
     */
    private void onClose() {
        store.clear();
    }

    /* ========================= registry ops (thread-local) ========================= */

    /**
     * Register the same value under each provided key for this thread.
     */
    public void register(Object value, Object... keys) {
        if (value == null) return;
        if (keys == null || keys.length == 0) {
            store.put(value.getClass(), value);
            return;
        }
        for (Object k : keys) {
            if (k != null) store.put(k, value);
        }
    }

    public Object getInstance(Object key) {
        return (key == null) ? null : store.get(key);
    }

    private final String keyFlag = "_TemplateKey_\u206AMETA";

    public Map<Object, Object> getKeyedTemplateMap(String key) {
        if (key == null || key.isBlank())
            return null;
        key = keyFlag + key.trim();
        return (Map<Object, Object>) store.computeIfAbsent(key, k -> new HashMap<>());
    }

    public void setKeyedTemplate(String uniqueObjectKey, Object key, Object value) {
        Map<Object, Object> map = getKeyedTemplateMap(uniqueObjectKey);
        if (map == null) throw new RuntimeException("uniqueObjectKey is null or empty.  Cannot put " + value);
        map.put(key, value);
    }

    public Object getKeyedTemplate(String uniqueObjectKey, Object key) {
        Map<Object, Object> map = (Map<Object, Object>) store.get(keyFlag + uniqueObjectKey);
        if (map == null) return null;
        return map.get(key);
    }


    public <T> T getInstance(Object key, Class<T> type) {
        if (key == null || type == null) return null;
        Object v = store.get(key);
        return type.isInstance(v) ? type.cast(v) : null;
    }

    public void remove(Object key) {
        if (key == null) return;
        store.remove(key);
    }

    /* ========================= convenience getters ========================= */
    public io.cucumber.core.gherkin.Pickle getScenarioPickle() {
        return scenarioPickle;
    }

//    public Object getScenario() {
//        return getInstance(K_SCENARIO);
//    }

    public Runner getRunner() {
        if (runner == null)
            return (Runner) getInstance(K_RUNNER);
        return runner;
    }


//    public io.cucumber.core.runtime.Runtime getRuntime() {
//        return (io.cucumber.core.runtime.Runtime) get(K_RUNTIME);
//    }


    public String getTestCaseName() {
        return nameOf(getTestCase());
    }

    public String getPickleName() {
        return nameOf(getScenarioPickle());
    }


    public String getPickleLanguage() {
        return getScenarioPickle().getLanguage();
    }

//    public String getScenarioName() {
//        return nameOf(getScenario());
//    }

    public io.cucumber.core.runner.Options getRuntimeOptions() {
        return (io.cucumber.core.runner.Options) getProperty(getRunner(), "runnerOptions");
    }

    public List<String> getTags() {
        return CucumberTagUtils.extractTags(getRuntimeOptions());
    }


    public CucumberQueryUtil.GherkinView getCucumberQuery() {
        return CucumberQueryUtil.describe(getScenarioPickle());
    }


}
