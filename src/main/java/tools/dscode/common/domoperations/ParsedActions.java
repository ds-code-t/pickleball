package tools.dscode.common.domoperations;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.parsedComponents.ValueMatch;


import java.util.ArrayList;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.HumanInteractions.clearAndType;
import static tools.dscode.common.domoperations.HumanInteractions.click;
import static tools.dscode.common.domoperations.HumanInteractions.contextClick;
import static tools.dscode.common.domoperations.HumanInteractions.doubleClick;
import static tools.dscode.common.domoperations.HumanInteractions.selectDropdownByVisibleText;
import static tools.dscode.common.domoperations.HumanInteractions.typeText;
import static tools.dscode.common.domoperations.HumanInteractions.wheelScrollBy;
import static tools.dscode.common.domoperations.SeleniumUtils.waitSeconds;
import static tools.dscode.common.util.DebugUtils.printDebug;


public class ParsedActions {


    public static void executeAction(WebDriver driver, PhraseData phraseData) {

        MatchNode actionNode = phraseData.phraseNode.getChild("action");
        String action = phraseData.action;

        List<ElementMatch> nextElementMatches = phraseData.getNextComponents(actionNode.position, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
        ElementMatch nextElementMatch = nextElementMatches.isEmpty() ? null : nextElementMatches.getFirst();

        List<WebElement> nextElements = nextElementMatch == null || nextElementMatch.wrappedElements.isEmpty() ?
                new ArrayList<>() : nextElementMatch.wrappedElements.stream().map(e -> e.getElement()).toList();
        ValueMatch nextValue = (ValueMatch) phraseData.getNextComponents(actionNode.position, "valueMatch").stream().findFirst().orElse(null);

        if (!action.equals("wait") && (nextElementMatches.isEmpty() || (!phraseData.elementMatch.selectionType.equals("any") && nextElements.isEmpty()))) {
            String message = "No elements found for " + action;
            if (nextElementMatch != null && nextElementMatch.xPathy != null)
                message += " at " + nextElementMatch.xPathy.getXpath();
            throw new RuntimeException(message);
        }

        System.out.println("Attempting " + action);
        switch (action) {
            case String s when s.contains("save") -> {
                String keyName = phraseData.keyName;

                for (ElementWrapper nextElement : phraseData.wrappedElements) {
                    if (keyName.isBlank())
                        keyName = nextElement.elementMatch.category;
                    printDebug("##Actions: saving '" + nextElement.getElementReturnValue() + "' to key: " + keyName);
                    getRunningStep().getStepParsingMap().put(keyName, nextElement.getElementReturnValue());
                }
            }
            case String s when s.contains("wait") -> {
                waitSeconds(Integer.parseInt(nextValue.value.replace("\"|'|`", "")));
            }
            case String s when s.contains("select") -> {
                for (WebElement nextElement : nextElements) {
                    selectDropdownByVisibleText(driver, nextElement, nextValue.value);
                }
            }
            case String s when s.contains("click") -> {
                for (WebElement nextElement : nextElements) {
                    click(driver, nextElement);
                }
            }
            case String s when s.contains("double click") -> {
                for (WebElement nextElement : nextElements) {
                    doubleClick(driver, nextElement);
                }
            }
            case String s when s.contains("right click") -> {
                for (WebElement nextElement : nextElements) {
                    contextClick(driver, nextElement);
                }
            }
            case String s when s.contains("enter") -> {
                for (WebElement nextElement : nextElements) {
                    typeText(driver, nextElement, nextValue.value);
                }
            }
            case String s when s.contains("scroll") -> {
                for (WebElement nextElement : nextElements) {
                    wheelScrollBy(driver, nextElement);
                }
            }
            case String s when s.contains("overwrite") -> {
                for (WebElement nextElement : nextElements) {
                    clearAndType(driver, nextElement, nextValue.value);
                }
            }
            default -> {
            }
        }
    }


}
