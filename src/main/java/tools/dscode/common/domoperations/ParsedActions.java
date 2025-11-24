package tools.dscode.common.domoperations;

import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.PhraseExecution;
import tools.dscode.common.treeparsing.PhraseExecution.ElementMatch;
import tools.dscode.common.treeparsing.PhraseExecution.ValueMatch;

import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.domoperations.HumanInteractions.clearAndType;
import static tools.dscode.common.domoperations.HumanInteractions.click;
import static tools.dscode.common.domoperations.HumanInteractions.contextClick;
import static tools.dscode.common.domoperations.HumanInteractions.doubleClick;
import static tools.dscode.common.domoperations.HumanInteractions.selectDropdownByVisibleText;
import static tools.dscode.common.domoperations.HumanInteractions.typeText;
import static tools.dscode.common.domoperations.HumanInteractions.wheelScrollBy;
import static tools.dscode.common.domoperations.SeleniumUtils.waitSeconds;


public class ParsedActions {


    public static void executeAction(ChromiumDriver driver, PhraseExecution phraseExecution) {

        MatchNode actionNode = phraseExecution.phraseNode.getChild("action");
        String action = phraseExecution.action;

        List<ElementMatch> nextElementMatches = phraseExecution.getNextComponents(actionNode.position, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
        ElementMatch nextElementMatch = nextElementMatches .isEmpty() ? null : nextElementMatches.getFirst();

        List<WrappedWebElement> nextElements = nextElementMatch == null || nextElementMatch.matchedElements == null ?
                new ArrayList<>() : nextElementMatch.matchedElements.getWrappers();
        ValueMatch nextValue = (ValueMatch) phraseExecution.getNextComponents(actionNode.position, "valueMatch").stream().findFirst().orElse(null);

        if (!action.equals("wait") && (nextElementMatches.isEmpty() || nextElements.isEmpty())) {
            String message = "No elements found for " + action;
            if (nextElementMatch != null && nextElementMatch.xPathy != null)
                message += " at " + nextElementMatch.xPathy.getXpath();
            throw new RuntimeException(message);
        }

        System.out.println("Attempting " + action);
        switch (action) {
            case String s when s.contains("select") -> {
                for (WrappedWebElement nextElement : nextElements) {
                    selectDropdownByVisibleText(driver, nextElement, nextValue.value);
                }
            }
             case String s when s.contains("click") -> {
                for (WrappedWebElement nextElement : nextElements) {
                    click(driver, nextElement);
                }
            }
            case String s when s.contains("double click") -> {
                for (WrappedWebElement nextElement : nextElements) {
                    doubleClick(driver, nextElement);
                }
            }
            case String s when s.contains("right click") -> {
                for (WrappedWebElement nextElement : nextElements) {
                    contextClick(driver, nextElement);
                }
            }
            case String s when s.contains("enter") -> {
                for (WrappedWebElement nextElement : nextElements) {
                    typeText(driver, nextElement, nextValue.value);
                }
            }
            case String s when s.contains("scroll") -> {
                for (WrappedWebElement nextElement : nextElements) {
                    wheelScrollBy(driver, nextElement);
                }
            }
            case String s when s.contains("wait") -> {
                waitSeconds(Integer.parseInt(nextValue.value.replace("\"|'|`", "")));
            }
            case String s when s.contains("overwrite") -> {
                for (WrappedWebElement nextElement : nextElements) {
                    clearAndType(driver, nextElement, nextValue.value);
                }
            }
            default -> {
            }
        }
    }


}
