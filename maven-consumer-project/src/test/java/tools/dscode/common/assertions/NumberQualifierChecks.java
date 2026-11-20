package tools.dscode.common.assertions;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.Test;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.ControlCallStatus;
import tools.dscode.control.api.DynamicControl;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NumberQualifierChecks {

    @Test
    void ensureHardFailsWhenANumberQualifierOperandIsNotNumeric() {
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", ensure \"abc\" number is equal to \"abc\""
        );
        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        String detail = result.error().message() + result.error().stackTrace();
        assertTrue(detail.contains("number qualifier: 'abc' is not a number"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void verifySoftFailsWhenANumberQualifierOperandIsNotNumeric() {
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", verify \"abc\" number is equal to \"abc\""
        );
        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        String detail = result.error().message() + result.error().type() + result.error().stackTrace();
        assertTrue(detail.contains("number qualifier: 'abc' is not a number"), detail);
        assertTrue(detail.contains("SoftRuntimeException"), detail);
        assertFalse(requireScenario().isScenarioFailed());
        ControlCallResult<Object> later = DynamicControl.executeStep(", save \"still\" as \"afterNumberVerify\"");
        assertTrue(later.successful(), () -> String.valueOf(later.error()));
    }

    @Test
    void notEqualDoesNotInvertANonNumericOperand() {
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", ensure \"abc\" number is not equal to \"abc\""
        );
        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        String detail = result.error().message() + result.error().stackTrace();
        assertTrue(detail.contains("number qualifier: 'abc' is not a number"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void integerQualifierRejectsAFractionalValue() {
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", ensure \"1.5\" integer is equal to \"1.5\""
        );
        assertEquals(ControlCallStatus.FAILED, result.status());
        assertNotNull(result.error());
        String detail = result.error().message() + result.error().stackTrace();
        assertTrue(detail.contains("integer qualifier: '1.5' is not an integer"), detail);
        assertFalse(requireScenario().isScenarioFailed());
    }

    @Test
    void numericMarginPasses() {
        ControlCallResult<Object> result = DynamicControl.executeStep(
                ", ensure \"1.00\" number is equal to \"1.05\" within a margin of 0.1"
        );
        assertTrue(result.successful(), () -> String.valueOf(result.error()));
        assertFalse(requireScenario().isScenarioFailed());
    }

    private static CurrentScenarioState requireScenario() {
        CurrentScenarioState state = getCurrentScenarioState();
        assertNotNull(state);
        return state;
    }
}
