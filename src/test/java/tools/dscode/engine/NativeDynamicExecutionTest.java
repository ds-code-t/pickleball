package tools.dscode.engine;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.control.override.StepOverridePatternType;
import tools.dscode.control.override.StepOverrideRegistry;
import tools.dscode.control.override.StepOverrideRule;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real dynamic phrases on the native engine. No feature file is written. */
class NativeDynamicExecutionTest {
    public static class ReadGlue {
        @Given("native read {string}")
        public String read(String key) {
            Object value = MappingProcessor.getRunMap() == null ? null : MappingProcessor.getRunMap().get(key);
            return value == null ? "" : String.valueOf(value);
        }

        @Given("native echo {string}")
        public String echo(String value) {
            return value;
        }
    }

    public static class HookGlue {
        static final AtomicInteger before = new AtomicInteger();
        static final AtomicInteger after = new AtomicInteger();
        static final AtomicInteger beforeStep = new AtomicInteger();

        @Before
        public void before() {
            before.incrementAndGet();
        }

        @After
        public void after() {
            after.incrementAndGet();
        }

        @io.cucumber.java.BeforeStep
        public void beforeStep() {
            beforeStep.incrementAndGet();
        }

        @Given("hook ping")
        public void ping() {
        }
    }

    public static class WriteDynamicGlue {
        @Given("native write dynamic save")
        public void write() {
            ExecutionContext.current().writeSteps(List.of(
                    EngineStep.of("* ", ", save \"Z\" as \"dyn\"").withMetadata("source", "runtime")
            ));
        }
    }

    @Test
    void commaSaveAndReadRunWithoutCucumber() {
        String feature = """
                Feature: Save
                  Scenario: One
                    * , save "A" as "result"
                    * native read "result"
                """;
        RunResult result = PickleballEngine.run(feature, EngineOptions.of(ReadGlue.class));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals("A", result.scenarios().getFirst().steps().get(1).returned());
    }

    @Test
    void assertEqualsPassesAndMismatchFails() {
        RunResult pass = PickleballEngine.run("""
                Feature: Assert
                  Scenario: One
                    * ASSERT: "a" equals "a"
                """, EngineOptions.of());
        assertEquals(Status.PASSED, pass.status(), pass.message());

        RunResult fail = PickleballEngine.run("""
                Feature: Assert
                  Scenario: One
                    * ASSERT: "a" equals "b"
                """, EngineOptions.of());
        assertEquals(Status.FAILED, fail.status(), fail.message());
    }

    @Test
    void blockConditionalSavesOnlyTheTakenBranch() {
        RunResult taken = PickleballEngine.run("""
                Feature: If
                  Scenario: One
                    * IF: "a" equals "a" THEN: , save "yes" as "branch"
                    * native read "branch"
                """, EngineOptions.of(ReadGlue.class));
        assertEquals(Status.PASSED, taken.status(), taken.message());
        assertEquals("yes", taken.scenarios().getFirst().steps().get(1).returned());

        RunResult skipped = PickleballEngine.run("""
                Feature: If
                  Scenario: One
                    * IF: "a" equals "b" THEN: , save "no" as "branch"
                    * native read "branch"
                """, EngineOptions.of(ReadGlue.class));
        assertEquals(Status.PASSED, skipped.status(), skipped.message());
        assertEquals("", skipped.scenarios().getFirst().steps().get(1).returned());
    }

    @Test
    void elseBranchSavesWhenIfIsFalse() {
        RunResult result = PickleballEngine.run("""
                Feature: Else
                  Scenario: One
                    * IF: "a" equals "b" THEN: , save "no" as "branch"
                    * ELSE: , save "yes" as "branch"
                    * native read "branch"
                """, EngineOptions.of(ReadGlue.class));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals("yes", result.scenarios().getFirst().steps().get(2).returned());
    }

    @Test
    void templatingReadsASavedValue() {
        RunResult result = PickleballEngine.run("""
                Feature: Template
                  Scenario: One
                    * , save "A" as "result"
                    * native echo "<result>"
                """, EngineOptions.of(ReadGlue.class));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals("A", result.scenarios().getFirst().steps().get(1).returned());
    }

    @Test
    void runScenarioExecutesTheNamedScenarioFromMemory() {
        RunResult result = PickleballEngine.run("""
                Feature: Modular
                  Scenario: Caller
                    * RUN SCENARIO: Worker
                    * native read "made"
                  Scenario: Worker
                    * , save "done" as "made"
                """, EngineOptions.of(ReadGlue.class).scenario("Caller"));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals(1, result.scenarios().size());
        assertEquals("done", result.scenarios().getFirst().steps().get(1).returned());
    }

    @Test
    void runComponentAndServiceCallUseTheInMemoryCatalog() {
        String feature = """
                Feature: Kinds
                  Scenario: Caller
                    * RUN COMPONENT SCENARIO: Worker
                    * RUN SERVICE CALL: Worker
                    * CALL: Worker
                    * native read "made"
                  Scenario: Worker
                    * , save "done" as "made"
                """;
        RunResult result = PickleballEngine.run(feature, EngineOptions.of(ReadGlue.class).scenario("Caller"));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals("done", result.scenarios().getFirst().steps().get(3).returned());
    }

    @Test
    void unknownStepIsUndefinedNotPassed() {
        RunResult result = PickleballEngine.run("""
                Feature: Missing
                  Scenario: One
                    * this step is not defined anywhere
                """, EngineOptions.of());
        assertEquals(Status.UNDEFINED, result.scenarios().getFirst().steps().getFirst().status());
        assertNotEquals(Status.PASSED, result.status());
    }

    @Test
    void twoCommaPhrasesBothSave() {
        RunResult result = PickleballEngine.run("""
                Feature: Chain
                  Scenario: One
                    * , save "A" as "one", save "B" as "two"
                    * native read "one"
                    * native read "two"
                """, EngineOptions.of(ReadGlue.class));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals("A", result.scenarios().getFirst().steps().get(1).returned());
        assertEquals("B", result.scenarios().getFirst().steps().get(2).returned());
        assertTrue(result.scenarios().getFirst().steps().getFirst().status() == Status.PASSED);
    }

    @Test
    void hooksRunWithoutACucumberScenario() {
        HookGlue.before.set(0);
        HookGlue.after.set(0);
        HookGlue.beforeStep.set(0);
        RunResult result = PickleballEngine.run("""
                Feature: Hooks
                  Scenario: One
                    * hook ping
                """, EngineOptions.of(HookGlue.class));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals(1, HookGlue.before.get());
        assertEquals(1, HookGlue.after.get());
        assertTrue(HookGlue.beforeStep.get() >= 1);
    }

    public static class OverrideGlue {
        @Given("native register echo override")
        public void register() {
            StepOverrideRegistry.register(new StepOverrideRule(
                    "echo-override",
                    StepOverridePatternType.REGEX,
                    "native echo \"(.*)\""
                    ,
                    context -> "overridden"
            ));
        }

        @After
        public void clear() {
            StepOverrideRegistry.clear();
        }
    }

    @Test
    void stepOverrideReplacesTheMatchedGlue() {
        RunResult result = PickleballEngine.run("""
                Feature: Override
                  Scenario: One
                    * native register echo override
                    * native echo "A"
                """, EngineOptions.of(ReadGlue.class, OverrideGlue.class));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals("overridden", result.scenarios().getFirst().steps().get(1).returned());
    }

    public static class CountGlue {
        static final AtomicInteger hits = new AtomicInteger();

        @Given("native count")
        public void count() {
            hits.incrementAndGet();
        }
    }

    @Test
    void untilRepeatsTheNestedBody() {
        CountGlue.hits.set(0);
        RunResult result = PickleballEngine.run("""
                Feature: Until
                  Scenario: One
                    * , until the Step Repetition is greater than 2:
                      : * native count
                    * native read "unused"
                """, EngineOptions.of(ReadGlue.class, CountGlue.class));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals(2, CountGlue.hits.get());
    }

    @Test
    void runtimeWrittenDynamicStepRuns() {
        RunResult result = PickleballEngine.run("""
                Feature: Runtime
                  Scenario: One
                    * native write dynamic save
                    * native read "dyn"
                """, EngineOptions.of(ReadGlue.class, WriteDynamicGlue.class));
        assertEquals(Status.PASSED, result.status(), result.message());
        assertEquals("Z", result.scenarios().getFirst().steps().get(2).returned());
        assertTrue(result.scenarios().getFirst().steps().get(1).message().contains("source=runtime"));
    }
}
