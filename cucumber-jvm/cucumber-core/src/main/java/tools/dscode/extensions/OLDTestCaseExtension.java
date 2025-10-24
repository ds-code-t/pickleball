// package tools.dscode.extensions;
//
// import io.cucumber.core.backend.TestCaseState;
// import io.cucumber.core.eventbus.EventBus;
// import io.cucumber.plugin.event.PickleStepTestStep;
// import io.cucumber.plugin.event.TestCase;
// import tools.dscode.common.SelfRegistering;
// import tools.dscode.common.mappings.MapConfigurations;
// import tools.dscode.common.mappings.NodeMap;
// import tools.dscode.common.mappings.ParsingMap;
// import tools.dscode.common.trace.ObjDataRegistry;
// import tools.dscode.state.ScenarioState;
//
// import java.util.ArrayList;
// import java.util.HashMap;
// import java.util.List;
// import java.util.Map;
//
// import static
// tools.dscode.common.mappings.MapConfigurations.DataSource.EXAMPLE_TABLE;
// import static tools.dscode.common.trace.ObjDataRegistry.setFlag;
// import static tools.dscode.common.util.Reflect.getProperty;
// import static tools.dscode.extensions.StepRelationships.pairSiblings;
// import static tools.dscode.state.ScenarioState.getScenarioPickle;
// import static tools.dscode.state.ScenarioState.getScenarioState;
// import static tools.dscode.state.ScenarioState.getTestCase;
//
// public class OLDTestCaseExtension extends SelfRegistering {
// public final List<OLDStepExtension> steps = new ArrayList<>();
//
// private OLDStepExtension rootScenarioNameStep;
//
// public OLDTestCaseExtension() {
// }
//
// private void initialize() {
// List<PickleStepTestStep> pSteps = (List<PickleStepTestStep>)
// getProperty(this, "testSteps");
// setFlag(pSteps.get(pSteps.size() - 1), ObjDataRegistry.ObjFlags.LAST);
//
// pSteps.forEach(step -> steps.add(new OLDStepExtension(step, this,
// getScenarioState().getScenarioPickle())));
//
// setFlag(pSteps.get(pSteps.size() - 1), ObjDataRegistry.ObjFlags.LAST);
//
// pSteps.forEach(step -> steps.add(new OLDStepExtension(step, this,
// getScenarioState().getScenarioPickle())));
//
// // List<PickleStepTestStep> pSteps = (List<PickleStepTestStep>)
// // getProperty(testCase, "testSteps");
//
// ScenarioState scenarioState = getScenarioState();
// NodeMap scenarioMap = scenarioState.getScenarioMap(getScenarioPickle());
// System.out.println("\n@@##scenarioMap:: " + scenarioMap);
// System.out.println("\n\n======\n\n");
// Map<Integer, OLDStepExtension> nestingMap = new HashMap<>();
//
// rootScenarioNameStep = new OLDStepExtension(getScenarioPickle(), this,
// steps.getFirst().delegate);
// // META_FLAG + "-ROOT-"
// System.out.println("@@getScenarioState(): " + getScenarioState());
// ParsingMap rootParsingMap = new ParsingMap();
//
// if (scenarioMap != null) {
// scenarioMap.setDataSource(EXAMPLE_TABLE);
// scenarioMap.setMapType(MapConfigurations.MapType.STEP_MAP);
// rootParsingMap.addMaps(scenarioMap);
// rootScenarioNameStep.setStepParsingMap(rootParsingMap);
// }
//
// getScenarioState().setParsingMap(rootParsingMap);
//
// nestingMap.put(-1, rootScenarioNameStep);
// setNesting(steps, 0, nestingMap);
// }
//
// public static void setNesting(
// List<OLDStepExtension> steps, int startingNesting, Map<Integer,
// OLDStepExtension> nestingMap
// ) {
// int size = steps.size();
//
// // Map<Integer, StepExtension> nestingMap = new HashMap<>();
//
// int lastNestingLevel = 0;
//
// for (int s = 0; s < size; s++) {
// OLDStepExtension currentStep = steps.get(s);
// currentStep.setNestingLevel(currentStep.getNestingLevel() + startingNesting);
// // int currentNesting = currentStep.nestingLevel + startingNesting;
// int currentNesting = currentStep.getNestingLevel();
//
// OLDStepExtension parentStep = nestingMap.get(currentNesting - 1);
//
// OLDStepExtension previousSibling = currentNesting > lastNestingLevel ? null
// : nestingMap.get(currentNesting);
//
// if (previousSibling != null) {
// pairSiblings(previousSibling, currentStep);
// }
// if (parentStep != null) {
// parentStep.addChildStep(currentStep);
// // currentStep.setParentStep(parentStep);
// }
//
// nestingMap.put(currentNesting, currentStep);
// lastNestingLevel = currentNesting;
//
// }
// }
//
// public void runSteps(Object executionMode) {
// runSteps(getTestCase(), getScenarioState().bus,
// getScenarioState().getTestCaseState(),
// executionMode);
// }
//
// private boolean scenarioHardFail = false;
// private boolean scenarioSoftFail = false;
// private boolean scenarioComplete = false;
//
// public boolean isScenarioFailed() {
// return scenarioHardFail || scenarioSoftFail;
// }
//
// public boolean isScenarioHardFail() {
// return scenarioHardFail;
// }
//
// public boolean isScenarioSoftFail() {
// return scenarioSoftFail;
// }
//
// public void setScenarioHardFail() {
// this.scenarioHardFail = true;
// setScenarioComplete();
// }
//
// public void setScenarioSoftFail() {
// this.scenarioSoftFail = true;
// }
//
// public void setScenarioComplete() {
// this.scenarioComplete = true;
// }
//
// public boolean isScenarioComplete() {
// return this.scenarioComplete;
// }
//
// public void runSteps(TestCase testCase, EventBus bus, TestCaseState state,
// Object executionMode) {
//
// setFlag(testCase, ObjDataRegistry.ObjFlags.RUNNING);
//
// OLDStepExtension currentStep = steps.get(0);
//
// rootScenarioNameStep.run(testCase, bus, state, executionMode);
//
// // ScenarioState scenarioState = getScenarioState();
// // StepExtension lastExecuted = currentStep.run(testCase, bus, state,
// // executionMode);
// // while (currentStep != null) {
// // currentStep = lastExecuted.runNextSibling();
// // }
//
// //
// // for (StepExtension step : steps) {
// // System.out.println("@@step: " + step.getStepText());
// // StepExtension newStep = step.updateStep(parsingMap);
// // newStep.run(testCase, bus, state, executionMode);
// // if (newStep.result.getStatus().equals(Status.PASSED))
// // continue;
// // Throwable throwable = newStep.result.getError();
// // if (throwable.getClass().equals(SoftException.class) ||
// // throwable.getClass().equals(SoftRuntimeException.class))
// // continue;
// // System.out.println("@@newStep.result: " + newStep.result);
// // break;
// // }
//
// }
//
// }
