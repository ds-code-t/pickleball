package tools.dscode.common.mappings;

//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;

public class MapConfigurations {

    public enum DataSource {
        CONFIGURATION_FILE, PASSED_TABLE, EXAMPLE_TABLE, STEP_TABLE, TABLE_ROW
    }
    public enum MapType {
        OVERRIDE_MAP, STEP_MAP, RUN_MAP, GLOBAL_NODE, DEFAULT, SINGLETON
    }

    // private static final Map<String, DataSource> dataSourceMap = new
    // ConcurrentHashMap<>();

}
