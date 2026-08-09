package com.example.pickleball;

import io.cucumber.java.en.Given;
import tools.dscode.testengine.PickleballRunner;

import java.util.Objects;

public final class ConfigurationValidationSteps {
    @Given("pkb property {string} equals {string}")
    public static void pkbPropertyEquals(String key, String expected) {
        String actual = PickleballRunner.getTestConfigurationValue(key);
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    "Expected " + key + "='" + expected + "' but was '" + actual + "'"
            );
        }
    }
}
