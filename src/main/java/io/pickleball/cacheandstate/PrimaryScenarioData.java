package io.pickleball.cacheandstate;

import io.cucumber.core.backend.Status;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.runner.*;
import io.pickleball.mapandStateutilities.LinkedMultiMap;
import io.pickleball.valueresolution.ExpressionEvaluator;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static io.pickleball.cacheandstate.GlobalCache.getState;

public class PrimaryScenarioData {


    private ScenarioContext primaryScenario;

    private final Stack<ScenarioContext> scenarioStack = new Stack<>();

    private final Runner runner;
    private final ScenarioContext scenarioContext;
    private final EventBus bus;
    private final Set<Throwable> loggedExceptions = new HashSet<>();
    private final ExpressionEvaluator mvelWrapper = new ExpressionEvaluator();



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

    public static EventBus getBus() {
        return getState().bus;
    }


    public PrimaryScenarioData(Runner runner, TestCase testCase) {
        this.runner = runner;
        this.scenarioContext = testCase;
        this.bus = runner.getBus();
    }


    public static ScenarioContext getPrimaryScenario() {
        return getState().primaryScenario;
    }

    public static void setPrimaryScenario(ScenarioContext primaryScenario) {
        getState().primaryScenario = primaryScenario;
    }

    public static ScenarioContext popCurrentScenario() {
        ScenarioContext poppedScenario = getState().scenarioStack.pop();
//        poppedScenario.getTestCaseState().completeScenario();
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
        throw new RuntimeException("Failed to find CurrentStep");
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

    public static Status getCurrentScenarioStatus(){
        return getCurrentState().getStatus();
    }

    public static Status getPrimaryScenarioStatus(){
        return getPrimaryState().getStatus();
    }

    public static Runner getRunner() {
        return  getState().runner;
    }

    public static ExpressionEvaluator getMvelWrapper() {
        return getState().mvelWrapper;
    }

    public static LinkedMultiMap<String, String> getPrimaryScenarioStateMap() {
        return getPrimaryScenario().getStateMap();
    }
}
