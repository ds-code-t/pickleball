package tools.dscode.control.api;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.control.ControlExecutionScope;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static tools.dscode.common.gherkinoperations.DynamicExecution.getCustomStep;
import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;

/**
 * Retry-friendly dynamic Pickleball execution API intended for humans, tooling, and future MCP adapters.
 * Calls execute against the current live test environment without being inserted into scenario traversal.
 */
public final class DynamicControl {
    private DynamicControl() {
    }

    public static ControlCallResult<StepExtension> createStep(String text) {
        return createStep(text, "");
    }

    /**
     * Creates a detached Pickleball step.
     *
     * <p>Controller callers may supply either the historical raw step text or one
     * complete Gherkin step line such as {@code Given CONTROL API TEST STEP}.
     * Gherkin parsing deliberately happens here in the consumer worker, never in
     * Workbench, so the controller remains independent of Cucumber/Pickleball.</p>
     */
    public static ControlCallResult<StepExtension> createStep(String text, String argument) {
        if (GlobalState.getCurrentScenarioState() == null || GlobalState.getTestCase() == null) {
            return ControlCallResult.unavailable("Dynamic step creation requires an active Pickleball test context.");
        }
        return attempt(() -> {
            DynamicStepSpec normalized = normalizeWorkbenchStep(text, argument);
            return getCustomStep(normalized.text(), normalized.argument());
        });
    }

    /** Creates a detached step with an exact caller-defined mapping source set. */
    public static ControlCallResult<StepExtension> createStep(
            String text,
            String argument,
            MappingContext mappingContext
    ) {
        if (mappingContext == null) {
            return ControlCallResult.unavailable("mappingContext must not be null");
        }
        ControlCallResult<StepExtension> created = createStep(text, argument);
        if (!created.successful()) {
            return created;
        }
        return attempt(() -> {
            MappingControl.installExact(
                    created.value().getStepParsingMap(),
                    mappingContext.maps()
            );
            return created.value();
        });
    }

    /** Creates every requested step and keeps going after individual failures. */
    public static List<ControlCallResult<StepExtension>> createSteps(List<DynamicStepSpec> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<ControlCallResult<StepExtension>> results = new ArrayList<>(steps.size());
        for (DynamicStepSpec step : steps) {
            results.add(step == null
                    ? ControlCallResult.unavailable("step must not be null")
                    : createStep(step.text(), step.argument()));
        }
        return List.copyOf(results);
    }

    public static ControlCallResult<Object> executeStep(String text) {
        return executeStep(text, "");
    }

    public static ControlCallResult<Object> executeStep(String text, String argument) {
        ControlCallResult<StepExtension> created = createStep(text, argument);
        if (!created.successful()) {
            return new ControlCallResult<>(created.status(), null, created.error());
        }
        return executeStep(created.value());
    }

    public static ControlCallResult<Object> executeStep(
            String text,
            MappingContext mappingContext
    ) {
        return executeStep(text, "", mappingContext);
    }

    public static ControlCallResult<Object> executeStep(
            String text,
            String argument,
            MappingContext mappingContext
    ) {
        ControlCallResult<StepExtension> created = createStep(text, argument);
        if (!created.successful()) {
            return new ControlCallResult<>(created.status(), null, created.error());
        }
        return executeStep(created.value(), mappingContext);
    }

    public static ControlCallResult<Object> executeStep(StepExtension step) {
        return executeStep(step, null);
    }

    /** Executes one detached step with an optional exact mapping context and restores the step map afterward. */
    public static ControlCallResult<Object> executeStep(
            StepExtension step,
            MappingContext mappingContext
    ) {
        if (step == null) {
            return ControlCallResult.unavailable("step must not be null");
        }

        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        if (state == null) {
            return ControlCallResult.unavailable("Dynamic execution requires an active Pickleball test context.");
        }

        Phrase previousPhrase = state.currentPhrase;
        MappingScope mappingScope = null;
        try {
            if (mappingContext != null) {
                mappingScope = new MappingScope(step.getStepParsingMap(), mappingContext);
            }
            Object value = ControlExecutionScope.withStep(step, () -> {
                if (step.isDynamicStep) {
                    step.lineData = ParsedLine.createParsedLine(step);
                    step.lineData.setInheritance(step);
                }
                return step.runAndGetReturnValue();
            });
            return ControlCallResult.success(value);
        } catch (Throwable error) {
            return ControlCallResult.failed(error);
        } finally {
            if (mappingScope != null) {
                mappingScope.close();
            }
            state.currentPhrase = previousPhrase;
        }
    }

    /** Executes every request and keeps going after individual failures. */
    public static List<ControlCallResult<Object>> executeSteps(List<DynamicStepSpec> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<ControlCallResult<Object>> results = new ArrayList<>(steps.size());
        for (DynamicStepSpec step : steps) {
            results.add(step == null
                    ? ControlCallResult.unavailable("step must not be null")
                    : executeStep(step.text(), step.argument()));
        }
        return List.copyOf(results);
    }

    /** Executes every request against the same caller-defined NodeMap sources. */
    public static List<ControlCallResult<Object>> executeSteps(
            List<DynamicStepSpec> steps,
            MappingContext mappingContext
    ) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<ControlCallResult<Object>> results = new ArrayList<>(steps.size());
        for (DynamicStepSpec step : steps) {
            results.add(step == null
                    ? ControlCallResult.unavailable("step must not be null")
                    : executeStep(step.text(), step.argument(), mappingContext));
        }
        return List.copyOf(results);
    }

    /** Executes a parsed Cucumber scenario/background-expanded Pickle as detached steps. */
    public static List<ControlCallResult<Object>> executePickle(Pickle pickle) {
        if (pickle == null) {
            return List.of(ControlCallResult.unavailable("pickle must not be null"));
        }
        List<DynamicStepSpec> steps = GherkinControl.steps(pickle).stream()
                .map(step -> new DynamicStepSpec(step.getText(), GherkinControl.argumentText(step)))
                .toList();
        return executeSteps(steps);
    }

    public static List<ControlCallResult<Object>> executePickle(
            Pickle pickle,
            MappingContext mappingContext
    ) {
        if (pickle == null) {
            return List.of(ControlCallResult.unavailable("pickle must not be null"));
        }
        List<DynamicStepSpec> steps = GherkinControl.steps(pickle).stream()
                .map(step -> new DynamicStepSpec(step.getText(), GherkinControl.argumentText(step)))
                .toList();
        return executeSteps(steps, mappingContext);
    }

    /** Executes a temporary StepExtension tree in pre-order without entering scenario traversal. */
    public static List<ControlCallResult<Object>> executeTree(StepExtension root) {
        return executeTree(root, null);
    }

    public static List<ControlCallResult<Object>> executeTree(
            StepExtension root,
            MappingContext mappingContext
    ) {
        if (root == null) {
            return List.of(ControlCallResult.unavailable("root step must not be null"));
        }
        List<ControlCallResult<Object>> results = new ArrayList<>();
        executeTree(root, mappingContext, results);
        return List.copyOf(results);
    }

    private static void executeTree(
            StepExtension step,
            MappingContext mappingContext,
            List<ControlCallResult<Object>> results
    ) {
        results.add(executeStep(step, mappingContext));
        for (var child : step.childSteps) {
            if (child instanceof StepExtension childStep) {
                executeTree(childStep, mappingContext, results);
            }
        }
    }

    public static ControlCallResult<StepExtension> cloneStep(StepExtension source, String newText) {
        if (source == null) {
            return ControlCallResult.unavailable("source step must not be null");
        }
        return attempt(() -> source.cloneWithOverrides(
                newText == null ? source.getStepText() : newText
        ));
    }

    /** Adds a child while maintaining the direct parent/sibling/nesting relationships. */
    public static ControlCallResult<StepExtension> addChild(StepExtension parent, StepExtension child) {
        if (parent == null || child == null) {
            return ControlCallResult.unavailable("parent and child must not be null");
        }
        return attempt(() -> {
            child.setNestingLevel(parent.getNestingLevel() + 1);
            child.setStepParsingMap(parent.getStepParsingMap());
            parent.addChildStep(child);
            return child;
        });
    }

    public static ControlCallResult<List<StepExtension>> addChildren(
            StepExtension parent,
            List<StepExtension> children
    ) {
        if (parent == null) {
            return ControlCallResult.unavailable("parent must not be null");
        }
        if (children == null || children.isEmpty()) {
            return ControlCallResult.success(List.of());
        }
        return attempt(() -> {
            List<StepExtension> added = new ArrayList<>(children.size());
            for (StepExtension child : children) {
                if (child == null) {
                    throw new IllegalArgumentException("children must not contain null");
                }
                ControlCallResult<StepExtension> result = addChild(parent, child);
                if (!result.successful()) {
                    throw new IllegalStateException(result.error().message());
                }
                added.add(child);
            }
            return List.copyOf(added);
        });
    }

    public static ControlCallResult<CurrentScenarioState> currentScenario() {
        CurrentScenarioState state = GlobalState.getCurrentScenarioState();
        return state == null
                ? ControlCallResult.unavailable("No scenario is currently active.")
                : ControlCallResult.success(state);
    }

    public static ControlCallResult<StepExtension> currentStep() {
        StepExtension step = GlobalState.getRunningStep();
        return step == null
                ? ControlCallResult.unavailable("No step is currently active.")
                : ControlCallResult.success(step);
    }

    public static ControlCallResult<Phrase> currentPhrase() {
        Phrase phrase = GlobalState.getRunningPhrase();
        return phrase == null
                ? ControlCallResult.unavailable("No phrase is currently active.")
                : ControlCallResult.success(phrase);
    }

    public static ControlCallResult<ParsingMap> currentParsingMap() {
        if (GlobalState.getCurrentScenarioState() == null) {
            return ControlCallResult.unavailable("No scenario is currently active.");
        }
        return attempt(() -> getRunningParsingMap());
    }

    private static DynamicStepSpec normalizeWorkbenchStep(String text, String argument) {
        String raw = text == null ? "" : text;
        String trimmed = raw.strip();
        if (!looksLikeGherkinStep(trimmed)) {
            return new DynamicStepSpec(raw, argument);
        }

        String source = """
                Feature: Workbench detached step
                  Scenario: Live step
                    %s
                """.formatted(trimmed);
        ControlCallResult<Feature> parsed = GherkinControl.parseFeature(source);
        if (!parsed.successful()) {
            String message = parsed.error() == null
                    ? "Could not parse the supplied Gherkin step."
                    : parsed.error().message();
            throw new IllegalArgumentException(message);
        }

        List<Pickle> pickles = GherkinControl.scenarios(parsed.value());
        if (pickles.size() != 1) {
            throw new IllegalArgumentException("A live Workbench command must contain exactly one Gherkin step.");
        }
        List<Step> steps = GherkinControl.steps(pickles.getFirst());
        if (steps.size() != 1) {
            throw new IllegalArgumentException("A live Workbench command must contain exactly one Gherkin step.");
        }

        Step step = steps.getFirst();
        String suppliedArgument = argument == null ? "" : argument;
        String parsedArgument = GherkinControl.argumentText(step);
        return new DynamicStepSpec(
                step.getText(),
                suppliedArgument.isBlank() ? parsedArgument : suppliedArgument
        );
    }

    private static boolean looksLikeGherkinStep(String text) {
        return startsWithAny(text, "Given ", "When ", "Then ", "And ", "But ", "* ");
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) return true;
        }
        return false;
    }

    private static <T> ControlCallResult<T> attempt(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        try {
            return ControlCallResult.success(action.get());
        } catch (Throwable error) {
            return ControlCallResult.failed(error);
        }
    }
}
