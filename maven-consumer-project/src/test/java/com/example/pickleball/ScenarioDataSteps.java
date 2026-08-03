package com.example.pickleball;

import io.cucumber.core.runner.ScenarioStepData;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.coredefinitions.ModularScenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class ScenarioDataSteps {
    private ScenarioDataSteps() {
    }

    @Given("^VERIFY SCENARIO DATA:?(.*)?$")
    public static void verifyScenarioData(
            String inlineArgs,
            DataTable dataTable
    ) {
        ScenarioStepData data =
                ModularScenarios.getScenarioStepData(inlineArgs, dataTable);

        assertNotNull(data);
        assertNotNull(data.getStepExpression());

        assertEquals(
                "---marker <passedValue> <exampleValue>",
                data.getStepText()
        );

        assertEquals(
                "marker <passedValue> <exampleValue>",
                data.getStepMarkerText()
        );

        assertEquals(
                "stored",
                data.getPassedNodeMap()
                        .getRoot()
                        .path("passedValue")
                        .asText()
        );

        assertEquals(
                "example-row",
                data.getExampleNodeMap()
                        .getAsList("exampleValue")
                        .getLast()
                        .asText()
        );

        NodeMap externalPassed =
                new NodeMap(MapConfigurations.MapType.PASSED_MAP);

        externalPassed.put("passedValue", "external");

        assertEquals(
                "---marker external example-row",
                data.getStepText(externalPassed)
        );

        assertEquals(
                "marker external example-row",
                data.getStepMarkerText(externalPassed)
        );
    }
}