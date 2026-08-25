package com.example.pickleball;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.java.en.Given;
import tools.dscode.control.api.DynamicControl;
import tools.dscode.control.api.MappingControl;
import tools.dscode.control.protocol.ControlProtocol;

public class ControlApiTestSteps {
    private static int invocationCount;
    private static int rawStackTracePrintCount;

    @Given("^CONTROL API TEST STEP$")
    public void controlApiTestStep() {
        invocationCount++;
    }

    @Given("^CONTROL API FAILING TEST STEP$")
    public void controlApiFailingTestStep() {
        throw new ExpectedControlFailure();
    }

    /**
     * Focused acceptance check for the Workbench player contract. Gherkin parsing
     * and current ParsingMap discovery must both remain inside the consumer worker.
     */
    @Given("^VERIFY WORKBENCH PLAYER RUNTIME SUPPORT$")
    public void verifyWorkbenchPlayerRuntimeSupport() {
        var created = DynamicControl.createStep("Given CONTROL API TEST STEP");
        if (!created.successful()) {
            throw new AssertionError(
                    "Full Gherkin live step was not accepted: "
                            + (created.error() == null ? created.status() : created.error().message())
            );
        }
        if (!"CONTROL API TEST STEP".equals(created.value().getStepText())) {
            throw new AssertionError(
                    "Worker did not normalize the Gherkin keyword before detached execution."
            );
        }

        var catalog = MappingControl.currentNodeMap(
                ControlProtocol.CURRENT_NODE_MAP_CATALOG_REFERENCE
        );
        if (!catalog.successful()) {
            throw new AssertionError(
                    "Current ParsingMap catalog was unavailable: "
                            + (catalog.error() == null ? catalog.status() : catalog.error().message())
            );
        }

        JsonNode maps = catalog.value().getRoot().get("maps");
        if (maps == null || !maps.isArray() || maps.isEmpty()) {
            throw new AssertionError("Current ParsingMap catalog did not expose any NodeMaps.");
        }

        String firstReference = maps.get(0).path("reference").asText();
        var currentMap = MappingControl.currentNodeMap(firstReference);
        if (!currentMap.successful() || currentMap.value() == null) {
            throw new AssertionError(
                    "Catalog NodeMap reference could not be resolved: " + firstReference
            );
        }
    }

    static void reset() {
        invocationCount = 0;
        rawStackTracePrintCount = 0;
    }

    static int invocationCount() {
        return invocationCount;
    }

    static int rawStackTracePrintCount() {
        return rawStackTracePrintCount;
    }

    private static final class ExpectedControlFailure extends RuntimeException {
        private ExpectedControlFailure() {
            super("expected detached control failure");
        }

        @Override
        public void printStackTrace() {
            rawStackTracePrintCount++;
            super.printStackTrace();
        }
    }
}
