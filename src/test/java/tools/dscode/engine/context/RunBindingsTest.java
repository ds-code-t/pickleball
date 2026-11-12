package tools.dscode.engine.context;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import tools.dscode.common.dataelements.DataContext;
import tools.dscode.common.dataelements.DataContextFactory;
import tools.dscode.common.dataelements.DataElementKind;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mapping, phrases, and data use ExecutionContext with no Cucumber thread local. */
class RunBindingsTest {
    public static class ProbeGlue {
        static boolean sawNullThreadLocal;
        static boolean rootMatched;
        static boolean dataMatched;

        @Given("probe bindings")
        public void probe() {
            sawNullThreadLocal = CurrentScenarioState.currentScenarioState.get() == null;
            ExecutionContext context = ExecutionContext.current();
            rootMatched = context != null
                    && context.rootStep() != null
                    && RunBindings.of(context).scenarioState() == context.legacy()
                    && NodeMap.getNodeMap("SCENARIO ROOT", context) == context.rootStep().getDefaultStepNodeMap();
            DataContext data = DataContextFactory.create("bound", DataElementKind.DATA_VALUE, context);
            dataMatched = data.executionContext() == context;
        }

        @Given("native read {string}")
        public String read(String key) {
            Object value = MappingProcessor.getRunMap() == null ? null : MappingProcessor.getRunMap().get(key);
            return value == null ? "" : String.valueOf(value);
        }
    }

    @Test
    void nodeMapAndDataUseThePassedContextWithoutACucumberScenario() {
        if (MappingProcessor.getRunMap() == null) MappingProcessor.resetCommonMaps();
        assertNull(CurrentScenarioState.currentScenarioState.get());
        try (ExecutionContext context = ExecutionContext.open(EngineScenario.of("S", List.of()))) {
            CurrentScenarioState state = new CurrentScenarioState("S", "Feature");
            context.attachLegacy(state);
            ScenarioStep root = new ScenarioStep("S");
            context.rootStep(root);
            assertNull(CurrentScenarioState.currentScenarioState.get());
            assertSame(state, RunBindings.of(context).scenarioState());
            assertSame(root, RunBindings.of(context).rootScenarioStep());
            assertSame(root.getDefaultStepNodeMap(), NodeMap.getNodeMap("SCENARIO ROOT", context));
            DataContext data = DataContextFactory.create("bound", DataElementKind.DATA_VALUE, context);
            assertSame(context, data.executionContext());
        }
    }

    @Test
    void dynamicSaveStillRunsWhenTheCucumberThreadLocalStaysEmpty() {
        ProbeGlue.sawNullThreadLocal = false;
        ProbeGlue.rootMatched = false;
        ProbeGlue.dataMatched = false;
        String feature = """
                Feature: Bindings
                  Scenario: One
                    * , save "A" as "result"
                    * probe bindings
                    * native read "result"
                """;
        RunResult result = PickleballEngine.run(feature, EngineOptions.of(ProbeGlue.class));
        assertEquals(Status.PASSED, result.status(), result.toString());
        assertTrue(ProbeGlue.sawNullThreadLocal);
        assertTrue(ProbeGlue.rootMatched);
        assertTrue(ProbeGlue.dataMatched);
        assertNull(CurrentScenarioState.currentScenarioState.get());
    }
}
