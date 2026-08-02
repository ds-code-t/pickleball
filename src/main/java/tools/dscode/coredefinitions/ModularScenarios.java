package tools.dscode.coredefinitions;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.ScenarioStep;
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

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.ScenarioStep.createScenarioStep;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.testengine.PKB_props.PKB_FEATURE_NAME;
import static tools.dscode.testengine.PKB_props.PKB_NAME;

public class ModularScenarios extends CoreSteps {
    static final String RUN_TAGS = "Run Tags";
    static final String TAGS = "Tags";
    static final String CUCUMBER_FEATURES = "cucumber.features";
    static final String STEP_MARKER = "Step_Marker";
    private static final Pattern INLINE_ARGUMENT_PATTERN = Pattern.compile(
            "(?i)(?<!\\S)(FEATURE|SCENARIO|START):"
    );

    @Given("^RUN SCENARIO(S)?:?(.*)?$")
    public static void runScenarios(
            String pluralFlag,
            String inlineArgs,
            DataTable dataTable
    ) {
        populateRunScenariosStep(
                getRunningStep(),
                pluralFlag,
                inlineArgs,
                dataTable,
                null,
                "component scenario",
                "RUN SCENARIO",
                null
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
                        null,
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

    static List<Map<String, String>> buildRunScenarioMaps(
            String inlineArgs,
            DataTable dataTable
    ) {
        List<Map<String, String>> maps = dataTable == null
                ? new ArrayList<>()
                : dataTable.asMaps().stream()
                .map(row -> {
                    Map<String, String> copy = new HashMap<>();
                    row.forEach((key, value) -> copy.put(key, value == null ? "" : value));
                    return copy;
                })
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

    public static void filterAndParsePickles(
            StepExtension topStep,
            List<Map<String, String>> maps
    ) {
        appendMatches(
                topStep,
                collectMatches(maps, null, null, false),
                null
        );
    }

    private static List<PickleMatch> collectMatches(
            List<Map<String, String>> maps,
            String featuresPath,
            String matchType,
            boolean requireSinglePerMap
    ) {
        List<PickleMatch> matches = new ArrayList<>();

        for (Map<String, String> map : maps) {
            Map<String, String> scanOptions = new HashMap<>(map);
            scanOptions.remove(STEP_MARKER);
            if (featuresPath != null && !featuresPath.isBlank()) {
                scanOptions.put(CUCUMBER_FEATURES, featuresPath);
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

            if (scenarioInitializer != null) {
                scenarioInitializer.accept(scenarioStep, match.passedValues());
            }

            lastLinkedStep = appendChild(
                    topStep,
                    lastLinkedStep,
                    scenarioStep
            );
        }
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

    private record PickleMatch(
            Pickle pickle,
            Map<String, String> passedValues
    ) {
    }
}
