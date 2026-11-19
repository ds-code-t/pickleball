package tools.dscode.control.bridge;

import org.junit.jupiter.api.Test;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.ControlBridgeStepResolution;

import com.example.glue.NoopSamples;

import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Empty consumer glue is flagged. A method that calls something is not. */
class NoopGlueFlagTest {
    @Test
    void emptyConsumerGlueIsFlaggedAndARealMethodIsNot() throws Exception {
        Method empty = NoopSamples.class.getDeclaredMethod("emptyGlue");
        Method real = NoopSamples.class.getDeclaredMethod("realGlue");
        Method returnsNull = NoopSamples.class.getDeclaredMethod("returnsNull");
        assertTrue(NoopGlue.consumerEmpty(empty), "emptyGlue");
        assertTrue(NoopGlue.consumerEmpty(returnsNull), "returnsNull");
        assertFalse(NoopGlue.consumerEmpty(real), "realGlue");
        assertFalse(NoopGlue.consumerEmpty(NoopGlue.class.getDeclaredMethod("stamp", String.class)));

        ControlBridgeStepResolution emptyResolution = ControlBridgeStepResolver.fromMethod(empty, "^empty$");
        assertEquals("noop:true", emptyResolution.detail());
        ControlBridgeStepResolution realResolution = ControlBridgeStepResolver.fromMethod(real, "^real$");
        assertFalse(realResolution.detail().contains("noop:true"), realResolution.detail());

        ControlBridgeCallResult stamped = NoopGlue.decorate(empty, success(null));
        assertEquals("SUCCESS", stamped.status());
        assertEquals("noop:true", stamped.valueText());

        String previous = System.getProperty("pkb_noop_glue");
        try {
            System.setProperty("pkb_noop_glue", "fail");
            ControlBridgeCallResult failed = NoopGlue.decorate(empty, success("ok"));
            assertEquals("FAILED", failed.status());
            assertEquals("noop:true", failed.valueText());
            assertEquals("NOOP_GLUE", failed.error().type());
        } finally {
            if (previous == null) System.clearProperty("pkb_noop_glue");
            else System.setProperty("pkb_noop_glue", previous);
        }

        StringWriter err = new StringWriter();
        NoopGlue.warnIfPresent("{\"valueText\":\"noop:true\"}", err);
        assertTrue(err.toString().contains("WARNING: noop consumer glue (noop:true)"), err.toString());
        assertTrue(err.toString().contains("pkb_noop_glue=fail"), err.toString());
        StringWriter quiet = new StringWriter();
        NoopGlue.warnIfPresent("{\"valueText\":\"clicked\"}", quiet);
        assertEquals("", quiet.toString());
    }

    private static ControlBridgeCallResult success(String value) {
        return new ControlBridgeCallResult("SUCCESS", "java.lang.String", value, null, null);
    }
}
