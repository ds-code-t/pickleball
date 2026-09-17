package tools.dscode.control.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.dscode.common.mappings.NodeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepMapSeedsTest {
    @AfterEach
    void clearSeeds() {
        StepMapSeeds.clear();
    }

    @Test
    void roundTripsReferenceAndAppliesSeededValuesOntoALiveStepMap() {
        String step = "Given a user is ready";
        String reference = StepMapSeeds.referenceFor(step);
        assertTrue(reference.startsWith(StepMapSeeds.PREFIX));
        assertEquals("a user is ready", StepMapSeeds.stepTextOf(reference));

        StepMapSeeds.mapFor(step).put("preset", "hello");
        NodeMap live = MappingControl.nodeMap(tools.dscode.common.mappings.MapConfigurations.MapType.STEP_MAP);
        StepMapSeeds.apply(live, "When a user is ready");
        assertTrue(String.valueOf(live.get("preset")).contains("hello"));
    }
}