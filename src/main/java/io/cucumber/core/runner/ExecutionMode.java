package io.cucumber.core.runner;

import io.cucumber.plugin.event.Status;

public enum ExecutionMode {

    RUN {
        @Override
        Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) throws Throwable {
            System.out.println("@@execute1");
            stepDefinitionMatch.runStep(state);
            System.out.println("@@execute2");

            return Status.PASSED;
        }

    },
    DRY_RUN {
        @Override
        Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) throws Throwable {
            stepDefinitionMatch.dryRunStep(state);
            return Status.PASSED;
        }
    },
    SKIP {
        @Override
        Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) {
            return Status.SKIPPED;
        }
    };

    abstract Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) throws Throwable;

    ExecutionMode next(ExecutionMode current) {
        return current == SKIP ? current : this;
    }
}
