package io.cucumber.core.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepRepetitionLimitTest {

    @Test
    void limitOfNAllowsExactlyNPasses() {
        assertFalse(StepData.repetitionLimitReached(99, 100));
        assertTrue(StepData.repetitionLimitReached(100, 100));
        assertFalse(StepData.repetitionLimitReached(0, 100));
    }

    @Test
    void negativeIsUnlimitedAndZeroIsAlreadyAtTheLimit() {
        assertFalse(StepData.repetitionLimitReached(0, -1));
        assertFalse(StepData.repetitionLimitReached(50, -1));
        assertTrue(StepData.repetitionLimitReached(0, 0));
        assertTrue(StepData.repetitionLimitReached(1, 0));
    }
}
