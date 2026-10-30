package tools.dscode.scenarioobject;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Then;

import java.util.ArrayList;
import java.util.List;

import static tools.dscode.Values.getScenarioObject;
import static tools.dscode.Values.saveScenarioObject;

public final class ScenarioObjectSteps {
    static final String WINDOWS = "C:\\Users\\Dan She'll\\page";
    static final List<Object> beforeTokens = java.util.Collections.synchronizedList(new ArrayList<>());
    static final List<Object> stepTokens = java.util.Collections.synchronizedList(new ArrayList<>());
    static final List<Object> afterTokens = java.util.Collections.synchronizedList(new ArrayList<>());
    static boolean leaked;

    private ScenarioObjectSteps() {
    }

    @Before
    public static void before() {
        if (getScenarioObject("from-step") != null) {
            leaked = true;
        }
        Object token = new Object();
        beforeTokens.add(token);
        saveScenarioObject(WINDOWS, token);
        saveScenarioObject("C:/Program Files/Pickleball/body.xml", "body");
    }

    @Then("the hook object is visible")
    public static void visible() {
        stepTokens.add(getScenarioObject(WINDOWS));
        if (!"body".equals(getScenarioObject("C:/Program Files/Pickleball/body.xml"))) {
            throw new AssertionError("spaced path was not kept");
        }
        saveScenarioObject("from-step", "kept");
    }

    @Then("the previous step object is gone")
    public static void gone() {
        if (getScenarioObject("from-step") != null) {
            leaked = true;
        }
        stepTokens.add(getScenarioObject(WINDOWS));
    }

    @After
    public static void after() {
        afterTokens.add(getScenarioObject(WINDOWS));
    }

    static void reset() {
        beforeTokens.clear();
        stepTokens.clear();
        afterTokens.clear();
        leaked = false;
    }
}
