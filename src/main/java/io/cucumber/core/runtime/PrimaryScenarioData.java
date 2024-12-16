package io.cucumber.core.runtime;

import io.cucumber.core.runner.ScenarioContext;

//import static io.cucumber.core.runtime.GlobalCache.getState;

public class PrimaryScenarioData {

    private ScenarioContext primaryScenario;
    private ScenarioContext currentScenario;


//    public static ScenarioContext getPrimaryScenario() {
//        return getState().primaryScenario;
//    }
//
//    public static void setPrimaryScenario(ScenarioContext primaryScenario) {
//
//        if (getState().primaryScenario != null)
//            throw new RuntimeException("primaryScenario value already set2");
//        getState().primaryScenario = primaryScenario;
//        getState().currentScenario = primaryScenario;
//    }
//
//
//    public static ScenarioContext getCurrentScenario() {
//        return getState().currentScenario;
//    }
//
//    public static void setCurrentScenario(ScenarioContext currentScenario) {
//        getState().currentScenario = currentScenario;
//    }
}
