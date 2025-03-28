package io.pickleball.metafunctionalities;

import io.cucumber.java.en.Given;
import io.pickleball.annotations.NoEventEmission;
import io.pickleball.cacheandstate.ScenarioContext;

import static io.cucumber.core.gherkin.messages.GherkinMessagesStep.BOOKMARKS;
import static io.pickleball.cacheandstate.PrimaryScenarioData.*;

public class ScenarioNavigation {

//    @NoEventEmission
//    @Given("^@MetaStepDefinition$")
//    public static void MetaStepDefinition() {
//        // Placeholder for when steps don't have text
//    }
//

    @NoEventEmission
    @Given("^go to: (?:\"(.*)\"|(&.*))$")
    public static void goTo(String regex, String bookmarksString) {
        if (bookmarksString != null)
            getCurrentScenario().setGoToBookmark(bookmarksString, ScenarioContext.RunStatus.FIND_ANY);
        else
            getCurrentScenario().setGoToRegex(regex, ScenarioContext.RunStatus.FIND_ANY);
    }

    @NoEventEmission
    @Given("^go to next: \"(.*)\"$")
    public static void goToNext(String regex, String bookmarksString) {
        if (bookmarksString != null)
            getCurrentScenario().setGoToBookmark(bookmarksString, ScenarioContext.RunStatus.FIND_NEXT);
        else
            getCurrentScenario().setGoToRegex(regex, ScenarioContext.RunStatus.FIND_NEXT);
    }

    @NoEventEmission
    @Given("^go to previous: (?:\"(.*)\"|(&.*))$")
    public static void goToPrevious(String regex, String bookmarksString) {
        if (bookmarksString != null)
            getCurrentScenario().setGoToBookmark(bookmarksString, ScenarioContext.RunStatus.FIND_PREVIOUS);
        else
            getCurrentScenario().setGoToRegex(regex, ScenarioContext.RunStatus.FIND_PREVIOUS);
    }

    @NoEventEmission
    @Given("^go to first: \"(.*)\"$")
    public static void goToFirst(String regex, String bookmarksString) {
        if (bookmarksString != null)
            getCurrentScenario().setGoToBookmark(bookmarksString, ScenarioContext.RunStatus.FIND_FIRST);
        else
            getCurrentScenario().setGoToRegex(regex, ScenarioContext.RunStatus.FIND_FIRST);
    }

    @NoEventEmission
    @Given("^go to last: \"(.*)\"$")
    public static void goToLast(String regex, String bookmarksString) {
        if (bookmarksString != null)
            getCurrentScenario().setGoToBookmark(bookmarksString, ScenarioContext.RunStatus.FIND_LAST);
        else
            getCurrentScenario().setGoToRegex(regex, ScenarioContext.RunStatus.FIND_LAST);
    }

//    @NoEventEmission
//    @Given("^(?:(&.*)(?: &.*)*)$")
//    public static void anchor(String anchors) {
//        System.out.println("@@anchors: " + anchors);
//    }

//    @Given("^ANCHORS:"+ BOOKMARKS + "(.*)$")

    @NoEventEmission
    @Given("^"+  BOOKMARKS + "(.*)$")
//    @Given("^ANCHORS:(.*)$")
    public static void anchor(String anchors) {
//        System.out.println("Bookmarks: " + anchors);
    }

}
