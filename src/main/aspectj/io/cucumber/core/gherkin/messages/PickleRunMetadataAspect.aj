package io.cucumber.core.gherkin.messages;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.util.CucumberQueryUtil;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.TableRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public aspect PickleRunMetadataAspect {

    private static final Pattern DESCRIPTION_ENTRY =
            Pattern.compile("^\\s*-\\s*([A-Z][A-Z0-9_-]*)\\s*:(.*)$");

    private Map<String, Object> GherkinMessagesPickle.descriptionMap =
            new LinkedHashMap<String, Object>();

    private boolean GherkinMessagesPickle.shouldRun = true;

    public abstract Map<String, Object> Pickle.getDescriptionMap();

    public abstract void Pickle.setDescriptionMap(Map<String, Object> descriptionMap);

    public abstract boolean Pickle.isShouldRun();

    public abstract void Pickle.setShouldRun(boolean shouldRun);

    public Map<String, Object> GherkinMessagesPickle.getDescriptionMap() {
        return descriptionMap;
    }

    public void GherkinMessagesPickle.setDescriptionMap(Map<String, Object> descriptionMap) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (descriptionMap != null && !descriptionMap.isEmpty()) {
            copy.putAll(descriptionMap);
        }
        this.descriptionMap = copy;
    }

    public boolean GherkinMessagesPickle.isShouldRun() {
        return shouldRun;
    }

    public void GherkinMessagesPickle.setShouldRun(boolean shouldRun) {
        this.shouldRun = shouldRun;
    }

    pointcut gherkinMessagesPickleConstruction(GherkinMessagesPickle pickle):
            execution(io.cucumber.core.gherkin.messages.GherkinMessagesPickle.new(..))
                    && this(pickle);

    after(GherkinMessagesPickle pickle) returning()
            : gherkinMessagesPickleConstruction(pickle) {
        initializeRunMetadata(pickle);
    }

    private static void initializeRunMetadata(GherkinMessagesPickle pickle) {
        CucumberQueryUtil.GherkinView view = CucumberQueryUtil.describe(pickle);
        Map<String, Object> descriptionMap = parseDescriptionMap(descriptionOf(view));
        pickle.setDescriptionMap(descriptionMap);
        pickle.setShouldRun(shouldRunForExamples(descriptionMap, view));
    }

    private static Map<String, Object> parseDescriptionMap(String description) {
        Map<String, Object> entries = new LinkedHashMap<>();
        if (description == null || description.isBlank()) {
            return entries;
        }

        for (String rawLine : description.split("\\R")) {
            Matcher matcher = DESCRIPTION_ENTRY.matcher(rawLine);
            if (matcher.matches()) {
                entries.put(matcher.group(1).strip(), matcher.group(2).strip());
            }
        }
        return entries;
    }

    private static String descriptionOf(CucumberQueryUtil.GherkinView view) {
        if (view == null) {
            return "";
        }
        String description = view.pickle == null ? "" : view.pickle.getDescription();
        if ((description == null || description.isBlank()) && view.scenario != null) {
            description = view.scenario.getDescription();
        }
        return description == null ? "" : description;
    }

    private static boolean shouldRunForExamples(Map<String, Object> descriptionMap, CucumberQueryUtil.GherkinView view) {
        if (descriptionMap == null || !descriptionMap.containsKey("EXAMPLES")) {
            return true;
        }
        if (view == null || !view.scenarioOutline) {
            return true;
        }

        ExamplePosition current = currentExamplePosition(view);
        String rawExamples = Objects.toString(descriptionMap.get("EXAMPLES"), "").strip();
        if (rawExamples.isEmpty()) {
            throw new IllegalArgumentException("Invalid EXAMPLES selector for scenario '"
                    + scenarioName(view) + "': value is blank.");
        }

        String[] selectors = rawExamples.split(",", -1);
        boolean matched = false;
        for (String selector : selectors) {
            String token = selector.strip();
            if (token.isEmpty()) {
                throw invalidExamplesSelector(view, rawExamples, "empty selector between commas");
            }
            matched |= selectorMatches(token, current, view, rawExamples);
        }
        return matched;
    }

    private static boolean selectorMatches(
            String token,
            ExamplePosition current,
            CucumberQueryUtil.GherkinView view,
            String rawExamples
    ) {
        int dot = token.indexOf('.');
        if (dot >= 0) {
            if (dot != token.lastIndexOf('.')) {
                throw invalidExamplesSelector(view, rawExamples,
                        "'" + token + "' contains more than one period");
            }
            int tableNumber = parsePositiveInt(token.substring(0, dot), view, rawExamples, token);
            int rowNumber = parsePositiveInt(token.substring(dot + 1), view, rawExamples, token);
            validateTableRowSelector(tableNumber, rowNumber, view, rawExamples, token);
            return current.tableNumber == tableNumber && current.rowNumber == rowNumber;
        }

        int globalNumber = parsePositiveInt(token, view, rawExamples, token);
        int totalExamples = totalExampleRows(view.scenario);
        if (globalNumber > totalExamples) {
            throw invalidExamplesSelector(view, rawExamples,
                    "'" + token + "' is outside the valid global example range 1-" + totalExamples);
        }
        return current.globalNumber == globalNumber;
    }

    private static ExamplePosition currentExamplePosition(CucumberQueryUtil.GherkinView view) {
        Scenario scenario = view.scenario;
        TableRow currentRow = view.exampleRow;
        if (scenario == null || currentRow == null) {
            throw new IllegalStateException("Unable to resolve current example row for scenario '"
                    + scenarioName(view) + "'.");
        }

        int globalNumber = 0;
        List<Examples> examplesList = scenario.getExamples();
        for (int tableIndex = 0; tableIndex < examplesList.size(); tableIndex++) {
            Examples examples = examplesList.get(tableIndex);
            List<TableRow> rows = examples.getTableBody();
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                globalNumber++;
                TableRow row = rows.get(rowIndex);
                if (Objects.equals(row.getId(), currentRow.getId())) {
                    return new ExamplePosition(globalNumber, tableIndex + 1, rowIndex + 1);
                }
            }
        }

        throw new IllegalStateException("Unable to locate current example row id '"
                + currentRow.getId() + "' in scenario '" + scenarioName(view) + "'.");
    }

    private static void validateTableRowSelector(
            int tableNumber,
            int rowNumber,
            CucumberQueryUtil.GherkinView view,
            String rawExamples,
            String token
    ) {
        List<Examples> examplesList = view.scenario.getExamples();
        if (tableNumber > examplesList.size()) {
            throw invalidExamplesSelector(view, rawExamples,
                    "'" + token + "' references example table " + tableNumber
                            + ", but only " + examplesList.size() + " table(s) exist");
        }
        int rowsInTable = examplesList.get(tableNumber - 1).getTableBody().size();
        if (rowNumber > rowsInTable) {
            throw invalidExamplesSelector(view, rawExamples,
                    "'" + token + "' references row " + rowNumber + " in example table "
                            + tableNumber + ", but that table only has " + rowsInTable + " row(s)");
        }
    }

    private static int parsePositiveInt(
            String raw,
            CucumberQueryUtil.GherkinView view,
            String rawExamples,
            String token
    ) {
        String value = raw.strip();
        if (value.isEmpty()) {
            throw invalidExamplesSelector(view, rawExamples,
                    "'" + token + "' has a missing example number");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw invalidExamplesSelector(view, rawExamples,
                        "'" + token + "' must use positive example numbers");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw invalidExamplesSelector(view, rawExamples,
                    "'" + token + "' is not a valid example number");
        }
    }

    private static int totalExampleRows(Scenario scenario) {
        if (scenario == null) {
            return 0;
        }
        int total = 0;
        for (Examples examples : scenario.getExamples()) {
            total += examples.getTableBody().size();
        }
        return total;
    }

    private static IllegalArgumentException invalidExamplesSelector(
            CucumberQueryUtil.GherkinView view,
            String rawExamples,
            String reason
    ) {
        return new IllegalArgumentException("Invalid EXAMPLES selector '" + rawExamples
                + "' for scenario '" + scenarioName(view) + "': " + reason + ".");
    }

    private static String scenarioName(CucumberQueryUtil.GherkinView view) {
        return view == null || view.scenario == null ? "<unknown>" : view.scenario.getName();
    }

    private static final class ExamplePosition {
        final int globalNumber;
        final int tableNumber;
        final int rowNumber;

        ExamplePosition(int globalNumber, int tableNumber, int rowNumber) {
            this.globalNumber = globalNumber;
            this.tableNumber = tableNumber;
            this.rowNumber = rowNumber;
        }
    }
}
