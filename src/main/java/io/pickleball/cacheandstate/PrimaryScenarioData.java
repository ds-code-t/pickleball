package io.pickleball.cacheandstate;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.runner.Runner;
import io.cucumber.core.runner.TestCase;
import io.cucumber.core.runner.TestCaseState;

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
        this.scenarioContext = testCase.scenarioContext;
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
        return getState().scenarioStack.pop();
    }

    public static ScenarioContext getCurrentScenario() {
        return getState().scenarioStack.peek();
    }

    public static StepContext getCurrentStep() {
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



    public static Runner getRunner() {
        return  getState().runner;
    }
}
