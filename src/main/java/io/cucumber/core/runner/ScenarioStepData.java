package io.cucumber.core.runner;

import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.DataTableArgument;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.core.stepexpression.StepExpression;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.util.List;
import java.util.Objects;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.resolvePickleStepTestStep;
import static tools.dscode.common.mappings.MapConfigurations.MapType.EXAMPLE_MAP;
import static tools.dscode.common.mappings.MapConfigurations.MapType.PASSED_MAP;
import static tools.dscode.common.mappings.MapConfigurations.MapType.PHRASE_MAP;
import static tools.dscode.common.mappings.MapConfigurations.MapType.STEP_MAP;
/**
 * Immutable data snapshot for a selected scenario marker step.
 */
public final class ScenarioStepData {
    private final StepExtension sourceStep;
    private final String stepText;
    private final String stepMarkerText;
    private final StepExpression stepExpression;
    private final DocStringArgument docStringArgument;
    private final DataTableArgument dataTableArgument;
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
        this.sourceStep = sourceStep;
        passedNodeMap = firstCopy(sourceParsingMap, PASSED_MAP);
        exampleNodeMap = firstCopy(sourceParsingMap, EXAMPLE_MAP);
        stepText = sourceStep.getUnmodifiedText();
        stepMarkerText = sourceStep.stepMarkerText;
        stepExpression = findStepExpression(sourceStep);

        List<Argument> arguments = List.copyOf(sourceStep.arguments);
        docStringArgument = firstArgument(arguments, DocStringArgument.class);
        dataTableArgument = firstArgument(arguments, DataTableArgument.class);
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
        return docStringArgument == null ? null : docStringArgument.getValue();
    }

    public Object getDocStringValue(NodeMap passedNodeMap) {
        return docStringArgument == null
                ? null
                : resolvedArgumentValue(DocStringArgument.class, passedNodeMap);
    }

    public Object getDataTableValue() {
        return dataTableArgument == null ? null : dataTableArgument.getValue();
    }
    public Object getDataTableValue(NodeMap passedNodeMap) {
        return dataTableArgument == null
                ? null
                : resolvedArgumentValue(DataTableArgument.class, passedNodeMap);
    }

    public NodeMap getPassedNodeMap() {
        return copyNodeMap(passedNodeMap, PASSED_MAP);
    }

    public NodeMap getExampleNodeMap() {
        return copyNodeMap(exampleNodeMap, EXAMPLE_MAP);
    }
    private String resolveText(String text, NodeMap externalPassedNodeMap) {
        return text == null
                ? null
                : resolutionParsingMap(externalPassedNodeMap).resolveWholeText(text);
    }
    private Object resolvedArgumentValue(
            Class<? extends Argument> argumentType,
            NodeMap externalPassedNodeMap
    ) {
        PickleStepTestStep resolvedStep = resolvePickleStepTestStep(
                sourceStep.pickleStepTestStep,
                resolutionParsingMap(externalPassedNodeMap)
        );
        Argument resolvedArgument = firstArgument(
                resolvedStep.getDefinitionMatch().getArguments(),
                argumentType
        );
        return resolvedArgument == null ? null : resolvedArgument.getValue();
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

    private static StepExpression findStepExpression(StepExtension stepExtension) {
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
        return maps.isEmpty() ? null : copyNodeMap(maps.getFirst(), mapType);
    }

    private static NodeMap copyNodeMap(
            NodeMap source,
            MapConfigurations.MapType mapType
    ) {
        if (source == null) {
            return null;
        }
        NodeMap copy = new NodeMap(mapType, source.getRoot().deepCopy());
        copy.setDataSource(
                source.getDataSources().toArray(MapConfigurations.DataSource[]::new)
        );
        return copy;
    }
    private static <T extends Argument> T firstArgument(
            List<Argument> arguments,
            Class<T> argumentType
    ) {
        return arguments.stream()
                .filter(argumentType::isInstance)
                .map(argumentType::cast)
                .findFirst()
                .orElse(null);
    }
}
