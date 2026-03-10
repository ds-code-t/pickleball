package tools.dscode.common.mappings;

//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;

public class MapConfigurations {

    public enum DataSource {
        UNDEFINED, CONFIGURATION_FILE, PASSED_TABLE, EXAMPLE_TABLE, DOC_STRING, DATA_ROW, DATA_TABLE, TABLE_ROW;

        public static DataSource fromString(String input) {
            try {
                return valueOf(input.trim().replaceAll("\\s+", "_").toUpperCase(java.util.Locale.ROOT));
            } catch (Exception ex) {
                return UNDEFINED;
            }
        }
    }

    public enum MapType {
        OVERRIDE_MAP,  PASSED_MAP, EXAMPLE_MAP, STEP_MAP, RUN_MAP, PHRASE_MAP,  GLOBAL_NODE, DEFAULT, SINGLETON, DATATABLE, DOCSTRING
    }

    // private static final Map<String, DataSource> dataSourceMap = new
    // ConcurrentHashMap<>();

}
