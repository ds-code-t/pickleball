package io.pickleball.cacheandstate;

import io.cucumber.core.backend.Status;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.runner.PickleStepTestStep;
import io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runner.TestCaseState;
import io.pickleball.valueresolution.MVELWrapper;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import static io.pickleball.cacheandstate.GlobalCache.getState;

public class PrimaryScenarioData {

    private ScenarioContext primaryScenario;

    private final Stack<ScenarioContext> scenarioStack = new Stack<>();

    private final Runner runner;
    private final ScenarioContext scenarioContext;
    private final EventBus bus;
    private final Set<Throwable> loggedExceptions = new HashSet<>();
    private final MVELWrapper mvelWrapper = new MVELWrapper();



    public static boolean shouldSendEvent(Throwable... throwables) {
        Set<Throwable> loggedExceptions = getState().loggedExceptions;
        if(throwables == null || throwables.length ==0 )
            return false;

        boolean allNotLogged = true;

        for (Throwable throwable: throwables) {

            // Traverse through all nested throwables
            while (throwable != null) {
                // Check if the current throwable is already logged
                if (!loggedExceptions.add(throwable)) {
                    allNotLogged = false; // Mark as not fully new
                }

                // Unwrap InvocationTargetException or other wrappers
                if (throwable instanceof InvocationTargetException) {
                    throwable = ((InvocationTargetException) throwable).getTargetException();
                } else {
                    throwable = throwable.getCause(); // Move to the next nested throwable
                }
            }

        }
        return allNotLogged;
    }

//
//    public static void startEvent() {
//        getCurrentStep().sendStartEvent();
//    }
//
//    public static void endEvent() {
//        getCurrentStep().sendEndEvent();
//    }


//    public static StepContext getCurrentStep() {
//        return getCurrentScenario().currentStep;
//    }
//
//    public static void setCurrentStep(StepContext currentStep) {
//        getCurrentScenario().currentStep = currentStep;
//    }

    public static EventBus getBus() {
        return getState().bus;
    }


    public PrimaryScenarioData(Runner runner, TestCase testCase) {
        this.runner = runner;
        this.scenarioContext = testCase;
        this.bus = runner.getBus();
    }


//    public PrimaryScenarioData(Runner runner, TestCase testCase) {
//
//    }

    public static ScenarioContext getPrimaryScenario() {
        return getState().primaryScenario;
    }

    public static void setPrimaryScenario(ScenarioContext primaryScenario) {
        getState().primaryScenario = primaryScenario;
    }

    public static ScenarioContext popCurrentScenario() {
        ScenarioContext poppedScenario = getState().scenarioStack.pop();
        poppedScenario.getTestCaseState().completeScenario();
        return poppedScenario;
    }

    public static ScenarioContext getCurrentScenario() {
        return getState().scenarioStack.peek();
    }

    public static PickleStepTestStep getCurrentStep() {
        Stack<ScenarioContext> scenarioContextStack = getState().scenarioStack;
        for (ScenarioContext scenarioContext1 : scenarioContextStack) {
            if (!scenarioContext1.getExecutingStepStack().isEmpty())
                return scenarioContext1.getExecutingStepStack().peek();
        }
        return null;
    }

    public static void setCurrentScenario(ScenarioContext currentScenario) {
        getState().scenarioStack.add(currentScenario);
    }

    public static TestCaseState getCurrentState() {
        return getState().scenarioStack.peek().getTestCaseState();
    }
    public static TestCaseState getPrimaryState() {
        return getPrimaryScenario().getTestCaseState();
    }

    public static Status[] getCurrentScenarioCompletionStatus() {
        return getCurrentState().getCompletionStatus();
    }

    public static Status[] getPrimaryScenarioCompletionStatus(){
        return getPrimaryState().getCompletionStatus();
    }

    public static Status getCurrentScenarioStatus(){
        return getCurrentState().getStatus();
    }

    public static Status getPrimaryScenarioStatus(){
        return getPrimaryState().getStatus();
    }

    public static Runner getRunner() {
        return  getState().runner;
    }

    public static MVELWrapper getMvelWrapper() {
        return getState().mvelWrapper;
    }


//    public static boolean assertScenarioExecutionStatus(String scenarioType, String status) {
//
//    }


}
