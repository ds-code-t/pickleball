package tools.ds.modkit.extensions;

import io.cucumber.core.backend.TestCaseState;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.datatable.DataTable;
import io.cucumber.gherkin.GherkinDialects;
import io.cucumber.messages.types.PickleStep;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.plugin.event.*;
import tools.ds.modkit.coredefinitions.MetaSteps;
import tools.ds.modkit.coredefinitions.ModularScenarios;
import tools.ds.modkit.executions.StepExecution;
import tools.ds.modkit.mappings.ParsingMap;
import tools.ds.modkit.status.SoftException;
import tools.ds.modkit.status.SoftRuntimeException;
import tools.ds.modkit.util.PickleStepArgUtils;
import tools.ds.modkit.util.Reflect;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.ds.modkit.blackbox.BlackBoxBootstrap.metaFlag;
import static tools.ds.modkit.blackbox.BlackBoxBootstrap.skipLogging;
import static tools.ds.modkit.coredefinitions.FlagSteps.*;
import static tools.ds.modkit.coredefinitions.MetaSteps.defaultMatchFlag;
import static tools.ds.modkit.evaluations.AviatorUtil.eval;

import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.util.ExecutionModes.RUN;
import static tools.ds.modkit.util.ExecutionModes.SKIP;
import static tools.ds.modkit.util.KeyFunctions.getUniqueKey;
import static tools.ds.modkit.util.Reflect.getProperty;
import static tools.ds.modkit.util.Reflect.invokeAnyMethod;
import static tools.ds.modkit.util.stepbuilder.StepUtilities.createScenarioPickleStepTestStep;
import static tools.ds.modkit.util.stepbuilder.StepUtilities.getDefinition;

public class StepExtension extends StepRelationships implements PickleStepTestStep, io.cucumber.plugin.event.Step {

    public Throwable storedThrowable;


    public String evalWithStepMaps(String expression) {
        return String.valueOf(eval(expression, getStepParsingMap()));
    }

    @Override
    public String toString() {
        return getStepText();
    }

    public static StepExtension getCurrentStep() {
        return getScenarioState().getCurrentStep();
    }
//    private  boolean skipLogging;

    public final PickleStepTestStep delegate;
    public final PickleStep rootStep;
    public final io.cucumber.core.gherkin.Step gherikinMessageStep;
    public final String rootText;
    public final String metaData;


//    private List<NodeMap> stepMaps = new ArrayList<>();


    public final io.cucumber.core.gherkin.Pickle parentPickle;
//    public final String pickleKey;

    private DataTable stepDataTable;


    private final boolean isCoreStep;
    private final boolean isDataTableStep;

    private final boolean metaStep;

//    private static final Pattern pattern = Pattern.compile("@\\[([^\\[\\]]+)\\]");
    private static final Pattern pattern = Pattern.compile("@:([A-Z]+:[A-Z-a-z0-9]+)");

//    private static final Class<?> metaClass = tools.ds.modkit.coredefinitions.MetaSteps.class;
//    private static final Class<?> ModularScenarios = tools.ds.modkit.coredefinitions.MetaSteps.class;


    private final Method method;
    private final String methodName;

    //    private final String stepTextOverRide;
    private final boolean isScenarioNameStep;


    public StepExtension(io.cucumber.core.gherkin.Pickle pickle, StepExecution stepExecution,
                         PickleStepTestStep step) {
        this(createScenarioPickleStepTestStep(pickle, step), stepExecution, pickle);
    }

    public StepExtension(PickleStepTestStep step, StepExecution stepExecution,
                         io.cucumber.core.gherkin.Pickle pickle) {
        this(step, stepExecution, pickle, new HashMap<>());
    }

    public StepExtension(PickleStepTestStep step, StepExecution stepExecution,
                         io.cucumber.core.gherkin.Pickle pickle, Map<String, Object> configs) {
        String codeLocation = step.getCodeLocation();
        if (codeLocation == null)
            codeLocation = "";
        if (codeLocation.startsWith(ModularScenarios.class.getCanonicalName() + ".")) {
            this.overRideUUID = skipLogging;
        }

        this.isScenarioNameStep = step.getStep().getText().contains(defaultMatchFlag + "Scenario");
        this.parentPickle = pickle;

        this.isCoreStep = codeLocation.startsWith(MetaSteps.class.getPackageName() + ".");
        this.method = (Method) getProperty(step, "definitionMatch.stepDefinition.stepDefinition.method");
        this.methodName = this.method == null ? "" : this.method.getName();
        this.stepExecution = stepExecution;
        this.delegate = step;


        if (isCoreStep && methodName.startsWith("flagStep_")) {
            this.isFlagStep = true;
            stepFlags.add(delegate.getStep().getText());
        }


        this.gherikinMessageStep = (io.cucumber.core.gherkin.Step) getProperty(delegate, "step");
        this.rootStep = (PickleStep) getProperty(gherikinMessageStep, "pickleStep");
        String[] strings = ((String) getProperty(rootStep, "text")).split(metaFlag);
        rootText = strings[0].trim();
        metaData = strings.length == 1 ? "" : strings[1].trim();
        Matcher matcher = pattern.matcher(metaData);
        isDataTableStep = isCoreStep && method.getName().equals("getDataTable");
        metaStep = isDataTableStep;

        while (matcher.find()) {
            stepTags.add(matcher.group().substring(1).replaceAll("@:", ""));
        }

        stepTags.stream().filter(t -> t.startsWith("REF:")).forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        setNestingLevel((int) matcher.replaceAll("").chars().filter(ch -> ch == ':').count());
    }


    public final StepExecution stepExecution;
    public Result result;


    public List<Object> getExecutionArguments() {
        return executionArguments;
    }

    public void setExecutionArguments(List<Object> executionArguments) {
        this.executionArguments = executionArguments;
        this.stepDataTable = executionArguments.stream().filter(DataTable.class::isInstance).map(DataTable.class::cast).findFirst().orElse(null);
    }

    private List<Object> executionArguments;


    public StepExtension createMessageStep(String newStepText) {
        Map<String, String> map = new HashMap<>();
        map.put("newStepText", "MESSAGE:\"" + newStepText + "\"");
        map.put("removeArgs", "true");
        map.put("RANDOMID", "RANDOMID");

        return updateStep(map);
    }

    public StepExtension modifyStep(String newStepText) {
        Map<String, String> map = new HashMap<>();
        map.put("newStepText", newStepText);
        return updateStep(map);
    }


    private StepExtension updateStep() {
        return updateStep(new HashMap<>());
    }

    private StepExtension updateStep(Map<String, String> overrides) {
        ParsingMap newParsingMap = this.getStepParsingMap();

        PickleStepArgument argument = overrides.containsKey("removeArgs") ? null : rootStep.getArgument().orElse(null);
        UnaryOperator<String> external = newParsingMap::resolveWholeText;
        PickleStepArgument newPickleStepArgument = isScenarioNameStep ? null : PickleStepArgUtils.transformPickleArgument(argument, external);
        String newStepText = overrides.getOrDefault("newStepText", rootStep.getText());

        PickleStep pickleStep = new PickleStep(newPickleStepArgument, rootStep.getAstNodeIds(), rootStep.getId(), rootStep.getType().orElse(null), newParsingMap.resolveWholeText(newStepText));

        io.cucumber.core.gherkin.Step newGherikinMessageStep = (io.cucumber.core.gherkin.Step) Reflect.newInstance(
                "io.cucumber.core.gherkin.messages.GherkinMessagesStep",
                pickleStep,
                GherkinDialects.getDialect(getScenarioState().getPickleLanguage()).orElse(GherkinDialects.getDialect("en").get()),
                gherikinMessageStep.getPreviousGivenWhenThenKeyword(),
                gherikinMessageStep.getLocation(),
                gherikinMessageStep.getKeyword()
        );
        Object pickleStepDefinitionMatch = getDefinition(getScenarioState().getRunner(), getScenarioState().getScenarioPickle(), newGherikinMessageStep);
        List<io.cucumber.core.stepexpression.Argument> args = (List<io.cucumber.core.stepexpression.Argument>) getProperty(pickleStepDefinitionMatch, "arguments");
        StepExtension newStep = new StepExtension((PickleStepTestStep) Reflect.newInstance(
                "io.cucumber.core.runner.PickleStepTestStep",
                (overrides.containsKey("RANDOMID") ? UUID.randomUUID() : getId()),               // java.util.UUID
                getUri(),                // java.net.URI
                newGherikinMessageStep,        // io.cucumber.core.gherkin.Step (public)
                pickleStepDefinitionMatch            // io.cucumber.core.runner.PickleStepDefinitionMatch (package-private instance is fine)
        ), stepExecution, parentPickle);
        System.out.println("aa3: " + newStep);
        System.out.println("aa3 args: " + args);


        newStep.setExecutionArguments(args.stream().map(io.cucumber.core.stepexpression.Argument::getValue).toList());

        copyRelationships(this, newStep);
        return newStep;
    }


    public boolean isFail() {
        return hardFail || softFail;
    }

    public boolean isHardFail() {
        return hardFail;
    }

    public boolean isSoftFail() {
        return softFail;
    }

    public void setHardFail() {
        this.hardFail = true;
    }

    public void setSoftFail() {
        this.softFail = true;
    }

    private boolean hardFail = false;
    private boolean softFail = false;
    private boolean skipped = false;

    TestCase ranTestCase;
    EventBus ranBus;
    TestCaseState ranState;
    Object ranExecutionMode;


    public StepExtension runNextSibling() {
        StepExtension nextSibling = getNextSibling();
        if (nextSibling == null)
            return null;

        StepExtension nextStepToRun = nextSibling;
        if (nextSibling.isTemplateStep) {
            nextStepToRun = nextSibling.updateStep();
            setNextSibling(nextStepToRun);
            nextStepToRun.setPreviousSibling(this);
        }
        return nextStepToRun.run(ranTestCase, ranBus, ranState, ranExecutionMode);
    }

    public StepExtension runFirstChild() {
        if (getChildSteps().isEmpty() || getConditionalStates().contains(ConditionalStates.SKIP_CHILDREN))
            return null;
        StepExtension firstChildToRun = getChildSteps().getFirst().updateStep();
        firstChildToRun.setParentStep(this);
        return firstChildToRun.run(ranTestCase, ranBus, ranState, ranExecutionMode);

    }


    public StepExtension run() {
        return run(ranTestCase, ranBus, ranState, ranExecutionMode);
    }

    public StepExtension run(TestCase testCase, EventBus bus, TestCaseState state, Object executionMode) {
        StepExtension nextSibling = getNextSibling();
        if (nextSibling != null && nextSibling.metaStep) {
            if (nextSibling.isDataTableStep) {
                StepExtension updatedDataTableStep = nextSibling.updateStep();
                setNextSibling(nextSibling.getNextSibling());
            }
        }


        getScenarioState().setCurrentStep(this);

        getScenarioState().register(this, getUniqueKey(this));

        skipped = stepExecution.isScenarioComplete();


        executionMode = shouldRun() ? RUN(executionMode) : SKIP(executionMode);
        Object returnObj = invokeAnyMethod(delegate, "run", testCase, bus, state, executionMode);
        List<Result> results = ((List<Result>) getProperty(state, "stepResults"));
        result = results.getLast();


        if (result.getStatus().equals(Status.FAILED) || result.getStatus().equals(Status.UNDEFINED)) {
            Throwable throwable = result.getError();
            if (throwable != null && (throwable.getClass().equals(SoftException.class) || throwable.getClass().equals(SoftRuntimeException.class)))
                setSoftFail();
            else
                setHardFail();
        }

        if (!(stepFlags.contains(IGNORE_FAILURES) || stepExecution.isScenarioComplete())) {
            if (isSoftFail())
                stepExecution.setScenarioSoftFail();
            else if (isHardFail())
                stepExecution.setScenarioHardFail();
        }


        ranTestCase = testCase;
        ranBus = bus;
        ranState = state;
        ranExecutionMode = returnObj;

        StepExtension ranStep = runFirstChild();

        return runNextSibling();
    }


    public boolean shouldRun() {
        System.out.println("@@should run " + this);
        System.out.println("@@stepFlags " + stepFlags);
        if (getParentStep() == null)
            return true;

        if (stepFlags.contains(ALWAYS_RUN))
            return true;

        if (stepFlags.contains(RUN_IF_SCENARIO_FAILED)) {
            return (stepExecution.isScenarioFailed());
        }

        if (stepFlags.contains(RUN_IF_SCENARIO_SOFT_FAILED))
            return isSoftFail();
        if (stepFlags.contains(RUN_IF_SCENARIO_HARD_FAILED))
            return isHardFail();
        if (stepFlags.contains(RUN_IF_SCENARIO_PASSING))
            return !(isHardFail() || isSoftFail());
        return !skipped;
    }


    @Override
    public String getPattern() {
        return delegate.getPattern();
    }

    @Override
    public Step getStep() {
        return this;
    }

    @Override
    public List<Argument> getDefinitionArgument() {
        return delegate.getDefinitionArgument();
    }

    @Override
    public StepArgument getStepArgument() {
        return delegate.getStepArgument();
    }

    @Override
    public int getStepLine() {
        return isScenarioNameStep ? parentPickle.getLocation().getLine() : delegate.getStepLine();
    }

    @Override
    public URI getUri() {
        return delegate.getUri();
    }

    @Override
    public String getStepText() {
        return delegate.getStepText();
    }

    @Override
    public String getCodeLocation() {
        return delegate.getCodeLocation();
    }

    public UUID overRideUUID = null;

    @Override
    public UUID getId() {
        return overRideUUID == null ? delegate.getId() : overRideUUID;
    }


    // from gherikin step

    @Override
    public StepArgument getArgument() {
        return gherikinMessageStep.getArgument();
    }

    @Override
    public String getKeyword() {
        return gherikinMessageStep.getKeyword();
    }

    @Override
    public String getText() {
        return "\u00A0\u00A0\u00A0\u00A0\u00A0".repeat(getNestingLevel()) + (gherikinMessageStep.getText().replaceFirst(defaultMatchFlag, ""));
    }

    @Override
    public int getLine() {
        return isScenarioNameStep ? parentPickle.getLocation().getLine() : gherikinMessageStep.getLine();
    }

    @Override
    public Location getLocation() {
        return isScenarioNameStep ? parentPickle.getLocation() : gherikinMessageStep.getLocation();
    }


}
