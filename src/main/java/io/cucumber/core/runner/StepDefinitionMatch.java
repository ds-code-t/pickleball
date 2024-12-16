package io.cucumber.core.runner;

import io.cucumber.core.backend.TestCaseState;

interface StepDefinitionMatch {

    void runStep(TestCaseState state) throws Throwable;

    void dryRunStep(TestCaseState state) throws Throwable;

    String getCodeLocation();

    default Object findMethod() {
        try {
            var field = this.getClass().getDeclaredField("method");
            field.setAccessible(true);
            return field.get(this);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

}
