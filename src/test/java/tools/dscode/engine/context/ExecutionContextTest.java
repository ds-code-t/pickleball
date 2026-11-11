package tools.dscode.engine.context;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ExecutionContextTest {
    @Test
    void contextsDoNotShareScopesAndRestoreThePreviousContext() {
        EngineScenario scenario = EngineScenario.of("one", java.util.List.of(EngineStep.of("Given ", "a")));
        try (ExecutionContext outer = ExecutionContext.open(scenario)) {
            outer.scope().put("name", "outer");
            outer.parsingMap().put("map", "outer");
            outer.pushStep(scenario.steps().getFirst());
            try (ExecutionContext inner = ExecutionContext.open(EngineScenario.of("two", java.util.List.of()))) {
                assertSame(inner, ExecutionContext.current());
                assertNull(inner.scope().get("name"));
                assertNull(inner.parsingMap().get("map"));
                inner.scope().put("name", "inner");
                inner.pushScope();
                inner.scope().put("name", "nested");
                inner.popScope();
                assertEquals("inner", inner.scope().get("name"));
            }
            assertSame(outer, ExecutionContext.current());
            assertEquals("outer", outer.scope().get("name"));
            assertEquals("outer", outer.parsingMap().get("map"));
            assertEquals(1, outer.stepStack().size());
        }
        assertNull(ExecutionContext.current());
    }

    @Test
    void cucumberThreadLocalWinsAndContextLegacyIsUsedOnlyWhenItIsEmpty() {
        if (MappingProcessor.getRunMap() == null) MappingProcessor.resetCommonMaps();
        CurrentScenarioState nativeState = new CurrentScenarioState("native", "Feature");
        CurrentScenarioState live = new CurrentScenarioState("live", "Feature");
        try (ExecutionContext context = ExecutionContext.open(EngineScenario.of("s", java.util.List.of()))) {
            assertNull(GlobalState.getCurrentScenarioState());
            assertSame(context.parsingMap(), ParsingMap.getRunningParsingMap());
            context.attachLegacy(nativeState);
            assertSame(nativeState, GlobalState.getCurrentScenarioState());
            CurrentScenarioState.currentScenarioState.set(live);
            assertSame(live, GlobalState.getCurrentScenarioState());
            CurrentScenarioState.currentScenarioState.remove();
            assertSame(nativeState, GlobalState.getCurrentScenarioState());
        }
        CurrentScenarioState.currentScenarioState.remove();
        assertNull(GlobalState.getCurrentScenarioState());
        assertSame(ParsingMap.getGlobalsParsingmap(), ParsingMap.getRunningParsingMap());
    }
}
