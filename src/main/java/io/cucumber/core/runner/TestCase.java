package io.cucumber.core.runner;

import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.messages.*;
import io.cucumber.messages.types.*;
import io.cucumber.plugin.event.*;
import io.cucumber.plugin.event.Group;
import io.cucumber.plugin.event.Location;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestStep;
import io.cucumber.plugin.event.TestStepFinished;
import io.pickleball.MapAndStateUtilities.LinkedMultiMap;
import io.pickleball.cacheandstate.ScenarioContext;
//import io.pickleball.cucumberutilities.ComponentScenarioWrapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.cucumber.core.runner.ExecutionMode.DRY_RUN;
import static io.cucumber.core.runner.ExecutionMode.RUN;
//import static io.pickleball.cacheandstate.PrimaryScenarioData.setPrimaryScenario;
import static io.cucumber.messages.Convertor.toMessage;
import static io.pickleball.cacheandstate.GlobalCache.getParsedFeature;
import static io.pickleball.cacheandstate.PrimaryScenarioData.setCurrentScenario;
import static io.pickleball.cacheandstate.PrimaryScenarioData.setPrimaryScenario;
import static io.pickleball.cucumberutilities.FeatureFileUtilities.getComponentByLine;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

public final class TestCase implements io.cucumber.plugin.event.TestCase, TestStep {

    private final Pickle pickle;
    private final List<PickleStepTestStep> testSteps;
    private final ExecutionMode executionMode;
    private final List<HookTestStep> beforeHooks;
    private final List<HookTestStep> afterHooks;
    public final UUID id;
    public final ScenarioContext scenarioContext;
    public GherkinMessagesFeature gherkinMessagesFeature;
    public Scenario scenario;
    public LinkedMultiMap constantMap;
    public final boolean componentScenario;
//    public final ComponentScenarioWrapper componentScenarioWrapper;


    public TestCase(
            UUID id, List<PickleStepTestStep> testSteps,
            List<HookTestStep> beforeHooks,
            List<HookTestStep> afterHooks,
            Pickle pickle,
            boolean dryRun
    ) {
        this(id, testSteps, beforeHooks, afterHooks, pickle, dryRun, false);
    }

    public TestCase(
            UUID id, List<PickleStepTestStep> testSteps,
            List<HookTestStep> beforeHooks,
            List<HookTestStep> afterHooks,
            Pickle pickle,
            boolean dryRun,
            boolean componentScenario
    ) {
        this.id = id;
        this.testSteps = testSteps;
        this.beforeHooks = beforeHooks;
        this.afterHooks = afterHooks;
        this.pickle = pickle;
        this.executionMode = dryRun ? DRY_RUN : RUN;
        this.componentScenario = componentScenario;
//        this.componentScenarioWrapper = new ComponentScenarioWrapper((GherkinMessagesPickle) pickle);

        this.scenarioContext = new ScenarioContext(pickle);
        this.scenarioContext.setTestCase(this);
        for (PickleStepTestStep testStep : testSteps) {
            testStep.stepContext.setScenarioContext(this.scenarioContext);
        }

        if (componentScenario) {
            this.gherkinMessagesFeature = getParsedFeature(getUri());

            Node node = getComponentByLine(this.gherkinMessagesFeature, getLine());
            if (node instanceof GherkinMessagesExample) {
                GherkinMessagesScenarioOutline gherkinMessagesScenarioOutline = ((GherkinMessagesExample) node).getGherkinMessagesScenarioOutline(this.gherkinMessagesFeature);
                this.constantMap = ((GherkinMessagesExample) node).getLinkedMultiMap();
                scenario = gherkinMessagesScenarioOutline.getScenario();
            } else {
                scenario = ((GherkinMessagesScenario) node).getScenario();
                this.constantMap = new LinkedMultiMap<>();
            }
        }
        }

        private static io.cucumber.messages.types.Group makeMessageGroup (
                Group group
    ){
            return new io.cucumber.messages.types.Group(
                    group.getChildren().stream()
                            .map(TestCase::makeMessageGroup)
                            .collect(toList()),
                    (long) group.getStart(),
                    group.getValue());
        }

        void run (EventBus bus){
            runComponent(bus);
        }

        public void runComponent (EventBus bus){
            setCurrentScenario(scenarioContext);
            ExecutionMode nextExecutionMode = this.executionMode;
            if (scenarioContext.isTopLevel())
                emitTestCaseMessage(bus);

            Instant start = bus.getInstant();
            UUID executionId = bus.generateId();

            if (scenarioContext.isTopLevel())
                emitTestCaseStarted(bus, start, executionId);
            else
                emitComponentCaseStarted(bus, start, executionId);


            TestCaseState state = new TestCaseState(bus, executionId, this);

            for (HookTestStep before : beforeHooks) {
                nextExecutionMode = before
                        .run(this, bus, state, executionMode)
                        .next(nextExecutionMode);
            }

            for (PickleStepTestStep step : testSteps) {
                nextExecutionMode = step
                        .run(this, bus, state, nextExecutionMode)
                        .next(nextExecutionMode);
            }

            for (HookTestStep after : afterHooks) {
                nextExecutionMode = after
                        .run(this, bus, state, executionMode)
                        .next(nextExecutionMode);
            }

            Instant stop = bus.getInstant();
            Duration duration = Duration.between(start, stop);
            Status status = Status.valueOf(state.getStatus().name());
            Result result = new Result(status, duration, state.getError());
            if (scenarioContext.isTopLevel())
                emitTestCaseFinished(bus, executionId, stop, result);
            else
                emitComponentCaseFinished(bus, executionId, stop, result);


        }

        @Override
        public Integer getLine () {
            return pickle.getLocation().getLine();
        }

        @Override
        public Location getLocation () {
            return pickle.getLocation();
        }

        @Override
        public String getKeyword () {
            return pickle.getKeyword();
        }

        @Override
        public String getName () {
            return pickle.getName();
        }

        @Override
        public String getScenarioDesignation () {
            return fileColonLine(getLocation().getLine()) + " # " + getName();
        }

        private String fileColonLine (Integer line){
            return pickle.getUri().getSchemeSpecificPart() + ":" + line;
        }

        @Override
        public List<String> getTags () {
            return pickle.getTags();
        }

        @Override
        public List<TestStep> getTestSteps () {
            List<TestStep> testSteps = new ArrayList<>(beforeHooks);
            for (PickleStepTestStep step : this.testSteps) {
                testSteps.addAll(step.getBeforeStepHookSteps());
                testSteps.add(step);
                testSteps.addAll(step.getAfterStepHookSteps());
            }
            testSteps.addAll(afterHooks);
            return testSteps;
        }

        @Override
        public URI getUri () {
            return pickle.getUri();
        }

        @Override
        public String getCodeLocation () {
            return "";
        }

        @Override
        public UUID getId () {
            return id;
        }

        private void emitTestCaseMessage (EventBus bus){
            Envelope envelope = Envelope.of(new io.cucumber.messages.types.TestCase(
                    id.toString(),
                    pickle.getId(),
                    getTestSteps()
                            .stream()
                            .map(this::createTestStep)
                            .collect(toList())));
            bus.send(envelope);
        }

        private io.cucumber.messages.types.TestStep createTestStep (TestStep pluginTestStep){
            // public TestStep(String hookId, String id, String pickleStepId,
            // List<String> stepDefinitionIds, List<StepMatchArgumentsList>
            // stepMatchArgumentsLists) {
            String id = pluginTestStep.getId().toString();
            String hookId = null;
            String pickleStepId = null;
            List<StepMatchArgumentsList> stepMatchArgumentsLists = null;
            List<String> stepDefinitionIds = null;

            if (pluginTestStep instanceof HookTestStep) {
                HookTestStep hookTestStep = (HookTestStep) pluginTestStep;
                HookDefinitionMatch definitionMatch = hookTestStep.getDefinitionMatch();
                CoreHookDefinition hookDefinition = definitionMatch.getHookDefinition();
                hookId = hookDefinition.getId().toString();
            } else if (pluginTestStep instanceof PickleStepTestStep) {
                PickleStepTestStep pickleStep = (PickleStepTestStep) pluginTestStep;
                pickleStepId = pickleStep.getStep().getId();
                stepMatchArgumentsLists = singletonList(getStepMatchArguments(pickleStep));
                StepDefinition stepDefinition = pickleStep.getDefinitionMatch().getStepDefinition();
                if (stepDefinition instanceof CoreStepDefinition) {
                    CoreStepDefinition coreStepDefinition = (CoreStepDefinition) stepDefinition;
                    stepDefinitionIds = singletonList(coreStepDefinition.getId().toString());
                }
            }

            return new io.cucumber.messages.types.TestStep(hookId, id, pickleStepId, stepDefinitionIds,
                    stepMatchArgumentsLists);
        }

        public StepMatchArgumentsList getStepMatchArguments (PickleStepTestStep pickleStep){
            return new StepMatchArgumentsList(
                    pickleStep.getDefinitionArgument().stream()
                            .map(arg -> new StepMatchArgument(makeMessageGroup(arg.getGroup()), arg.getParameterTypeName()))
                            .collect(Collectors.toList()));
        }

        private void emitTestCaseStarted (EventBus bus, Instant start, UUID executionId){
            bus.send(new TestCaseStarted(start, this));
            Envelope envelope = Envelope.of(new io.cucumber.messages.types.TestCaseStarted(
                    0L,
                    executionId.toString(),
                    id.toString(),
                    Thread.currentThread().getName(),
                    toMessage(start)));
            bus.send(envelope);
        }

        private void emitTestCaseFinished (
                EventBus bus, UUID executionId, Instant stop, Result result
    ){
            bus.send(new TestCaseFinished(stop, this, result));
            Envelope envelope = Envelope.of(new io.cucumber.messages.types.TestCaseFinished(executionId.toString(),
                    toMessage(stop), false));
            bus.send(envelope);
        }


        private void emitComponentCaseStarted (EventBus bus, Instant start, UUID executionId){
            bus.send(new io.cucumber.plugin.event.TestStepStarted(start, scenarioContext.parent.getTestCase(), this));
        }

        private void emitComponentCaseFinished (
                EventBus bus, UUID executionId, Instant stop, Result result
    ){
            bus.send(new TestStepFinished(stop, scenarioContext.parent.getTestCase(), this, result));
        }


    }
