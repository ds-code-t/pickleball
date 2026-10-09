package io.cucumber.core.runner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void stepMaxTimeBareIntegerIsMinutesAndAUnitIsAccepted() {
        assertEquals(Duration.ofMinutes(5), StepData.parseStepMaxTime("5", null));
        assertEquals(Duration.ofMinutes(5), StepData.parseStepMaxTime(5, "30"));
        assertEquals(Duration.ofSeconds(90), StepData.parseStepMaxTime("90s", null));
        assertEquals(Duration.ofMinutes(2), StepData.parseStepMaxTime("2 minutes", "90"));
        assertEquals(Duration.ZERO, StepData.parseStepMaxTime("0", null));
        assertTrue(StepData.parseStepMaxTime("-1", "10").isNegative());
    }

    @Test
    void legacyStepRepeatMaxTimeIsSecondsAndAbsenceDefaultsToSixtyMinutes() {
        assertEquals(Duration.ofSeconds(30), StepData.parseStepMaxTime(null, "30"));
        assertEquals(Duration.ofSeconds(90), StepData.parseStepMaxTime(null, 90));
        assertEquals(Duration.ofMinutes(60), StepData.parseStepMaxTime(null, null));
        assertEquals(Duration.ofMinutes(60), StepData.parseStepMaxTime("  ", ""));
    }

    @Test
    void stepMaxTimeMessageFormatsTheLiveDuration() {
        assertEquals("5m", StepData.formatStepMaxTime(Duration.ofMinutes(5)));
        assertEquals("90s", StepData.formatStepMaxTime(Duration.ofSeconds(90)));
        assertEquals("1ms", StepData.formatStepMaxTime(Duration.ofMillis(1)));
        assertEquals("60m", StepData.formatStepMaxTime(Duration.ofHours(1)));
        Duration previous = StepData.globalTimeoutSeconds;
        StepData.globalTimeoutSeconds = Duration.ofMinutes(5);
        try {
            assertEquals("Step exceeded pkb_stepMaxTime (5m)", StepData.stepMaxTimeExceededMessage());
        } finally {
            StepData.globalTimeoutSeconds = previous;
        }
    }

    @Test
    void untilPauseSleepsOnlyTheTimeLeft() {
        assertEquals(400, StepData.untilPauseMillis(null, Duration.ofMinutes(5)));
        assertEquals(400, StepData.untilPauseMillis(Instant.now(), null));
        assertEquals(400, StepData.untilPauseMillis(Instant.now(), Duration.ofMinutes(-1)));
        assertEquals(0, StepData.untilPauseMillis(Instant.now().minusSeconds(10), Duration.ofSeconds(1)));
        long pause = StepData.untilPauseMillis(Instant.now(), Duration.ofMillis(50));
        assertTrue(pause <= 50, Long.toString(pause));
        assertTrue(pause >= 0, Long.toString(pause));
    }
}
