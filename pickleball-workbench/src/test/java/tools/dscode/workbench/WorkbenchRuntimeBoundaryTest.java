package tools.dscode.workbench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkbenchRuntimeBoundaryTest {
    @Test
    void workbenchTestProcessCannotLoadConsumerRuntimeClasses() {
        assertDoesNotThrow(WorkbenchRuntimeBoundary::verify);
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("tools.dscode.testengine.WorkbenchWorkerMain")
        );
    }
}
