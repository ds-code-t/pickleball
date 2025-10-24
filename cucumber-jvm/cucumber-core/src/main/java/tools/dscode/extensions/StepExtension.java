package tools.dscode.extensions;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.runner.ExecutionMode;
import io.cucumber.core.runner.HookTestStep;
import io.cucumber.core.runner.PickleStepDefinitionMatch;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.TestCaseState;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.plugin.event.Argument;
import io.cucumber.plugin.event.StepArgument;
import io.cucumber.plugin.event.TestCase;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.common.GlobalConstants.DocString_KEY;
import static tools.dscode.common.GlobalConstants.TABLE_KEY;
import static tools.dscode.common.GlobalConstants.defaultMatchFlag;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.state.ScenarioState.getBus;
import static tools.dscode.state.ScenarioState.getScenarioState;
import static tools.dscode.state.ScenarioState.getTestCase;
import static tools.dscode.state.ScenarioState.getTestCaseState;
import static tools.dscode.state.ScenarioState.setKeyedTemplate;

/**
 * StepExtension is a decorator that extends {@link PickleStepTestStep} while
 * delegating all behavior to a provided {@code PickleStepTestStep}.
 * Additionally, it supports overriding returned values from selected getters
 * via an internal overrides map. If an override for a given property key is
 * present and non-null, the getter returns that value instead of delegating.
 */
public class StepExtension extends StepRelationships {

    /* ===================== Override Keys ===================== */
    public static final String KEY_PATTERN = "pattern";
    public static final String KEY_STEP = "step";
    public static final String KEY_DEFINITION_ARGUMENTS = "definitionArguments";
    public static final String KEY_DEFINITION_MATCH = "definitionMatch";
    public static final String KEY_STEP_ARGUMENT = "stepArgument";
    public static final String KEY_STEP_LINE = "stepLine";
    public static final String KEY_URI = "uri";
    public static final String KEY_STEP_TEXT = "stepText";
    public static final String KEY_BEFORE_STEP_HOOKS = "beforeStepHookSteps";
    public static final String KEY_AFTER_STEP_HOOKS = "afterStepHookSteps";

    private final PickleStepTestStep delegate;
    private final ConcurrentHashMap<String, Object> overrides = new ConcurrentHashMap<>();

    // private static final Pattern pattern =
    // Pattern.compile("@\\[([^\\[\\]]+)\\]");
    private static final Pattern pattern = Pattern.compile("@:([A-Z]+:[A-Z-a-z0-9]+)");

    public boolean isCoreStep;

    public String metaData;
    public PickleStep rootStep;
    public io.cucumber.core.gherkin.Step gherikinMessageStep;

    // private final StepDefinition stepDefinition;
    public Method method;
    public String methodName;

    // private final String stepTextOverRide;
    private boolean isScenarioNameStep;
    // PickleballChange
    public DefinitionFlag[] definitionFlags;

    public io.cucumber.core.gherkin.Pickle parentPickle;

    /**
     * Constructs a delegating step that mirrors the provided {@code delegate}.
     * All calls are forwarded to that delegate unless a getter override is set.
     */
    public StepExtension(PickleStepTestStep delegate) {
        // Initialize super with the delegate's current state so the base class
        // fields are consistent (id, uri, step, hooks, definitionMatch).
        super(
            nonNull(delegate, "delegate").getId(),
            delegate.getUri(),
            delegate.getStep(),
            delegate.getBeforeStepHookSteps(),
            delegate.getAfterStepHookSteps(),
            delegate.getDefinitionMatch());
        this.delegate = delegate;
    }

    public void initialize() {
        String codeLocation = delegate.getCodeLocation();
        if (codeLocation == null)
            codeLocation = "";

        this.isScenarioNameStep = delegate.getStep().getText().contains(defaultMatchFlag
                + "Scenario");

        this.isCoreStep = codeLocation.startsWith("tools.dscode.tools.dscode.coredefinitions.");
        this.method = (Method) getProperty(delegate,
            "definitionMatch.stepDefinition.stepDefinition.method");
        this.methodName = this.method == null ? "" : this.method.getName();

        DefinitionFlags annotation = method.getAnnotation(DefinitionFlags.class);

        definitionFlags = annotation == null ? new DefinitionFlag[] {} : annotation.value();

        if (isCoreStep && methodName.startsWith("flagStep_")) {
            this.isFlagStep = true;
            stepFlags.add(delegate.getStep().getText());
        }

        this.gherikinMessageStep = (io.cucumber.core.gherkin.Step) getProperty(delegate, "step");
        this.rootStep = (PickleStep) getProperty(gherikinMessageStep, "pickleStep");

        metaData = rootStep.metaText;
        Matcher matcher = pattern.matcher(metaData);

        while (matcher.find()) {
            stepTags.add(matcher.group().substring(1).replaceAll("@:", ""));
        }
        stepTags.stream().filter(t -> t.startsWith("REF:")).forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        setNestingLevel((int) matcher.replaceAll("").chars().filter(ch -> ch == ':').count());

        if (isCoreStep && methodName.equals("docString")) {
            getProperty(delegate, "definitionMatch");
            Object pickleStepDefinitionMatch = getProperty(delegate, "definitionMatch");
            List<io.cucumber.core.stepexpression.Argument> args = (List<io.cucumber.core.stepexpression.Argument>) getProperty(
                pickleStepDefinitionMatch, "arguments");
            String docStringName = (String) args.getFirst().getValue();
            DocString docString = (DocString) args.getLast().getValue();
            if (docStringName != null && !docStringName.isBlank())
                getScenarioState().getParsingMap().getRootSingletonMap().put("DOCSTRING." +
                        docStringName.trim(),
                    docString);
            // if (docStringName != null && !docStringName.isBlank())
            // getScenarioState().register(docString, docStringName.trim());
            setKeyedTemplate(this, DocString_KEY, docString);
        } else if (isCoreStep && methodName.equals("dataTable")) {
            getProperty(delegate, "definitionMatch");
            Object pickleStepDefinitionMatch = getProperty(delegate, "definitionMatch");
            List<io.cucumber.core.stepexpression.Argument> args = (List<io.cucumber.core.stepexpression.Argument>) getProperty(
                pickleStepDefinitionMatch, "arguments");
            String tableName = (String) args.getFirst().getValue();
            DataTable dataTable = (DataTable) args.getLast().getValue();
            if (tableName != null && !tableName.isBlank())
                getScenarioState().getParsingMap().getRootSingletonMap().put("DATATABLE." +
                        tableName.trim(),
                    dataTable);
            // if (tableName != null && !tableName.isBlank())
            // getScenarioState().register(dataTable, tableName.trim());
            setKeyedTemplate(this, TABLE_KEY, dataTable);
        }
    }

    public void runExtension() {
        delegate.run(getTestCase(), getBus(), getTestCaseState(), ExecutionMode.RUN);
    }

    /**
     * Expose the underlying step, if needed.
     */
    public PickleStepTestStep getDelegate() {
        return delegate;
    }

    /* ===================== Overrides API ===================== */

    /**
     * Put/replace an override value for a given key. Use the KEY_* constants.
     */
    public StepExtension withOverride(String key, Object value) {
        putOverride(key, value);
        return this;
    }

    /**
     * Put/replace an override value for a given key.
     */
    public void putOverride(String key, Object value) {
        Objects.requireNonNull(key, "key");
        overrides.put(key, value);
    }

    /**
     * Remove a specific override.
     */
    public void removeOverride(String key) {
        if (key != null) {
            overrides.remove(key);
        }
    }

    /**
     * Clear all overrides.
     */
    public void clearOverrides() {
        overrides.clear();
    }

    /*
     * ===================== Delegation + Override Helpers =====================
     */

    private static <T> T nonNull(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private <T> T orOverride(String key, Class<T> type, Supplier<T> fallback) {
        if (overrides.containsKey(key)) { // instead of checking for non-null
            Object o = overrides.get(key);
            if (o == null)
                return null; // explicit null override
            if (!type.isInstance(o)) {
                throw new ClassCastException("Override for key '" + key + "' is not of type " + type.getName());
            }
            return type.cast(o);
        }
        return fallback.get();
    }

    private int orOverrideInt(String key, Supplier<Integer> fallback) {
        Object o = overrides.get(key);
        if (o != null) {
            if (!(o instanceof Integer)) {
                throw new ClassCastException(
                    "Override for key '" + key + "' is not an Integer (was "
                            + o.getClass().getName() + ")");
            }
            return (Integer) o;
        }
        return fallback.get();
    }

    /*
     * ===================== Delegation Overrides (with overrides)
     * =====================
     */

    // Keep execution consistent; this is not a getter and is not overridden via
    // map.
    @Override
    public ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode executionMode) {
        return delegate.run(testCase, bus, state, executionMode);
    }

    // Hook lists (package-private in base). We expose as public and allow
    // overrides.
    @Override
    public List<HookTestStep> getBeforeStepHookSteps() {
        return orOverride(KEY_BEFORE_STEP_HOOKS, (Class<List<HookTestStep>>) (Class<?>) List.class,
            delegate::getBeforeStepHookSteps);
    }

    @Override
    public List<HookTestStep> getAfterStepHookSteps() {
        return orOverride(KEY_AFTER_STEP_HOOKS, (Class<List<HookTestStep>>) (Class<?>) List.class,
            delegate::getAfterStepHookSteps);
    }

    @Override
    public String getPattern() {
        return orOverride(KEY_PATTERN, String.class, delegate::getPattern);
    }

    @Override
    public Step getStep() {
        return orOverride(KEY_STEP, Step.class, delegate::getStep);
    }

    @Override
    public List<Argument> getDefinitionArgument() {
        return orOverride(KEY_DEFINITION_ARGUMENTS, (Class<List<Argument>>) (Class<?>) List.class,
            delegate::getDefinitionArgument);
    }

    @Override
    public PickleStepDefinitionMatch getDefinitionMatch() {
        return orOverride(KEY_DEFINITION_MATCH, PickleStepDefinitionMatch.class, delegate::getDefinitionMatch);
    }

    @Override
    public StepArgument getStepArgument() {
        return orOverride(KEY_STEP_ARGUMENT, StepArgument.class, delegate::getStepArgument);
    }

    @Override
    public int getStepLine() {
        return orOverrideInt(KEY_STEP_LINE, delegate::getStepLine);
    }

    @Override
    public URI getUri() {
        return orOverride(KEY_URI, URI.class, delegate::getUri);
    }

    @Override
    public String getStepText() {
        return orOverride(KEY_STEP_TEXT, String.class, delegate::getStepText);
    }

    /* ===================== Niceties ===================== */

    @Override
    public String toString() {
        return "StepExtension(delegate=" + delegate + ", overrides=" + overrides.keySet() + ")";
    }

    @Override
    public int hashCode() {
        // Preserve identity through the delegate to avoid surprising set
        // behavior.
        return delegate.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj instanceof StepExtension other) {
            return delegate.equals(other.delegate);
        }
        return delegate.equals(obj);
    }
}
