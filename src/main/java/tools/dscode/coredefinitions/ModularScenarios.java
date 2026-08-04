package tools.dscode.coredefinitions;
import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.ScenarioStepData;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static io.cucumber.core.options.Constants.FILTER_NAME_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.core.runner.GlobalState.getClosestScenarioStepAncestor;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.ScenarioStep.createScenarioStep;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.variables.RunVars.resolveFromVars;
import static tools.dscode.testengine.PKB_props.PKB_CALL_PATH;
import static tools.dscode.testengine.PKB_props.PKB_COMPONENT_PATH;
import static tools.dscode.testengine.PKB_props.PKB_DATA_PATH;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_FEATURE_NAME;
import static tools.dscode.testengine.PKB_props.PKB_NAME;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

public class ModularScenarios extends CoreSteps {
    static final String RUN_TAGS = "Run Tags";
    static final String RUN_KEY = "RunKey";
    static final String TAGS = "Tags";
    static final String CUCUMBER_FEATURES = "cucumber.features";
    static final String STEP_MARKER = "Step_Marker";
    static final String RETURN = "RETURN";
    static final String DEFAULT_DATA_PATH = "src/test/resources/data";
    static final String DEFAULT_CALLS_PATH = "src/test/resources/calls";
    static final String DEFAULT_COMPONENT_PATH = "src/test/resources/component";
    private static final Pattern INLINE_ARGUMENT_PATTERN = Pattern.compile(
            "(?i)(?<!\\S)(FEATURE|SCENARIO|START):"
    );
    @Given("^RUN\\s+(?:\"([^\"]+)\"\\s+)?(SCENARIO|COMPONENT SCENARIO|SERVICE CALL)(S)?:?(.*)?$")
    public static void runScenarios(
            String inlineRunKey,
            String runTypeText,
            String pluralFlag,
            String inlineArgs,
            DataTable dataTable
    ) {
        RunType runType = RunType.fromStepText(runTypeText);
        populateRunScenariosStep(
                getRunningStep(),
                pluralFlag,
                inlineArgs,
                dataTable,
                runType,
                runType.matchType(),
                "RUN " + runType.stepText(),
                (scenarioStep, passedValues) -> registerRunReference(
                        scenarioStep,
                        passedValues,
                        inlineRunKey
                )
        );
    }

    @Given("^SCENARIO:(.*)$")
    public static Object inlineScenario(
            String inlineArgs,
            DataTable dataTable
    ) {
        return runSingleScenario(inlineArgs, dataTable, RunType.SCENARIO);
    }

    @Given("^COMPONENT:(.*)$")
    public static Object inlineComponent(
            String inlineArgs,
            DataTable dataTable
    ) {
        return runSingleScenario(inlineArgs, dataTable, RunType.COMPONENT_SCENARIO);
    }

    static Object runSingleScenario(
            String inlineArgs,
            DataTable dataTable,
            RunType runType
    ) {
        StepExtension triggerStep = getRunningStep();
        ScenarioStep[] scenarioHolder = new ScenarioStep[1];
        @SuppressWarnings("unchecked")
        Map<String, String>[] passedValuesHolder = new Map[1];

        populateRunScenariosStep(
                triggerStep,
                null,
                inlineArgs,
                dataTable,
                runType,
                runType.matchType(),
                runType.convenienceStepText(),
                (scenarioStep, passedValues) -> {
                    scenarioHolder[0] = scenarioStep;
                    passedValuesHolder[0] = passedValues;
                }
        );

        ScenarioStep nestedScenarioStep = scenarioHolder[0];
        if (nestedScenarioStep == null) {
            throw new IllegalStateException(
                    "No " + runType.matchType() + " was created for "
                            + runType.convenienceStepText() + " selector: "
                            + normalize(inlineArgs)
            );
        }

        nestedScenarioStep.setNestingLevel(triggerStep.getNestingLevel() + 1);
        detachChild(triggerStep, nestedScenarioStep);
        getCurrentScenarioState().runStep(nestedScenarioStep);

        NodeMap nestedScenarioMap = nestedScenarioStep.getDefaultStepNodeMap();
        Object returnedValue = scenarioReturnValue(nestedScenarioMap);

        String runKey = resolve(
                nestedScenarioStep,
                passedValuesHolder[0].get(RUN_KEY)
        );
        if (!runKey.isBlank()) {
            saveRunValue(runKey, returnedValue);
        }
        return returnedValue;
    }

    static Object scenarioReturnValue(NodeMap scenarioMap) {
        JsonNode returnNode = scenarioMap.getRoot().get(RETURN);
        if (returnNode == null) {
            return scenarioMap.getRoot();
        }
        return returnNode.isNull() ? null : scenarioMap.get(RETURN);
    }

    private static void registerRunReference(
            ScenarioStep scenarioStep,
            Map<String, String> passedValues,
            String inlineRunKey
    ) {
        String runKey = firstNonBlank(
                resolve(scenarioStep, passedValues.get(RUN_KEY)),
                resolve(scenarioStep, inlineRunKey)
        );
        if (!runKey.isBlank()) {
            getRunMap().putReference(
                    runKey,
                    scenarioStep.getDefaultStepNodeMap().getRoot()
            );
        }
    }

    private static void saveRunValue(String runKey, Object value) {
        if (value instanceof JsonNode jsonNode) {
            getRunMap().putReference(runKey, jsonNode);
        } else {
            getRunMap().put(runKey, value);
        }
    }

    private static void detachChild(
            StepExtension parent,
            ScenarioStep child
    ) {
        parent.childSteps.remove(child);
        if (child.previousSibling != null) {
            child.previousSibling.nextSibling = child.nextSibling;
        }
        if (child.nextSibling != null) {
            child.nextSibling.previousSibling = child.previousSibling;
        }
        child.previousSibling = null;
        child.nextSibling = null;
    }

    private static String resolve(ScenarioStep scenarioStep, String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "";
        }
        return normalize(
                scenarioStep.getStepParsingMap().resolveWholeText(normalized)
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    /**
     * Selects one component scenario with the same inline arguments and
     * invocation table used by {@code RUN SCENARIO}, without attaching or
     * executing it, and returns the selected start-marker data.
     *
     * @return marker data, or {@code null} when no nonblank Step_Marker was supplied
     */
    public static ScenarioStepData getScenarioStepData(
            String inlineArgs,
            DataTable dataTable
    ) {
        return getScenarioStepData(inlineArgs, dataTable, null);
    }
    public static ScenarioStepData getScenarioStepData(
            String inlineArgs,
            DataTable dataTable,
            String featuresPath
    ) {
        List<Map<String, String>> maps =
                buildRunScenarioMaps(inlineArgs, dataTable);
        if (maps.isEmpty() || maps.stream().allMatch(
                map -> normalize(map.get(STEP_MARKER)).isBlank()
        )) {
            return null;
        }
        List<PickleMatch> matches = collectMatches(
                maps,
                featuresPath,
                "component scenario",
                false
        );
        validateDataMatchCount(matches);

        if (matches.isEmpty()) {
            return null;
        }
        PickleMatch match = matches.getFirst();
        if (normalize(match.passedValues().get(STEP_MARKER)).isBlank()) {
            return null;
        }
        return new ScenarioStepData(prepareScenarioStep(match, null));
    }
    public static ScenarioStepData getScenarioMarkerData(String dataAddress) {
        return getScenarioMarkerData(dataAddress, null);
    }
    /**
     * Retrieves marker data using {@code marker}, {@code scenario.marker}, or
     * {@code feature.scenario.marker} address syntax.
     */
    public static ScenarioStepData getScenarioMarkerData(
            String dataAddress,
            DataTable options
    ) {
        DataAddress address = parseDataAddress(dataAddress);
        if (address == null) {
            return null;
        }
        String featureName = address.featureName();
        String scenarioName = address.scenarioName();
        String featuresPath = configuredDataPath();
        if (featureName.isBlank()
                && scenarioName.isBlank()
                && featuresPath.isBlank()
                && dataTableRows(options).isEmpty()) {
            ScenarioStep currentScenario = getClosestScenarioStepAncestor();
            if (currentScenario == null) {
                throw new IllegalStateException(
                        "A marker-only data address requires a running root or component scenario."
                );
            }
            return currentScenario.getStepMarkerData(address.stepMarker());
        }
        boolean tableHasFeaturePath = hasOption(
                options,
                PKB_FEATURES,
                CUCUMBER_FEATURES
        );
        boolean tableHasFeatureName = hasOption(options, PKB_FEATURE_NAME);
        boolean tableHasScenarioSelector = hasOption(
                options,
                PKB_NAME,
                FILTER_NAME_PROPERTY_NAME,
                RUN_TAGS,
                TAGS,
                PKB_TAGS,
                FILTER_TAGS_PROPERTY_NAME
        );
        if (scenarioName.isBlank() && !tableHasScenarioSelector) {
            ScenarioStep currentScenario = getClosestScenarioStepAncestor();
            if (currentScenario == null) {
                throw new IllegalStateException(
                        "A marker-only data address requires a running root or component scenario."
                );
            }
            scenarioName = normalize(currentScenario.getSourceScenarioName());
            if (scenarioName.isBlank()) {
                throw new IllegalStateException(
                        "The current scenario does not expose a source scenario name."
                );
            }
            if (featuresPath.isBlank()
                    && !tableHasFeaturePath
                    && featureName.isBlank()
                    && !tableHasFeatureName) {
                featuresPath = normalize(currentScenario.getSourceFeaturePath());
                featureName = normalize(
                        CucumberScanUtil.getFeatureName(
                                currentScenario.getSourcePickle()
                        )
                );
                if (featuresPath.isBlank()) {
                    throw new IllegalStateException(
                            "The current scenario does not expose a source feature path."
                    );
                }
            }
        }
        if (featuresPath.isBlank() && !tableHasFeaturePath) {
            featuresPath = DEFAULT_DATA_PATH;
        }
        String inlineArgs = buildDataInlineArguments(
                featureName,
                scenarioName,
                address.stepMarker()
        );
        return getScenarioStepData(inlineArgs, options, featuresPath);
    }
    static DataAddress parseDataAddress(String dataAddress) {
        String normalized = normalize(dataAddress);
        if (normalized.isBlank()) {
            return null;
        }
        String[] parts = normalized.split("\\.", -1);
        if (parts.length > 3) {
            throw new IllegalArgumentException(
                    "Data addresses support marker, scenario.marker, or "
                            + "feature.scenario.marker. Feature names, scenario names, "
                            + "and step markers used for data retrieval cannot contain "
                            + "period characters because periods separate the address."
            );
        }
        if (normalize(parts[parts.length - 1]).isBlank()) {
            return null;
        }
        String[] padded = new String[3];
        java.util.Arrays.fill(padded, "");
        System.arraycopy(parts, 0, padded, 3 - parts.length, parts.length);
        return new DataAddress(
                normalize(padded[0]),
                normalize(padded[1]),
                normalize(padded[2])
        );
    }
    private static String buildDataInlineArguments(
            String featureName,
            String scenarioName,
            String stepMarker
    ) {
        StringBuilder inlineArgs = new StringBuilder();
        if (!normalize(featureName).isBlank()) {
            inlineArgs.append("FEATURE: ").append(featureName).append(' ');
        }
        if (!normalize(scenarioName).isBlank()) {
            inlineArgs.append("SCENARIO: ").append(scenarioName).append(' ');
        }
        inlineArgs.append("START: ").append(stepMarker);
        return inlineArgs.toString();
    }
    private static String configuredDataPath() {
        Object configured = resolveFromVars(PKB_DATA_PATH);
        return configured == null ? "" : normalize(configured.toString());
    }
    private static boolean hasOption(DataTable options, String... names) {
        return dataTableRows(options).stream().anyMatch(row ->
                java.util.Arrays.stream(names).anyMatch(name ->
                        !normalize(row.get(name)).isBlank()
                )
        );
    }
    public static void populateRunScenariosStep(
            StepExtension topStep,
            String inlineArgs,
            DataTable dataTable
    ) {
        appendMatches(
                topStep,
                collectMatches(
                        buildRunScenarioMaps(inlineArgs, dataTable),
                        (String) null,
                        null,
                        false
                ),
                null
        );
    }
    public static void populateRunScenariosStep(
            StepExtension topStep,
            String inlineArgs,
            DataTable dataTable,
            String featuresPath,
            String singleMatchType,
            BiConsumer<ScenarioStep, Map<String, String>> scenarioInitializer
    ) {
        appendMatches(
                topStep,
                collectMatches(
                        buildRunScenarioMaps(inlineArgs, dataTable),
                        featuresPath,
                        singleMatchType,
                        singleMatchType != null
                ),
                scenarioInitializer
        );
    }
    static void populateRunScenariosStep(
            StepExtension topStep,
            String pluralFlag,
            String inlineArgs,
            DataTable dataTable,
            String featuresPath,
            String matchType,
            String singularStepText,
            BiConsumer<ScenarioStep, Map<String, String>> scenarioInitializer
    ) {
        List<PickleMatch> matches = collectMatches(
                buildRunScenarioMaps(inlineArgs, dataTable),
                featuresPath,
                matchType,
                false
        );
        validateMatchCount(
                matches.stream().map(match -> match.pickle().getName()).toList(),
                "S".equals(pluralFlag),
                singularStepText,
                matchType
        );
        appendMatches(topStep, matches, scenarioInitializer);
    }

    static void populateRunScenariosStep(
            StepExtension topStep,
            String pluralFlag,
            String inlineArgs,
            DataTable dataTable,
            RunType runType,
            String matchType,
            String singularStepText,
            BiConsumer<ScenarioStep, Map<String, String>> scenarioInitializer
    ) {
        List<PickleMatch> matches = collectMatches(
                buildRunScenarioMaps(inlineArgs, dataTable),
                runType,
                matchType,
                false
        );
        validateMatchCount(
                matches.stream().map(match -> match.pickle().getName()).toList(),
                "S".equals(pluralFlag),
                singularStepText,
                matchType
        );
        appendMatches(topStep, matches, scenarioInitializer);
    }
    static List<Map<String, String>> buildRunScenarioMaps(
            String inlineArgs,
            DataTable dataTable
    ) {
        return buildRunScenarioMapsFromRows(
                inlineArgs,
                dataTableRows(dataTable)
        );
    }
    static List<Map<String, String>> buildRunScenarioMapsFromRows(
            String inlineArgs,
            List<Map<String, String>> rows
    ) {
        List<Map<String, String>> maps = rows.stream()
                .map(HashMap::new)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        InlineArguments arguments = parseInlineArguments(inlineArgs);
        if (arguments.isEmpty()) {
            return maps;
        }
        if (maps.isEmpty()) {
            maps.add(new HashMap<>());
        }
        maps.forEach(map -> {
            if (arguments.tags() != null) {
                addInlineTags(map, arguments.tags());
            }
            if (arguments.featureName() != null) {
                map.put(PKB_FEATURE_NAME, arguments.featureName());
            }
            if (arguments.scenarioName() != null) {
                map.put(PKB_NAME, exactNameRegex(arguments.scenarioName()));
            }
            if (arguments.stepMarker() != null) {
                map.put(STEP_MARKER, arguments.stepMarker());
            }
        });
        return maps;
    }
    static List<Map<String, String>> dataTableRows(DataTable dataTable) {
        if (dataTable == null) {
            return new ArrayList<>();
        }
        List<List<String>> cells = dataTable.cells();
        if (cells.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> headers = cells.getFirst();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < cells.size(); rowIndex++) {
            List<String> values = cells.get(rowIndex);
            Map<String, String> row = new HashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                String value = column < values.size()
                        ? values.get(column)
                        : "";
                row.put(headers.get(column), value == null ? "" : value);
            }
            rows.add(row);
        }
        return rows;
    }
    public static void filterAndParsePickles(
            StepExtension topStep,
            List<Map<String, String>> maps
    ) {
        appendMatches(
                topStep,
                collectMatches(maps, (String) null, null, false),
                null
        );
    }
    private static List<PickleMatch> collectMatches(
            List<Map<String, String>> maps,
            String featuresPath,
            String matchType,
            boolean requireSinglePerMap
    ) {
        return collectMatches(
                maps,
                featuresPath,
                null,
                matchType,
                requireSinglePerMap
        );
    }

    private static List<PickleMatch> collectMatches(
            List<Map<String, String>> maps,
            RunType runType,
            String matchType,
            boolean requireSinglePerMap
    ) {
        return collectMatches(
                maps,
                null,
                runType,
                matchType,
                requireSinglePerMap
        );
    }

    private static List<PickleMatch> collectMatches(
            List<Map<String, String>> maps,
            String featuresPath,
            RunType runType,
            String matchType,
            boolean requireSinglePerMap
    ) {
        List<PickleMatch> matches = new ArrayList<>();
        for (Map<String, String> map : maps) {
            Map<String, String> scanOptions = new HashMap<>(map);
            scanOptions.remove(STEP_MARKER);
            scanOptions.remove(RUN_KEY);
            removeBlankPathOption(scanOptions, PKB_FEATURES);
            removeBlankPathOption(scanOptions, CUCUMBER_FEATURES);

            if (runType == null) {
                applyFeaturesPathOverride(scanOptions, featuresPath);
            } else {
                applyRunTypePathOverride(scanOptions, map, runType);
            }

            List<Pickle> pickles;
            try {
                pickles = CucumberScanUtil.listPickles(scanOptions);
            } catch (IllegalArgumentException exception) {
                if (matchType == null || !isNoMatchException(exception)) {
                    throw exception;
                }
                throw new IllegalArgumentException(
                        "No " + matchType + " matched the provided scenario filters. "
                                + exception.getMessage(),
                        exception
                );
            }
            if (requireSinglePerMap && pickles.size() > 1) {
                throw new IllegalArgumentException(
                        "Expected one " + matchType + " per filter row, but matched "
                                + pickles.size() + ": "
                                + pickles.stream().map(Pickle::getName).toList()
                );
            }
            pickles.forEach(pickle -> matches.add(new PickleMatch(pickle, map)));
        }

        return matches;
    }

    private static void applyRunTypePathOverride(
            Map<String, String> scanOptions,
            Map<String, String> passedValues,
            RunType runType
    ) {
        scanOptions.remove(PKB_CALL_PATH);
        scanOptions.remove(PKB_COMPONENT_PATH);
        if (runType.pathProperty() == null) {
            return;
        }

        String rowPath = normalize(passedValues.get(runType.pathProperty()));
        String configuredPath = rowPath.isBlank()
                ? configuredRunPath(runType)
                : rowPath;
        applyFeaturesPathOverride(scanOptions, configuredPath);
    }

    private static String configuredRunPath(RunType runType) {
        Object configured = resolveFromVars(runType.pathProperty());
        String configuredPath = configured == null
                ? ""
                : normalize(configured.toString());
        return configuredPath.isBlank()
                ? runType.defaultPath()
                : configuredPath;
    }

    private static void removeBlankPathOption(
            Map<String, String> scanOptions,
            String property
    ) {
        if (scanOptions.containsKey(property)
                && normalize(scanOptions.get(property)).isBlank()) {
            scanOptions.remove(property);
        }
    }

    static void applyFeaturesPathOverride(
            Map<String, String> scanOptions,
            String featuresPath
    ) {
        if (featuresPath == null || featuresPath.isBlank()) {
            return;
        }
        scanOptions.remove(PKB_FEATURES);
        scanOptions.put(CUCUMBER_FEATURES, featuresPath);
    }
    static void validateMatchCount(
            List<String> matchNames,
            boolean allowMultiple,
            String singularStepText,
            String matchType
    ) {
        if (allowMultiple || matchNames.size() <= 1) {
            return;
        }
        throw new IllegalArgumentException(
                singularStepText + " matched " + matchNames.size() + " "
                        + matchType + "s"
                        + " after ordering and limit were applied: "
                        + matchNames
                        + ". Use " + singularStepText + "S to allow multiple matches."
        );
    }
    private static void appendMatches(
            StepExtension topStep,
            List<PickleMatch> matches,
            BiConsumer<ScenarioStep, Map<String, String>> scenarioInitializer
    ) {
        StepExtension lastLinkedStep = null;
        for (PickleMatch match : matches) {
            ScenarioStep scenarioStep = prepareScenarioStep(
                    match,
                    scenarioInitializer
            );
            lastLinkedStep = appendChild(
                    topStep,
                    lastLinkedStep,
                    scenarioStep
            );
        }
    }
    private static ScenarioStep prepareScenarioStep(
            PickleMatch match,
            BiConsumer<ScenarioStep, Map<String, String>> scenarioInitializer
    ) {
        ParsingMap scenarioStepParsingMap = new ParsingMap();
        NodeMap passedMap = new NodeMap(MapConfigurations.MapType.PASSED_MAP);
        passedMap.merge(match.passedValues());
        scenarioStepParsingMap.addMaps(passedMap);
        io.cucumber.messages.types.Pickle pickle =
                (io.cucumber.messages.types.Pickle) getProperty(
                        match.pickle(),
                        "pickle"
                );
        if (pickle.getValueRow() != null && !pickle.getValueRow().isEmpty()) {
            NodeMap examples = new NodeMap(MapConfigurations.MapType.EXAMPLE_MAP);
            examples.merge(pickle.getHeaderRow(), pickle.getValueRow());
            scenarioStepParsingMap.addMaps(examples);
        }
        ScenarioStep scenarioStep = createScenarioStep(
                match.pickle(),
                scenarioStepParsingMap,
                match.passedValues().get(STEP_MARKER)
        );
        scenarioStep.setStepParsingMap(ParsingMap.getRunningParsingMap());
        if (scenarioInitializer != null) {
            scenarioInitializer.accept(scenarioStep, match.passedValues());
        }
        return scenarioStep;
    }
    private static void validateDataMatchCount(List<PickleMatch> matches) {
        if (matches.size() <= 1) {
            return;
        }
        throw new IllegalArgumentException(
                "Scenario data lookup matched " + matches.size()
                        + " component scenarios after ordering and limit were applied: "
                        + matches.stream()
                        .map(match -> match.pickle().getName())
                        .toList()
                        + ". Refine the filters to return exactly one scenario."
        );
    }
    private static boolean isNoMatchException(IllegalArgumentException exception) {
        return exception.getMessage() != null
                && exception.getMessage().startsWith(
                "No scenarios matched the provided filters:"
        );
    }
    private static void addInlineTags(
            Map<String, String> map,
            String inlineArgs
    ) {
        map.put(
                RUN_TAGS,
                (inlineArgs + " "
                        + map.getOrDefault(RUN_TAGS, map.getOrDefault(TAGS, ""))).trim()
        );
    }
    static InlineArguments parseInlineArguments(String inlineArgs) {
        String arguments = normalize(inlineArgs);
        if (arguments.isBlank()) {
            return InlineArguments.EMPTY;
        }
        Matcher matcher = INLINE_ARGUMENT_PATTERN.matcher(arguments);
        List<InlinePart> parts = new ArrayList<>();
        while (matcher.find()) {
            parts.add(new InlinePart(
                    matcher.group(1).toUpperCase(Locale.ROOT),
                    matcher.start(),
                    matcher.end()
            ));
        }
        if (parts.isEmpty()) {
            if (isTagSelector(arguments)) {
                return new InlineArguments(arguments, null, null, null);
            }
            throw invalidInlineArguments(arguments);
        }
        String leadingText = arguments.substring(0, parts.getFirst().start()).trim();
        String tags = null;
        if (!leadingText.isBlank()) {
            if (!isTagSelector(leadingText)) {
                throw invalidInlineArguments(arguments);
            }
            tags = leadingText;
        }
        String featureName = null;
        String scenarioName = null;
        String stepMarker = null;
        for (int index = 0; index < parts.size(); index++) {
            InlinePart part = parts.get(index);
            int valueEnd = index + 1 < parts.size()
                    ? parts.get(index + 1).start()
                    : arguments.length();
            String value = arguments.substring(part.end(), valueEnd).trim();
            if (value.isBlank()) {
                throw new IllegalArgumentException(
                        part.name() + ": requires a nonblank value in inline arguments: ["
                                + arguments + "]"
                );
            }
            switch (part.name()) {
                case "FEATURE" -> featureName = uniqueInlineValue(
                        "FEATURE",
                        featureName,
                        value,
                        arguments
                );
                case "SCENARIO" -> scenarioName = uniqueInlineValue(
                        "SCENARIO",
                        scenarioName,
                        value,
                        arguments
                );
                case "START" -> stepMarker = uniqueInlineValue(
                        "START",
                        stepMarker,
                        value,
                        arguments
                );
                default -> throw invalidInlineArguments(arguments);
            }
        }
        return new InlineArguments(tags, featureName, scenarioName, stepMarker);
    }
    private static String uniqueInlineValue(
            String argumentName,
            String existingValue,
            String newValue,
            String inlineArgs
    ) {
        if (existingValue != null) {
            throw new IllegalArgumentException(
                    argumentName + ": may only be supplied once in inline arguments: ["
                            + inlineArgs + "]"
            );
        }
        return newValue;
    }
    private static IllegalArgumentException invalidInlineArguments(String inlineArgs) {
        return new IllegalArgumentException(
                "Inline arguments must start with @ or % for a tag expression, "
                        + "or use FEATURE:, SCENARIO:, and START: labels: ["
                        + inlineArgs + "]"
        );
    }
    private static boolean isTagSelector(String inlineArgs) {
        return inlineArgs.startsWith("@") || inlineArgs.startsWith("%");
    }
    private static String exactNameRegex(String scenarioName) {
        return "^" + Pattern.quote(scenarioName) + "$";
    }
    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
    private static StepExtension appendChild(
            StepExtension parent,
            StepExtension previous,
            StepExtension child
    ) {
        child.parentStep = parent;
        child.previousSibling = previous;
        child.nextSibling = null;
        parent.childSteps.add(child);
        if (previous != null) {
            previous.nextSibling = child;
        }
        return child;
    }
    enum RunType {
        SCENARIO("SCENARIO", null, null, "scenario", "SCENARIO"),
        COMPONENT_SCENARIO(
                "COMPONENT SCENARIO",
                PKB_COMPONENT_PATH,
                DEFAULT_COMPONENT_PATH,
                "component scenario",
                "COMPONENT"
        ),
        SERVICE_CALL(
                "SERVICE CALL",
                PKB_CALL_PATH,
                DEFAULT_CALLS_PATH,
                "service-call scenario",
                "CALL"
        );

        private final String stepText;
        private final String pathProperty;
        private final String defaultPath;
        private final String matchType;
        private final String convenienceStepText;

        RunType(
                String stepText,
                String pathProperty,
                String defaultPath,
                String matchType,
                String convenienceStepText
        ) {
            this.stepText = stepText;
            this.pathProperty = pathProperty;
            this.defaultPath = defaultPath;
            this.matchType = matchType;
            this.convenienceStepText = convenienceStepText;
        }

        static RunType fromStepText(String stepText) {
            String normalized = normalize(stepText).toUpperCase(Locale.ROOT);
            for (RunType type : values()) {
                if (type.stepText.equals(normalized)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unsupported RUN type: " + stepText);
        }

        String stepText() {
            return stepText;
        }

        String pathProperty() {
            return pathProperty;
        }

        String defaultPath() {
            return defaultPath;
        }

        String matchType() {
            return matchType;
        }

        String convenienceStepText() {
            return convenienceStepText;
        }
    }

    record InlineArguments(
            String tags,
            String featureName,
            String scenarioName,
            String stepMarker
    ) {
        private static final InlineArguments EMPTY =
                new InlineArguments(null, null, null, null);
        boolean isEmpty() {
            return tags == null
                    && featureName == null
                    && scenarioName == null
                    && stepMarker == null;
        }
    }
    private record InlinePart(String name, int start, int end) {
    }

    record DataAddress(
            String featureName,
            String scenarioName,
            String stepMarker
    ) {
    }
    private record PickleMatch(
            Pickle pickle,
            Map<String, String> passedValues
    ) {
    }
}
