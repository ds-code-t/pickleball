package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.plugin.event.TestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


public interface LineComponent {
//    String scenarioStepText = "Scenario: ";

    List<LineComponent> lineComponentList = new ArrayList<>();
    List<LineComponent> executionList = new ArrayList<>();
//    int nestingLevel = 0;
//    int position = 0;
//    LineComponent parent = null;
//    UUID id = null;


    abstract ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode executionMode);

    abstract ExecutionMode runDynamically(io.cucumber.plugin.event.TestCase testCase, EventBus bus, TestCaseState currentState, ExecutionMode executionMode);

    default ExecutionMode preRunProcess(io.cucumber.plugin.event.TestCase testCase, EventBus bus, TestCaseState currentState, ExecutionMode executionMode) {
        executionList.addAll(lineComponentList);
        return executionMode;
    }


//
//    enum LineType {
//        PICKLE_STEP_DEFINITION,
//        TEST_CASE,
//        UNKNOWN
//    }
//



//    default LineType returnType() {
//        if (this instanceof PickleStepTestStep) {
//            PickleStepTestStep step = (PickleStepTestStep) this;
//            System.out.println("@@ code location:: "+ step.getCodeLocation());
//            System.out.println("@@ code getUri:: "+ step.getUri());
////            step.getDefinitionMatch()
//
//            return LineType.PICKLE_STEP_DEFINITION;
//        } else if (this instanceof io.cucumber.core.runner.TestCase) {
//            io.cucumber.core.runner.TestCase test = (io.cucumber.core.runner.TestCase) this;
//
//            return LineType.TEST_CASE;
//        } else {
//            return LineType.UNKNOWN;
//        }
//    }




}
