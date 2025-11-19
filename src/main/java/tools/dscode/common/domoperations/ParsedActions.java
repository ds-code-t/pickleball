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
import static tools.dscode.common.domoperations.HumanInteractions.typeText;
import static tools.dscode.common.domoperations.HumanInteractions.wheelScrollBy;
import static tools.dscode.common.domoperations.SeleniumUtils.explicitWait;
import static tools.dscode.common.util.DebugUtils.printDebug;


public class ParsedActions {


    public static void executeAction(ChromiumDriver driver, PhraseExecution phraseExecution) {

        MatchNode actionNode = phraseExecution.phraseNode.getChild("action");
        String action = actionNode.modifiedText();

        ElementMatch nextElementMatch = (ElementMatch) phraseExecution.getNextComponents(actionNode.position, "elementMatch").stream().findFirst().orElse(null);
        List<WebElement> nextElements = nextElementMatch == null || nextElementMatch.matchedElements == null ?
                new ArrayList<>() : nextElementMatch.matchedElements;
        ValueMatch nextValue = (ValueMatch) phraseExecution.getNextComponents(actionNode.position, "valueMatch").stream().findFirst().orElse(null);

        if (!action.equals("wait") && nextElements.isEmpty()) {
            String message = "No elements found for " + action;
            if (nextElementMatch != null && nextElementMatch.xPathy != null)
                message += " at " + nextElementMatch.xPathy.getXpath();
            throw new RuntimeException(message);
        }

        switch (action) {
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
            case String s when s.contains("wait") -> {
                explicitWait(Long.parseLong(nextValue.value.replace("\"|'|`", "")));
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
