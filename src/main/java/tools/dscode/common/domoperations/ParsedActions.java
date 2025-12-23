package tools.dscode.common.domoperations;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.coredefinitions.GeneralSteps;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.HumanInteractions.clearAndType;
import static tools.dscode.common.domoperations.HumanInteractions.click;
import static tools.dscode.common.domoperations.HumanInteractions.contextClick;
import static tools.dscode.common.domoperations.HumanInteractions.doubleClick;
import static tools.dscode.common.domoperations.HumanInteractions.selectDropdownByVisibleText;
import static tools.dscode.common.domoperations.HumanInteractions.typeText;
import static tools.dscode.common.domoperations.HumanInteractions.wheelScrollBy;
import static tools.dscode.common.domoperations.SeleniumUtils.waitForDuration;
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

        System.out.println("@@##phraseData.elements: " + phraseData.elementMatches);
        System.out.println("@@##phraseData.previousPhrase: " + phraseData.previousPhrase);
        System.out.println("@@##action: " + action);

//        if (phraseData.components == null || phraseData.components.isEmpty()) {
//            phraseData.components = phraseData.previousPhrase.components;
//            phraseData.elements = phraseData.previousPhrase.elements;
//            phraseData.elementMatch = phraseData.previousPhrase.elementMatch;
//            phraseData.wrappedElements = phraseData.previousPhrase.wrappedElements;
//        }

//        if((phraseData.elements == null || phraseData.elements.isEmpty()) && phraseData.previousPhrase!= null && !action.equals("save") && !action.equals("wait"))
//        {
//            System.out.println("@@EE!!");
//            phraseData.elements = phraseData.previousPhrase.elements;
//            phraseData.elementMatch = phraseData.previousPhrase.elementMatch;
//            phraseData.wrappedElements = phraseData.previousPhrase.wrappedElements;
//        }
//        System.out.println("@@##phraseData.elements: " + phraseData.elements);
//
//        List<ElementMatch> nextElementMatches = phraseData.getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
//        ElementMatch nextElementMatch = nextElementMatches.isEmpty() ? null : nextElementMatches.getFirst();
//        System.out.println("@@##nextElementMatches: " + nextElementMatches);
//
//        List<WebElement> nextElements = (nextElementMatch == null || nextElementMatch.wrappedElements.isEmpty()) ?
//                new ArrayList<>() : nextElementMatch.wrappedElements.stream().map(e -> e.getElement()).toList();
//
//        System.out.println("@@##nextElements: " + nextElements);
//
//        ValueMatch nextValue = (ValueMatch) phraseData.getNextComponents(actionNode.position, "valueMatch").stream().findFirst().orElse(null);
//
//        System.out.println("@@##nextValue: " + nextValue);
//
////        if (!action.equals("wait") && (nextElementMatches.isEmpty() || (!phraseData.elementMatch.selectionType.equals("any") && nextElements.isEmpty()))) {
//        if ((phraseData.elementMatch != null && nextElements.isEmpty()) && (!phraseData.elementMatch.selectionType.equals("any"))) {
//            String message = "No elements found for " + action;
//            if (nextElementMatch != null && nextElementMatch.xPathy != null)
//                message += " at " + nextElementMatch.xPathy.getXpath();
//            throw new RuntimeException(message);
//        }

        LinkedHashSet<ElementMatch> generalValueElements = phraseData.getElementMatch(ElementType.RETURNS_VALUE);
        LinkedHashSet<ElementMatch> htmlElements = phraseData.getElementMatch(ElementType.HTML_ELEMENT);
        LinkedHashSet<ElementMatch> nonHtmlElements = phraseData.getElementMatch(ElementType.VALUE_TYPE);
        ElementMatch elementMatch1 = htmlElements.getFirst();


        System.out.println("Attempting " + action);
        System.out.println("@@elementMatch1 " + elementMatch1);
        System.out.println("@@phraseData.firstElement)--- " + phraseData.firstElement);
        System.out.println("@@phraseData.secondElement)--- " + phraseData.secondElement);
        switch (action) {
            case String s when s.contains("save") -> {
                String keyName = phraseData.keyName.isBlank() ? "saved" : phraseData.keyName;
                List<Object> values = phraseData.getAllPhraseValues();
                if (values.isEmpty() && phraseData.previousPhrase != null) {
                    values = phraseData.previousPhrase.getAllPhraseValues();
                }
                for (Object value : values) {
                    printDebug("##Actions: saving '" + value + "' to key: " + keyName);
                    getRunningStep().getStepParsingMap().put(keyName, value);
                }
            }
            case String s when s.contains("wait") -> {
                ElementMatch waitElementMatch = phraseData.getSingleElementMatch(ElementType.TIME_VALUE);
                waitForDuration(waitElementMatch.getValue().asDuration(waitElementMatch.category));
            }
            case String s when s.contains("select") -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    selectDropdownByVisibleText(driver, elementWrapper.getElement(), nonHtmlElements.getFirst().getValue().toString());
                }
            }
            case String s when s.contains("click") -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    click(driver, elementWrapper.getElement());
                }
            }
            case String s when s.contains("double click") -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    doubleClick(driver, elementWrapper.getElement());
                }
            }
            case String s when s.contains("right click") -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    contextClick(driver, elementWrapper.getElement());
                }
            }
            case String s when s.contains("enter") -> {
                System.out.println("@@enter: elementMatch1: " + elementMatch1);
                System.out.println("@@nonHtmlElements.size(): " + nonHtmlElements.size());
                if(nonHtmlElements.size() > 0)
                {
                    System.out.println("@@nonHtmlElements.getFirst(): " + nonHtmlElements.getFirst());
                    System.out.println("@@nonHtmlElements.getFirst().getValue(): " + nonHtmlElements.getFirst().getValue());
                }
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    System.out.println("@@elementWrapper::: " + elementWrapper);
                    System.out.println("@@elementWrapper.getElement(::: " + elementWrapper.getElement());
                    typeText(driver, elementWrapper.getElement(), nonHtmlElements.getFirst().getValue().toString());
                }
            }
            case String s when s.contains("scroll") -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    wheelScrollBy(driver, elementWrapper.getElement());
                }
            }
            case String s when s.contains("overwrite") -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    clearAndType(driver, elementWrapper.getElement(),  nonHtmlElements.getFirst().getValue().toString());
                }
            }
            default -> {
            }
        }
    }


}
