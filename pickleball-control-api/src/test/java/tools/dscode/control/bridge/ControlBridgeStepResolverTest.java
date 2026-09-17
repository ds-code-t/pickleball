package tools.dscode.control.bridge;

import com.example.glue.SampleGlue;
import org.junit.jupiter.api.Test;
import tools.dscode.control.api.DynamicControl;
import tools.dscode.control.api.DynamicStepSpec;
import tools.dscode.control.protocol.ControlBridgeStepResolution;
import tools.dscode.coredefinitions.GeneralSteps;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlBridgeStepResolverTest {
    @Test
    void resolveWithoutScenarioIsUnmatchedAndDoesNotThrow() {
        ControlBridgeStepResolution resolution =
                ControlBridgeStepResolver.resolve("Given this step does not exist anywhere", "");
        assertEquals(ControlBridgeStepResolution.UNMATCHED, resolution.kind());
        assertFalse(resolution.detail().isBlank());
    }

    @Test
    void blankTextIsUnmatched() {
        ControlBridgeStepResolution resolution = ControlBridgeStepResolver.resolve("  ", "");
        assertEquals(ControlBridgeStepResolution.UNMATCHED, resolution.kind());
    }

    @Test
    void matchingStepStripsGherkinKeywords() {
        DynamicStepSpec spec = DynamicControl.matchingStep("Given CONTROL API TEST STEP", "");
        assertEquals("CONTROL API TEST STEP", spec.text());
        DynamicStepSpec raw = DynamicControl.matchingStep("CONTROL API TEST STEP", "");
        assertEquals("CONTROL API TEST STEP", raw.text());
    }

    @Test
    void frameworkClassesAreDynamicAndConsumerGlueIsNot() {
        assertTrue(ControlBridgeStepResolver.isFrameworkClass("tools.dscode.coredefinitions.BrowserSteps"));
        assertTrue(ControlBridgeStepResolver.isFrameworkClass("tools.dscode"));
        assertFalse(ControlBridgeStepResolver.isFrameworkClass("tools.dscodeextra.Glue"));
        assertFalse(ControlBridgeStepResolver.isFrameworkClass("com.example.glue.SampleGlue"));
    }

    @Test
    void consumerGlueIncludesSourcePathWhenTheJavaFileExists() throws Exception {
        Method method = SampleGlue.class.getMethod("aConsumerStep");
        ControlBridgeStepResolution resolution =
                ControlBridgeStepResolver.fromMethod(method, "^a consumer step$");
        assertEquals(ControlBridgeStepResolution.CONSUMER_GLUE, resolution.kind());
        assertEquals(SampleGlue.class.getName(), resolution.className());
        assertEquals("aConsumerStep", resolution.methodName());
        assertEquals("^a consumer step$", resolution.pattern());
        assertTrue(
                resolution.sourcePath().replace('\\', '/').endsWith(
                        "src/test/java/com/example/glue/SampleGlue.java"
                ),
                resolution.sourcePath()
        );
        assertTrue(Path.of(resolution.sourcePath()).isAbsolute());
        assertTrue(resolution.snippet().contains("aConsumerStep()"));
    }

    @Test
    void frameworkGlueLeavesSourcePathEmpty() throws Exception {
        Method method = GeneralSteps.class.getMethod("placeHolder");
        ControlBridgeStepResolution resolution =
                ControlBridgeStepResolver.fromMethod(method, method.getName());
        assertEquals(ControlBridgeStepResolution.DYNAMIC, resolution.kind());
        assertEquals("", resolution.sourcePath());
        assertEquals("Pickleball framework/dynamic step.", resolution.detail());
        assertTrue(resolution.className().startsWith("tools.dscode."));
    }
}
