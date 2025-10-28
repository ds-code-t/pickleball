package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;

public interface RunnerExtras {
    TestCase createTestCase(Pickle pickle);

    // NEW: expose the helper that constructs a matched step from text
    PickleStepTestStep createStepForText(Pickle pickle, String stepText);
}
