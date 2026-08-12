package com.example.pickleball;

import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.ScenarioStepData;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
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

    @Given("^VERIFY DATA ADDRESS \"([^\"]+)\" HAS DATA TABLE WITH (\\d+) DATA ROWS$")
    public static void verifyDataAddressDataTable(
            String address,
            int expectedDataRows
    ) {
        ScenarioStepData data =
                tools.dscode.coredefinitions.ModularScenarios
                        .getScenarioMarkerData(address);
        assertNotNull(data);
        DataTable dataTable = assertInstanceOf(
                DataTable.class,
                data.getDataTableValue()
        );
        assertEquals(expectedDataRows + 1, dataTable.height());
    }

    @Given("^VERIFY DATA ADDRESS \"([^\"]+)\" HAS DOC STRING CONTENT \"([^\"]+)\"$")
    public static void verifyDataAddressDocString(
            String address,
            String expectedContent
    ) {
        ScenarioStepData data =
                tools.dscode.coredefinitions.ModularScenarios
                        .getScenarioMarkerData(address);
        assertNotNull(data);
        DocString docString = assertInstanceOf(
                DocString.class,
                data.getDocStringValue()
        );
        assertEquals(expectedContent, docString.getContent());
    }

    @Given("^VERIFY EMBEDDED DATA TABLE ADDRESS \"([^\"]+)\" HAS (\\d+) DATA ROWS$")
    public static void verifyEmbeddedDataTableAddress(
            String address,
            int expectedDataRows
    ) {
        DataTable dataTable = assertInstanceOf(
                DataTable.class,
                embeddedValue(address)
        );
        assertEquals(expectedDataRows + 1, dataTable.height());
    }

    @Given("^VERIFY EMBEDDED DOC STRING ADDRESS \"([^\"]+)\" HAS CONTENT \"([^\"]+)\"$")
    public static void verifyEmbeddedDocStringAddress(
            String address,
            String expectedContent
    ) {
        DocString docString = assertInstanceOf(
                DocString.class,
                embeddedValue(address)
        );
        assertEquals(expectedContent, docString.getContent());
    }

    @Given("^VERIFY CURRENT SCENARIO MARKER CACHE HAS NAMED \"([^\"]+)\" AND UNNAMED STEP (\\d+)$")
    public static void verifyCurrentScenarioMarkerCache(
            String namedMarker,
            int unnamedStepNumber
    ) {
        ScenarioStep currentScenario =
                GlobalState.getClosestScenarioStepAncestor();
        assertNotNull(currentScenario);
        assertNotNull(currentScenario.getStepMarkerSteps().get(namedMarker));
        assertNotNull(
                currentScenario.getUnnamedStepMarkerStep(unnamedStepNumber)
        );
    }

    private static Object embeddedValue(String address) {
        return GlobalState.getRunningStep()
                .getStepParsingMap()
                .resolveWholeValue("<data:" + address + ">");
    }
}
