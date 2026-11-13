package tools.dscode.workbench;

import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.JavaEngineLauncher;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Java DOM engine is on the Workbench test classpath and is not a compile dependency. */
class JavaEngineClasspathTest {
    @Test
    void engineMainIsVisibleToTheWorkbenchTestLoader() {
        assertTrue(JavaEngineLauncher.available(JavaEngineClasspathTest.class.getClassLoader()));
    }
}
