package tools.dscode.engine.context;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.ScenarioStep;

import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit run state for the native engine. A live Cucumber
 * {@link CurrentScenarioState} thread local still wins in
 * {@code GlobalState.getCurrentScenarioState()}. This context is used only
 * when that thread local is empty, so an existing Cucumber run is unchanged.
 */
public final class ExecutionContext implements AutoCloseable {
    public enum Phase {
        READY,
        RUNNING,
        STEP,
        CLEANUP,
        CLOSED
    }

    private static final ThreadLocal<ExecutionContext> CURRENT = new ThreadLocal<>();

    private final String id = UUID.randomUUID().toString();
    private final EngineScenario scenario;
    private final Deque<EngineStep> stepStack = new ArrayDeque<>();
    private final Deque<Map<String, Object>> scopes = new ArrayDeque<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final Map<String, Object> resources = new LinkedHashMap<>();
    private final List<EngineStep> written = new ArrayList<>();
    private final ParsingMap parsingMap;
    private final NodeMap nodeMap;
    private final ExecutionContext previous;
    private final Instant opened = Instant.now();
    private Phase phase = Phase.READY;
    private CurrentScenarioState legacy;
    private ScenarioStep rootStep;
    private boolean closed;

    private ExecutionContext(EngineScenario scenario) {
        this.scenario = scenario == null ? EngineScenario.of("", List.of()) : scenario;
        this.previous = CURRENT.get();
        if (MappingProcessor.getRunMap() == null) {
            MappingProcessor.resetCommonMaps();
        }
        this.parsingMap = new ParsingMap(this);
        this.nodeMap = new NodeMap(MapConfigurations.MapType.RUN_MAP, this);
        this.parsingMap.getMaps().removeAll(MapConfigurations.MapType.RUN_MAP);
        this.parsingMap.addMaps(nodeMap);
        this.scopes.push(new LinkedHashMap<>());
        CURRENT.set(this);
        EngineLog.log(null, "context", id, "open " + this.scenario.name());
    }

    public static ExecutionContext open(EngineScenario scenario) {
        return new ExecutionContext(scenario);
    }

    public static ExecutionContext current() {
        return CURRENT.get();
    }

    /** Legacy scenario used only when Cucumber has not set its thread local. */
    public static CurrentScenarioState legacyOrNull() {
        ExecutionContext context = CURRENT.get();
        return context == null ? null : context.legacy;
    }

    public String id() {
        return id;
    }

    public EngineScenario scenario() {
        return scenario;
    }

    public Phase phase() {
        return phase;
    }

    public ExecutionContext phase(Phase next) {
        this.phase = next == null ? Phase.READY : next;
        return this;
    }

    public ParsingMap parsingMap() {
        return parsingMap;
    }

    public NodeMap nodeMap() {
        return nodeMap;
    }

    public Map<String, Object> scope() {
        return scopes.peek();
    }

    public ExecutionContext pushScope() {
        scopes.push(new LinkedHashMap<>());
        return this;
    }

    public ExecutionContext popScope() {
        if (scopes.size() > 1) scopes.pop();
        return this;
    }

    public List<EngineStep> stepStack() {
        return List.copyOf(stepStack);
    }

    public ExecutionContext pushStep(EngineStep step) {
        phase = Phase.STEP;
        stepStack.push(step == null ? EngineStep.of("", "") : step);
        return this;
    }

    public EngineStep popStep() {
        return stepStack.isEmpty() ? null : stepStack.pop();
    }

    public ExecutionContext fail(Throwable failure) {
        if (failure != null) failures.add(failure);
        return this;
    }

    public List<Throwable> failures() {
        return List.copyOf(failures);
    }

    public Map<String, Object> resources() {
        return resources;
    }

    public ExecutionContext resource(String name, Object value) {
        if (name != null && !name.isBlank()) resources.put(name, value);
        return this;
    }

    /** Steps written while this context is running. The engine drains them. */
    public ExecutionContext writeSteps(List<EngineStep> steps) {
        if (steps != null) written.addAll(steps);
        return this;
    }

    public List<EngineStep> drainWritten() {
        List<EngineStep> copy = List.copyOf(written);
        written.clear();
        return copy;
    }

    public Duration elapsed() {
        return Duration.between(opened, Instant.now());
    }

    public ExecutionContext attachLegacy(CurrentScenarioState state) {
        this.legacy = state;
        return this;
    }

    public CurrentScenarioState legacy() {
        return legacy;
    }

    /** Native scenario root. Cucumber roots stay on the test case. */
    public ExecutionContext rootStep(ScenarioStep root) {
        this.rootStep = root;
        return this;
    }

    public ScenarioStep rootStep() {
        return rootStep;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        phase = Phase.CLOSED;
        if (CURRENT.get() == this) {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
        EngineLog.log(null, "context", id, "close");
    }
}
