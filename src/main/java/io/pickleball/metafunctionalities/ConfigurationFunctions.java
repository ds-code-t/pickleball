package io.pickleball.metafunctionalities;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import static io.pickleball.cacheandstate.GlobalCache.getTestStateMap;
import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.datafunctions.FileAndDataParsing.getBaseFileName;

public class ConfigurationFunctions {


    public static final String NESTED_CONTEXT = "FOR NESTED STEPS";
    public static final String CURRENT_SCENARIO = "FOR CURRENT SCENARIO";


    public static final String CONTEXT_TYPE = NESTED_CONTEXT + "|" + CURRENT_SCENARIO;

    @Given("^SET (?:\"(.*)\" )?(.*) DATA( " + CONTEXT_TYPE + ")?$")
    public static void useData(String customName, String dataPath, String context, DataTable dataTable) {
        String contextType = context == null ? "" : context.strip();
        System.out.println("@@contextType: " + contextType);

        LinkedMultiMap contextMap = switch (contextType) {
            case NESTED_CONTEXT -> getCurrentStep().getInheritedStepMap();
            case CURRENT_SCENARIO -> getCurrentScenarioStateMap();
            default -> getTestStateMap();
        };

        String configKeyName = (customName == null || customName.isBlank()) ? getBaseFileName(dataPath) : customName;
//
        System.out.println("@@dataPath: " + dataPath);
        System.out.println("@@customName: " + configKeyName);
        contextMap.addFromFile(configKeyName, "data/" + dataPath + ".yaml");
        System.out.println("@@contextMap: " + contextMap);
//        if (customName == null || customName.isBlank()) {
//            contextMap.createMapFromPath("data/" + dataPath + ".yaml");
//            System.out.println("@@contextMap: " +    contextMap);
//            System.out.println("@@ChromeDriver: " +    getRunMaps().get("ChromeDriver"));
//            System.out.println("@@ChromeDriver.ChromeDriverService: " + getRunMaps().get("ChromeDriver.ChromeOptions.setLoggingPrefs"));
//        }
//        else {
//            LinkedMultiMap map = new LinkedMultiMap();
//            contextMap.put(customName.trim(), map);
//        }


    }


}
