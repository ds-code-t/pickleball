package tools.dscode.common.domoperations;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.parsedComponents.ValueMatch;
import tools.dscode.coredefinitions.GeneralSteps;


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
import static tools.dscode.coredefinitions.GeneralSteps.getDriver;


public class ParsedActions {


    public static void executeAction(WebDriver driver, PhraseData phraseData) {
        if(driver == null)
        {
            driver = GeneralSteps.getDriver();
        }

        MatchNode actionNode = phraseData.phraseNode.getChild("action");
        String action = phraseData.action;

        System.out.println("@@##phraseData.elements: " + phraseData.elements);
        System.out.println("@@##phraseData.previousPhrase: " + phraseData.previousPhrase);
        System.out.println("@@##action: " + action);

        if (phraseData.components == null || phraseData.components.isEmpty()) {
            phraseData.components = phraseData.previousPhrase.components;
            phraseData.elements = phraseData.previousPhrase.elements;
            phraseData.elementMatch = phraseData.previousPhrase.elementMatch;
            phraseData.wrappedElements = phraseData.previousPhrase.wrappedElements;
        }

//        if((phraseData.elements == null || phraseData.elements.isEmpty()) && phraseData.previousPhrase!= null && !action.equals("save") && !action.equals("wait"))
//        {
//            System.out.println("@@EE!!");
//            phraseData.elements = phraseData.previousPhrase.elements;
//            phraseData.elementMatch = phraseData.previousPhrase.elementMatch;
//            phraseData.wrappedElements = phraseData.previousPhrase.wrappedElements;
//        }
        System.out.println("@@##phraseData.elements: " + phraseData.elements);


        List<ElementMatch> nextElementMatches = phraseData.getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
        ElementMatch nextElementMatch = nextElementMatches.isEmpty() ? null : nextElementMatches.getFirst();
        System.out.println("@@##nextElementMatches: " + nextElementMatches);

        List<WebElement> nextElements = (nextElementMatch == null || nextElementMatch.wrappedElements.isEmpty()) ?
                new ArrayList<>() : nextElementMatch.wrappedElements.stream().map(e -> e.getElement()).toList();

        System.out.println("@@##nextElements: " + nextElements);

        ValueMatch nextValue = (ValueMatch) phraseData.getNextComponents(actionNode.position, "valueMatch").stream().findFirst().orElse(null);

        System.out.println("@@##nextValue: " + nextValue);

//        if (!action.equals("wait") && (nextElementMatches.isEmpty() || (!phraseData.elementMatch.selectionType.equals("any") && nextElements.isEmpty()))) {
        if ((phraseData.elementMatch != null && nextElements.isEmpty()) && (!phraseData.elementMatch.selectionType.equals("any"))) {
            String message = "No elements found for " + action;
            if (nextElementMatch != null && nextElementMatch.xPathy != null)
                message += " at " + nextElementMatch.xPathy.getXpath();
            throw new RuntimeException(message);
        }

        System.out.println("Attempting " + action);
        switch (action) {
            case String s when s.contains("save") -> {
                String keyName = phraseData.keyName.isBlank() ? "saved" : phraseData.keyName;
                List<String> values = phraseData.getAllPhraseValues();
                if (values.isEmpty() && phraseData.previousPhrase != null) {
                    values = phraseData.previousPhrase.getAllPhraseValues();
                }
                for (String value : values) {
                    printDebug("##Actions: saving '" + value + "' to key: " + keyName);
                    getRunningStep().getStepParsingMap().put(keyName, value);
                }


//                for (ElementWrapper nextElement : phraseData.wrappedElements) {
//                    if (keyName.isBlank())
//                        keyName = nextElement.elementMatch.category;
//                    printDebug("##Actions: saving '" + nextElement.getElementReturnValue() + "' to key: " + keyName);
//                    getRunningStep().getStepParsingMap().put(keyName, nextElement.getElementReturnValue());
//                }
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
