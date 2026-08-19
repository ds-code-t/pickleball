package com.example.pickleball;

import org.junit.jupiter.api.Test;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;
import tools.dscode.control.override.StepOverrideCompiler;
import tools.dscode.control.override.StepOverridePatternType;
import tools.dscode.control.override.StepOverrideRegistry;
import tools.dscode.control.override.StepOverrideRule;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StepOverrideChecks {

    @Test
    void matchingOverrideReplacesNormalGlueAndRemovalRestoresIt() {
        ControlApiTestSteps.reset();
        AtomicInteger overrideCalls = new AtomicInteger();

        try {
            StepOverrideRegistry.register(new StepOverrideRule(
                    "replace-control-step",
                    StepOverridePatternType.REGEX,
                    "^CONTROL API TEST STEP$",
                    context -> {
                        overrideCalls.incrementAndGet();
                        return null;
                    }
            ));

            ControlCallResult<Object> overridden = DynamicControl.executeStep("CONTROL API TEST STEP");
            assertTrue(overridden.successful(), () -> String.valueOf(overridden.error()));
            assertEquals(1, overrideCalls.get());
            assertEquals(0, ControlApiTestSteps.invocationCount());

            assertTrue(StepOverrideRegistry.remove("replace-control-step"));

            ControlCallResult<Object> restored = DynamicControl.executeStep("CONTROL API TEST STEP");
            assertTrue(restored.successful(), () -> String.valueOf(restored.error()));
            assertEquals(1, ControlApiTestSteps.invocationCount());
        } finally {
            StepOverrideRegistry.clear();
        }
    }

    @Test
    void regexCapturesAreAvailableToTheHandler() {
        AtomicReference<List<String>> captures = new AtomicReference<>();

        try {
            StepOverrideRegistry.register(new StepOverrideRule(
                    "capture-step",
                    StepOverridePatternType.REGEX,
                    "^WORKBENCH OVERRIDE ([A-Za-z]+) ([0-9]+)$",
                    context -> {
                        captures.set(context.captures());
                        return null;
                    }
            ));

            ControlCallResult<Object> result =
                    DynamicControl.executeStep("WORKBENCH OVERRIDE alpha 42");

            assertTrue(result.successful(), () -> String.valueOf(result.error()));
            assertEquals(List.of("alpha", "42"), captures.get());
        } finally {
            StepOverrideRegistry.clear();
        }
    }

    @Test
    void stepArgumentRemainsSeparateFromRegexCaptures() {
        AtomicReference<Object> argument = new AtomicReference<>();
        AtomicReference<List<String>> captures = new AtomicReference<>();

        try {
            StepOverrideRegistry.register(new StepOverrideRule(
                    "argument-step",
                    StepOverridePatternType.REGEX,
                    "^WORKBENCH ARGUMENT ([A-Za-z]+)$",
                    context -> {
                        argument.set(context.argument());
                        captures.set(context.captures());
                        return null;
                    }
            ));

            ControlCallResult<Object> result = DynamicControl.executeStep(
                    "WORKBENCH ARGUMENT alpha",
                    """
                    \"\"\"
                    payload
                    \"\"\"
                    """
            );

            assertTrue(result.successful(), () -> String.valueOf(result.error()));
            assertEquals(List.of("alpha"), captures.get());
            assertNotNull(argument.get());
        } finally {
            StepOverrideRegistry.clear();
        }
    }

    @Test
    void multipleMatchesFailDeterministically() {
        try {
            StepOverrideRegistry.register(new StepOverrideRule(
                    "zeta",
                    StepOverridePatternType.REGEX,
                    "^WORKBENCH AMBIGUOUS$",
                    context -> null
            ));
            StepOverrideRegistry.register(new StepOverrideRule(
                    "alpha",
                    StepOverridePatternType.REGEX,
                    "^WORKBENCH AMBIGUOUS$",
                    context -> null
            ));

            ControlCallResult<Object> result =
                    DynamicControl.executeStep("WORKBENCH AMBIGUOUS");

            assertEquals(ControlCallStatus.FAILED, result.status());
            assertNotNull(result.error());
            assertEquals(
                    "Ambiguous Step Override for step 'WORKBENCH AMBIGUOUS': alpha, zeta",
                    result.error().message()
            );
        } finally {
            StepOverrideRegistry.clear();
        }
    }

    @Test
    void registeringTheSameRuleIdReplacesItsHandler() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        try {
            StepOverrideRegistry.register(new StepOverrideRule(
                    "replaceable",
                    StepOverridePatternType.REGEX,
                    "^WORKBENCH REPLACEABLE$",
                    context -> {
                        first.incrementAndGet();
                        return null;
                    }
            ));
            StepOverrideRegistry.register(new StepOverrideRule(
                    "replaceable",
                    StepOverridePatternType.REGEX,
                    "^WORKBENCH REPLACEABLE$",
                    context -> {
                        second.incrementAndGet();
                        return null;
                    }
            ));

            ControlCallResult<Object> result =
                    DynamicControl.executeStep("WORKBENCH REPLACEABLE");

            assertTrue(result.successful(), () -> String.valueOf(result.error()));
            assertEquals(0, first.get());
            assertEquals(1, second.get());
            assertEquals(1, StepOverrideRegistry.rules().size());
        } finally {
            StepOverrideRegistry.clear();
        }
    }

    @Test
    void generatedImplementationCanReplaceGeneratedImplementation() {
        String scenarioId = getCurrentScenarioState().id.toString();
        String firstSource = source("first");
        String secondSource = source("second");

        try {
            StepOverrideRule first = StepOverrideCompiler.compile(
                    scenarioId, "generated", StepOverridePatternType.REGEX,
                    "^GENERATED OVERRIDE$", firstSource
            );
            String firstClass = StepOverrideCompiler.handlerClassName(first);

            StepOverrideRule second = StepOverrideCompiler.compile(
                    scenarioId, "generated", StepOverridePatternType.REGEX,
                    "^GENERATED OVERRIDE$", secondSource
            );
            String secondClass = StepOverrideCompiler.handlerClassName(second);

            assertNotEquals(firstClass, secondClass);
            assertEquals(1, StepOverrideRegistry.rules().size());

            ControlCallResult<Object> result = DynamicControl.executeStep("GENERATED OVERRIDE");
            assertTrue(result.successful(), () -> String.valueOf(result.error()));
        } finally {
            StepOverrideRegistry.clear();
        }
    }

    private static String source(String marker) {
        return """
                package com.example.pickleball.generated;
                import tools.dscode.control.override.StepOverrideContext;
                import tools.dscode.control.override.StepOverrideHandler;

                public final class {{CLASS_NAME}} implements StepOverrideHandler {
                    public Object execute(StepOverrideContext context) {
                        if ("%s".isBlank()) throw new IllegalStateException();
                        return null;
                    }
                }
                """.formatted(marker);
    }
}
