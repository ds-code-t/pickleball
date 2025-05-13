package io.pickleball.metafunctionalities;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.pickleball.mapandStateutilities.LinkedMultiMap;

import static io.pickleball.cacheandstate.GlobalCache.getGlobalConfigs;
import static io.pickleball.cacheandstate.PrimaryScenarioData.*;
import static io.pickleball.cacheandstate.ScenarioContext.getRunMaps;
import static io.pickleball.cacheandstate.StepContext.getCurrentFlagList;

public class ConfigurationFunctions {



    public static final String NESTED_CONTEXT = "FOR NESTED STEPS";
    public static final String CURRENT_SCENARIO = "FOR CURRENT SCENARIO";


    public static final String CONTEXT_TYPE = NESTED_CONTEXT + "|" + CURRENT_SCENARIO;

    @Given("^SET (?:\"(.*)\" )?(.*) DATA( " + CONTEXT_TYPE + ")?$")
    public static void useData(String BrowserName, String driverConfigName, String context, DataTable dataTable) {
        String contextType = context == null ? "" : context.strip();
        LinkedMultiMap<?, ?> contextMap = switch (contextType) {
            case NESTED_CONTEXT -> getCurrentStep().getStepMap();
            case CURRENT_SCENARIO -> getCurrentScenario().getStateMap();
            default -> getPrimaryScenarioStateMap();
        };

//        LinkedMultiMap<?, ?> contextMap = contextType.isEmpty() ? getPrimaryScenarioStateMap() : (contextTypeequals()getCurrentStep().getStepMap();
        String configKeyName = (BrowserName == null || BrowserName.isBlank()) ? driverConfigName : BrowserName;
        contextMap.putConfig(configKeyName, getGlobalConfigs().get("configs.driverconfigs." + driverConfigName));
        System.out.println("@@@@getGlobalConfigs().get(\"configs.driverconfigs.\" + driverConfigName) : " + getGlobalConfigs().get("configs.driverconfigs." + driverConfigName));
        System.out.println("@@@@configKeyName : " + configKeyName);
        System.out.println("@@@@driverConfigName : " + driverConfigName);


        System.out.println("@@getCurrentFlagList() : " + getCurrentFlagList());
        System.out.println("@@getCurrentFlagList() : " + getCurrentFlagList().contains("@NestedContext"));
        System.out.println("@@BrowserName: " + BrowserName);
        System.out.println("@@driverConfigName: " + driverConfigName);
        System.out.println("@@dataTable: " + dataTable);
//        getRunMaps().addMapsWithKey(key, dataTable.asLinkedMultiMaps());
    }
//
//    public static final String NESTED_CONTEXT = "FOR NESTED STEPS";
//
//    @Given("^SET (?:\"(.*)\" )?(.*) Browser( " + NESTED_CONTEXT + ")?$")
//    public static void webdriverConfiguration(String BrowserName, String driverConfigName, String context, DataTable dataTable) {
//
//        LinkedMultiMap<?, ?> contextMap = (context == null || context.isBlank()) ? getPrimaryScenarioStateMap() : getCurrentStep().getStepMap();
//        String configKeyName = (BrowserName == null || BrowserName.isBlank()) ? driverConfigName : BrowserName;
//        contextMap.putConfig(configKeyName, getGlobalConfigs().get("configs.driverconfigs." + driverConfigName));
//        System.out.println("@@@@getGlobalConfigs().get(\"configs.driverconfigs.\" + driverConfigName) : " + getGlobalConfigs().get("configs.driverconfigs." + driverConfigName));
//        System.out.println("@@@@configKeyName : " + configKeyName);
//        System.out.println("@@@@driverConfigName : " + driverConfigName);
//
//
//        System.out.println("@@getCurrentFlagList() : " + getCurrentFlagList());
//        System.out.println("@@getCurrentFlagList() : " + getCurrentFlagList().contains("@NestedContext"));
//        System.out.println("@@BrowserName: " + BrowserName);
//        System.out.println("@@driverConfigName: " + driverConfigName);
//        System.out.println("@@dataTable: " + dataTable);
////        getRunMaps().addMapsWithKey(key, dataTable.asLinkedMultiMaps());
//    }

}
