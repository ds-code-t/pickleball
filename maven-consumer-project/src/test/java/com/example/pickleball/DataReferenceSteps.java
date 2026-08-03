package com.example.pickleball;

import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.ScenarioStepData;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class DataReferenceSteps {
    private DataReferenceSteps() {
    }

    @Given("^VERIFY DATA ADDRESS \"([^\"]+)\" HAS MARKER \"([^\"]+)\"$")
    public static void verifyDataAddress(
            String address,
            String expectedMarker,
            DataTable options
    ) {
        ScenarioStepData data =
                tools.dscode.coredefinitions.ModularScenarios
                        .getScenarioMarkerData(address, options);

        assertNotNull(data);
        assertEquals(expectedMarker, data.getStepMarkerText());
    }

    @Given("^VERIFY EMBEDDED DATA ADDRESS \"([^\"]+)\" HAS MARKER \"([^\"]+)\"$")
    public static void verifyEmbeddedDataAddress(
            String address,
            String expectedMarker
    ) {
        Object value = GlobalState.getRunningStep()
                .getStepParsingMap()
                .resolveWholeValue("<&data:" + address + ">");

        ScenarioStepData data =
                assertInstanceOf(ScenarioStepData.class, value);
        assertEquals(expectedMarker, data.getStepMarkerText());
    }
}
