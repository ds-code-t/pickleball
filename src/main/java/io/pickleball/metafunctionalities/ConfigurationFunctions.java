package io.pickleball.metafunctionalities;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import static io.pickleball.cacheandstate.GlobalCache.getGlobalConfigs;
import static io.pickleball.cacheandstate.GlobalCache.getTestStateMap;
import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.cacheandstate.ScenarioContext.getRunMaps;
import static io.pickleball.cacheandstate.StepContext.getCurrentFlagList;

public class ConfigurationFunctions {


    public static final String NESTED_CONTEXT = "FOR NESTED STEPS";
    public static final String CURRENT_SCENARIO = "FOR CURRENT SCENARIO";


    public static final String CONTEXT_TYPE = NESTED_CONTEXT + "|" + CURRENT_SCENARIO;

    @Given("^SET (?:\"(.*)\" )?(.*) DATA( " + CONTEXT_TYPE + ")?$")
    public static void useData(String customName, String dataPath, String context, DataTable dataTable) {
        String contextType = context == null ? "" : context.strip();
        LinkedMultiMap contextMap = switch (contextType) {
            case NESTED_CONTEXT -> getCurrentStep().getStepMap();
            case CURRENT_SCENARIO -> getCurrentScenarioStateMap();
            default -> getTestStateMap();
        };

//        String configKeyName = (customName == null || customName.isBlank()) ? dataPath : customName;
//
        System.out.println("@@dataPath: " + dataPath);
        System.out.println("@@customName: " + customName);
        if (customName == null || customName.isBlank()) {
            contextMap.parseDirectoriesContents("data/" + dataPath + ".yaml");
            System.out.println("@@ChromeDriver: " +    getRunMaps().get("ChromeDriver"));
            System.out.println("@@ChromeDriver.ChromeDriverService: " + getRunMaps().get("ChromeDriver.ChromeOptions.setLoggingPrefs"));
        }
        else {
            LinkedMultiMap map = new LinkedMultiMap();
            contextMap.put(customName.trim(), map);
        }


    }


}
