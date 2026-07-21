package tools.dscode.coredefinitions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.java.en.Given;
import tools.dscode.common.CoreSteps;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.NodeMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static io.cucumber.core.runner.util.TableUtils.toFlatStringMultimap;
import static io.cucumber.core.runner.util.TableUtils.toRowsStringMultimap;
import static tools.dscode.common.mappings.FileAndDataParsing.JSON_MAPPER;
import static tools.dscode.common.mappings.FileAndDataParsing.XML_MAPPER;
import static tools.dscode.common.mappings.FileAndDataParsing.YAML_MAPPER;
import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.MappingProcessor.getDataTableMap;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.mappings.ParsingMap.getClosestScenarioStepAncestorNodeMap;
import static tools.dscode.common.mappings.ParsingMap.getRootScenarioStepNodeMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;
import static tools.dscode.common.variables.RunVars.resolveFromVars;

public class MappingSteps extends CoreSteps {

    @Given("^CLEAR SAVED VALUES(:.*)?$")
    public static void clearRunMap(String keys) {
        String[] keyArray = keys == null || keys.trim().length() == 1
                ? new String[0]
                : java.util.Arrays.stream(keys.substring(1).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        getRunMap().clearValues(keyArray);

        if (keyArray.length == 0) {
            logInfo("Clearing Scenario Run Map");
        } else {
            logInfo(
                    "Clearing Scenario Run Map values: "
                            + String.join(", ", keyArray)
            );
        }
    }

    @Given("^(:?\"(.*)\"\\s+)?DOC STRING$")
    public static void docString(String docStringName, DocString docString) {
        String docStringNameText = docStringName == null || docStringName.isBlank()
                ? ""
                : " " + docStringName;
        logInfo("Setting Doc String" + docStringNameText + ":" + docString);
    }

    @Given("^(:?\"(.*)\"\\s+)?DATA TABLE$")
    public static void dataTable(String tableName, DataTable dataTable) {
        String tableNameText = tableName == null || tableName.isBlank()
                ? ""
                : " " + tableName;
        logInfo("Setting Data Table" + tableNameText + ":" + dataTable);
    }

    @Given("^SET \"(.*)\" DATA TABLE$")
    public static void setDataTable(String tableName, DataTable dataTable) {
        getDataTableMap().put(
                TABLE_KEY,
                Map.of(tableName.trim(), toRowsStringMultimap(dataTable))
        );
        logInfo("Setting Data Table" + tableName + ":" + dataTable);
    }

    // TODO convert to comma steps
    @Given("^FOR EVERY (\".*\" )?DATA ROW IN THE (\".*\" )?DATA TABLE:$")
    public static void forEveryRow(String rowName, String tableName) {
        tableName = tableName == null || tableName.isBlank() ? "" : tableName;
        rowName = rowName == null || rowName.isBlank() ? "" : rowName;
        StepExtension currentStep = getRunningStep();
        StepExtension modifiedStep = currentStep.modifyStepExtension(
                ", in the " + tableName + "Data Table, for every " + rowName + "Data Row:"
        );
        currentStep.addReplacementStep(modifiedStep);
    }

    @Given("^SET (?:(DEFAULT|OVERRIDE|SINGLETON) )?VALUES$")
    public static void setValues(String mapType, DataTable dataTable) {
        NodeMap nodeMap = switch (mapType) {
            case "DEFAULT" -> MappingProcessor.getDefaultsMap();
            case "OVERRIDE" -> MappingProcessor.getOverridesMap();
            case "SINGLETON" -> MappingProcessor.getSingletonMap();
            case null, default -> getRunMap();
        };

        nodeMap.merge(toFlatStringMultimap(dataTable.asLists()));
    }


    @Given("^MAP \"(.*)\" (TEXT|OBJECT) VALUE(?: TO (DEFAULT|OVERRIDE|SINGLETON|STEP|ROOT SCENARIO|SCENARIO|RUN) MAP)?$")
    public static void mapDocString(String key, String dataType, String mapType, DocString docString) {
        NodeMap nodeMap = switch (mapType) {
            case "DEFAULT" -> MappingProcessor.getDefaultsMap();
            case "OVERRIDE" -> MappingProcessor.getOverridesMap();
            case "SINGLETON" -> MappingProcessor.getSingletonMap();
            case "STEP" -> getRunningStep().getDefaultStepNodeMap();
            case "SCENARIO" -> getClosestScenarioStepAncestorNodeMap();
            case "SCENARIO ROOT" -> getRootScenarioStepNodeMap();
            case "RUN" -> getRunMap();
            case null, default -> getRunMap();
        };
        String content = docString.getContent();

        Object value = switch (dataType == null ? "TEXT" : dataType) {
            case "TEXT" -> content;
            case "OBJECT" -> parseData(content, docString.getContentType());
            default -> throw new IllegalArgumentException(
                    "Unsupported map value type: " + dataType
            );
        };
        nodeMap.put(key, value);
    }

    @Given("^MAP (?:\"(.*)\" )?TABLE VALUES(?: TO (DEFAULT|OVERRIDE|SINGLETON|STEP|ROOT SCENARIO|SCENARIO|RUN) MAP)?$")
    public static void mapValues(String prefix, String mapType, DataTable dataTable) {
        NodeMap nodeMap = switch (mapType) {
            case "DEFAULT" -> MappingProcessor.getDefaultsMap();
            case "OVERRIDE" -> MappingProcessor.getOverridesMap();
            case "SINGLETON" -> MappingProcessor.getSingletonMap();
            case "STEP" -> getRunningStep().getDefaultStepNodeMap();
            case "SCENARIO" -> getClosestScenarioStepAncestorNodeMap();
            case "SCENARIO ROOT" -> getRootScenarioStepNodeMap();
            case "RUN" -> getRunMap();
            case null, default -> getRunMap();
        };
        final String keyPrefix = prefix == null || prefix.isBlank() ? "" : prefix;
        dataTable.asLists().forEach(row -> nodeMap.put(keyPrefix + row.getFirst(), row.get(1)));
    }

    static void mapValues(NodeMap destination, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "MAP VALUES requires at least one two-column row"
            );
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);

            if (row == null || row.size() != 2) {
                int actualColumns = row == null ? 0 : row.size();
                throw new IllegalArgumentException(
                        "MAP VALUES row "
                                + (rowIndex + 1)
                                + " must contain exactly two columns but contained "
                                + actualColumns
                );
            }

            String key = normalizeKey(row.get(0), rowIndex);
            destination.put(key, actualValue(row.get(1)));
        }
    }

    static Object actualValue(String rawValue) {
        if (rawValue == null) {
            return null;
        }

        String value = rawValue.trim();
        if (value.isEmpty()) {
            return "";
        }

        try {
            JsonNode parsed = MAPPER.readTree(value);
            return parsed == null ? value : parsed;
        } catch (Exception ignored) {
            return value;
        }
    }

    private static String normalizeKey(String rawKey, int rowIndex) {
        String key = rawKey == null ? "" : rawKey.trim();

        if (key.isBlank()) {
            throw new IllegalArgumentException(
                    "MAP VALUES row "
                            + (rowIndex + 1)
                            + " requires a non-blank destination key"
            );
        }

        return key;
    }

    @Given("(?i)^resolveVar:(.+)$")
    public static Object resolveToVarStepDef(String varName) {
        return resolveFromVars(varName);
    }

    @Given("^ARG$")
    public static Object getArgValue() {
        return getRunningStep().argument.getValue();
    }

    @Given("^PATH:(.*)$")
    public static Object getFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return buildJsonFromPath(path.trim());
    }


    private static JsonNode parseData(
            String content,
            String contentType
    ) {

        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException(
                    "MAP DATA VALUE requires a DocString content type, "
                            + "such as \"\"\"json, \"\"\"xml, or \"\"\"yaml"
            );
        }

        String normalizedType = contentType
                .toLowerCase(Locale.ROOT)
                .trim();

        ObjectMapper mapper = switch (normalizedType) {
            case "json",
                 "application/json",
                 "text/json" -> JSON_MAPPER;

            case "xml",
                 "application/xml",
                 "text/xml" -> XML_MAPPER;

            case "yaml",
                 "yml",
                 "application/yaml",
                 "application/x-yaml",
                 "text/yaml",
                 "text/x-yaml" -> YAML_MAPPER;

            default -> throw new IllegalArgumentException(
                    "Unsupported DocString content type: " + contentType
            );
        };

        try {
            return mapper.readTree(content);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

}
