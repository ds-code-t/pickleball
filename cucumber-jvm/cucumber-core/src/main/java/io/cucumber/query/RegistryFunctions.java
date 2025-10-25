package io.cucumber.query;

import io.cucumber.messages.types.TestStep;

import static tools.dscode.common.SelfRegistering.localOrGlobalOf;

public class RegistryFunctions {

    public static void putTestStepById(String key, TestStep testStep) {
        localOrGlobalOf(Repository.class).testStepById.put(key, testStep);
    }

    public static void putTestStepById(TestStep testStep) {
        localOrGlobalOf(Repository.class).testStepById.put(testStep.getId(), testStep);
    }
}
