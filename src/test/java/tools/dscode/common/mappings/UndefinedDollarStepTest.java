package tools.dscode.common.mappings;

import io.cucumber.core.runner.CurrentScenarioState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.dscode.common.control.ControlExecutionScope;
import tools.dscode.engine.context.ExecutionContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native runs have no Cucumber step while a {@code $} placeholder is resolved.
 * That used to NPE. It reports the same undefined-step text as the adapter.
 */
class UndefinedDollarStepTest {
    @AfterEach
    void clearRunState() {
        CurrentScenarioState.currentScenarioState.remove();
        while (ExecutionContext.current() != null) {
            ExecutionContext.current().close();
        }
    }

    @Test
    void missingRunningStepReportsUndefinedInsteadOfNullPointer() {
        CurrentScenarioState.currentScenarioState.remove();
        while (ExecutionContext.current() != null) {
            ExecutionContext.current().close();
        }
        assertTrue(ControlExecutionScope.currentStepOverride() == null);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> new ParsingMap().resolveWholeText("<$nope>"));
        assertFalse(causedBy(thrown, NullPointerException.class), thrown.toString());
        String message = thrown.getMessage() == null ? "" : thrown.getMessage();
        assertTrue(message.contains("'nope' step is undefined"), message);
    }

    private static boolean causedBy(Throwable thrown, Class<? extends Throwable> type) {
        Throwable cursor = thrown;
        while (cursor != null) {
            if (type.isInstance(cursor)) return true;
            cursor = cursor.getCause();
        }
        return false;
    }
}
