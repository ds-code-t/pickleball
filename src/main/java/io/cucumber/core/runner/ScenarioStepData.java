package io.cucumber.core.runner;

import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.stepexpression.StepExpression;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.messages.types.PickleStepArgument;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.util.List;
import java.util.Objects;

import static tools.dscode.common.mappings.MapConfigurations.MapType.EXAMPLE_MAP;
import static tools.dscode.common.mappings.MapConfigurations.MapType.PASSED_MAP;
import static tools.dscode.common.mappings.MapConfigurations.MapType.PHRASE_MAP;
import static tools.dscode.common.mappings.MapConfigurations.MapType.STEP_MAP;

/**
 * Immutable data snapshot for a selected scenario marker step.
 */
public final class ScenarioStepData {
    private final String stepText;
    private final String stepMarkerText;
    private final StepExpression stepExpression;
    private final DocString docString;
    private final DataTable dataTable;
    private final NodeMap passedNodeMap;
    private final NodeMap exampleNodeMap;

    public ScenarioStepData(StepExtension stepExtension) {
        this(
                Objects.requireNonNull(
                        stepExtension,
                        "stepExtension"
                ).getStepParsingMap(),
                selectedSourceStep(stepExtension)
        );
    }

    /**
     * Creates marker data using the owning scenario's parsing-map snapshot and
     * the explicitly selected marker step.
     */
    public ScenarioStepData(
            ScenarioStep scenarioStep,
            StepExtension sourceStep
    ) {
        this(
                Objects.requireNonNull(
                        scenarioStep,
                        "scenarioStep"
                ).getStepParsingMap(),
                requireSourceStep(sourceStep)
        );
    }

    private ScenarioStepData(
            ParsingMap sourceParsingMap,
            StepExtension sourceStep
    ) {
        passedNodeMap = firstCopy(sourceParsingMap, PASSED_MAP);
        exampleNodeMap = firstCopy(sourceParsingMap, EXAMPLE_MAP);
        stepText = sourceStep.getUnmodifiedText();
        stepMarkerText = sourceStep.stepMarkerText;
        stepExpression = findStepExpression(sourceStep);

        PickleStepArgument pickleArgument = sourceStep.pickleStepTestStep
                .getPickleStep()
                .getArgument()
                .orElse(null);

        dataTable = sourceStep.dataTable != null
                ? sourceStep.dataTable
                : dataTableFromPickleArgument(pickleArgument);
        docString = sourceStep.docString != null
                ? sourceStep.docString
                : docStringFromPickleArgument(pickleArgument);
    }

    private static StepExtension selectedSourceStep(
            StepExtension stepExtension
    ) {
        StepExtension selectedStep =
                stepExtension instanceof ScenarioStep scenarioStep
                        ? scenarioStep.getStartStepMarkerStep()
                        : stepExtension;
        return requireSourceStep(selectedStep);
    }

    private static StepExtension requireSourceStep(
            StepExtension sourceStep
    ) {
        if (sourceStep == null) {
            throw new IllegalArgumentException(
                    "The selected scenario did not contain the requested step marker."
            );
        }
        return sourceStep;
    }

    public String getStepText() {
        return stepText;
    }

    public String getStepText(NodeMap passedNodeMap) {
        return resolveText(stepText, passedNodeMap);
    }

    public String getStepMarkerText() {
        return stepMarkerText;
    }

    public String getStepMarkerText(NodeMap passedNodeMap) {
        return resolveText(stepMarkerText, passedNodeMap);
    }

    public StepExpression getStepExpression() {
        return stepExpression;
    }

    public Object getDocStringValue() {
        return docString;
    }

    public Object getDocStringValue(NodeMap passedNodeMap) {
        if (docString == null) {
            return null;
        }

        ParsingMap parsingMap = resolutionParsingMap(passedNodeMap);
        String resolvedContent =
                parsingMap.resolveWholeText(docString.getContent());
        return resolvedContent.equals(docString.getContent())
                ? docString
                : DocString.create(
                        resolvedContent,
                        docString.getContentType()
                );
    }

    public Object getDataTableValue() {
        return dataTable;
    }

    public Object getDataTableValue(NodeMap passedNodeMap) {
        if (dataTable == null) {
            return null;
        }

        ParsingMap parsingMap = resolutionParsingMap(passedNodeMap);
        List<List<String>> resolvedCells = dataTable.cells().stream()
                .map(row -> row.stream()
                        .map(parsingMap::resolveWholeText)
                        .toList())
                .toList();

        return resolvedCells.equals(dataTable.cells())
                ? dataTable
                : DataTable.create(
                        resolvedCells,
                        dataTable.getTableConverter()
                );
    }

    public NodeMap getPassedNodeMap() {
        return copyNodeMap(passedNodeMap, PASSED_MAP);
    }

    public NodeMap getExampleNodeMap() {
        return copyNodeMap(exampleNodeMap, EXAMPLE_MAP);
    }

    static DataTable dataTableFromPickleArgument(
            PickleStepArgument argument
    ) {
        if (argument == null || argument.getDataTable().isEmpty()) {
            return null;
        }

        List<List<String>> cells = argument.getDataTable()
                .orElseThrow()
                .getRows()
                .stream()
                .map(row -> row.getCells()
                        .stream()
                        .map(cell -> cell.getValue())
                        .toList())
                .toList();
        return DataTable.create(cells);
    }

    static DocString docStringFromPickleArgument(
            PickleStepArgument argument
    ) {
        if (argument == null || argument.getDocString().isEmpty()) {
            return null;
        }

        var value = argument.getDocString().orElseThrow();
        return DocString.create(
                value.getContent(),
                value.getMediaType().orElse("")
        );
    }

    private String resolveText(String text, NodeMap externalPassedNodeMap) {
        return text == null
                ? null
                : resolutionParsingMap(externalPassedNodeMap)
                        .resolveWholeText(text);
    }

    private ParsingMap resolutionParsingMap(NodeMap externalPassedNodeMap) {
        return buildResolutionParsingMap(
                ParsingMap.getRunningParsingMap(),
                externalPassedNodeMap,
                passedNodeMap,
                exampleNodeMap
        );
    }

    static ParsingMap buildResolutionParsingMap(
            ParsingMap parentParsingMap,
            NodeMap externalPassedNodeMap,
            NodeMap storedPassedNodeMap,
            NodeMap storedExampleNodeMap
    ) {
        ParsingMap parsingMap = new ParsingMap();
        if (parentParsingMap != null) {
            parsingMap.addMaps(parentParsingMap.getNodeMaps(STEP_MAP));
            parsingMap.addMaps(parentParsingMap.getNodeMaps(PHRASE_MAP));
        }

        NodeMap external = copyNodeMap(externalPassedNodeMap, PASSED_MAP);
        NodeMap storedPassed = copyNodeMap(storedPassedNodeMap, PASSED_MAP);
        NodeMap storedExample = copyNodeMap(storedExampleNodeMap, EXAMPLE_MAP);

        if (external != null) {
            parsingMap.addMaps(external);
        }
        if (storedPassed != null) {
            parsingMap.addMaps(storedPassed);
        }
        if (storedExample != null) {
            parsingMap.addMaps(storedExample);
        }
        return parsingMap;
    }

    private static StepExpression findStepExpression(
            StepExtension stepExtension
    ) {
        PickleStepDefinitionMatch definitionMatch =
                stepExtension.pickleStepTestStep.getDefinitionMatch();
        if (definitionMatch == null) {
            return null;
        }

        StepDefinition stepDefinition = definitionMatch.getStepDefinition();
        return stepDefinition instanceof CoreStepDefinition coreStepDefinition
                ? coreStepDefinition.getExpression()
                : null;
    }

    private static NodeMap firstCopy(
            ParsingMap parsingMap,
            MapConfigurations.MapType mapType
    ) {
        List<NodeMap> maps = parsingMap.getNodeMaps(mapType);
        return maps.isEmpty()
                ? null
                : copyNodeMap(maps.getFirst(), mapType);
    }

    private static NodeMap copyNodeMap(
            NodeMap source,
            MapConfigurations.MapType mapType
    ) {
        if (source == null) {
            return null;
        }

        NodeMap copy = new NodeMap(
                mapType,
                source.getRoot().deepCopy()
        );
        copy.setDataSource(
                source.getDataSources()
                        .toArray(MapConfigurations.DataSource[]::new)
        );
        return copy;
    }
}
