package tools.dscode.common.domoperations;

import org.openqa.selenium.WebDriver;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.coredefinitions.GeneralSteps;


import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.HumanInteractions.clearAndType;
import static tools.dscode.common.domoperations.HumanInteractions.click;
import static tools.dscode.common.domoperations.HumanInteractions.contextClick;
import static tools.dscode.common.domoperations.HumanInteractions.doubleClick;
import static tools.dscode.common.domoperations.HumanInteractions.selectDropdownByVisibleText;
import static tools.dscode.common.domoperations.HumanInteractions.typeText;
import static tools.dscode.common.domoperations.HumanInteractions.wheelScrollBy;
import static tools.dscode.common.domoperations.SeleniumUtils.waitForDuration;
import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.coredefinitions.GeneralSteps.getDriver;


public class ParsedActions {

    public enum ActionOperations {
        SAVE(
                EnumSet.of(ElementType.RETURNS_VALUE),
                EnumSet.of(ElementType.KEY_VALUE)
        ),
         WAIT(
                 EnumSet.of(ElementType.TIME_VALUE)
         ),
        SELECT(
                EnumSet.of(ElementType.RETURNS_VALUE),
                EnumSet.of(ElementType.HTML_ELEMENT)
                ),
        CLICK(
                EnumSet.of(ElementType.HTML_ELEMENT)
                ),
        DOUBLE_CLICK(
                EnumSet.of(ElementType.HTML_ELEMENT)
                ),
        RIGHT_CLICK(
                EnumSet.of(ElementType.HTML_ELEMENT)
                ),
        ENTER(
                EnumSet.of(ElementType.VALUE_TYPE),
                EnumSet.of(ElementType.HTML_ELEMENT)
                ),
        SCROLL(
                EnumSet.of(ElementType.HTML_ELEMENT)
                ),
        OVERWRITE;

        private final List<EnumSet<ElementType>> elementTypeGroups;

        @SafeVarargs
        ActionOperations(EnumSet<ElementType>... groups) {
            this.elementTypeGroups = List.of(groups);
        }

        public List<EnumSet<ElementType>> elementTypeGroups() {
            return elementTypeGroups;
        }

        public static ActionOperations requireActionOperationFromContainingString(String input) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "Action operation text was null. Expected one of: "
                                + Arrays.toString(ActionOperations.values())
                );
            }

            String normalized = input
                    .trim()
                    .toUpperCase(Locale.ROOT)
                    .replaceAll("\\s+", "_");

            return Arrays.stream(ActionOperations.values())
                    .sorted(Comparator.comparingInt(op -> -op.name().length())) // longest first
                    .filter(op -> normalized.contains(op.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No ActionOperation matched text: '" + input + "' " +
                                    "(normalized: '" + normalized + "'). Expected one of: "
                                    + Arrays.toString(ActionOperations.values())
                    ));
        }







    }

    public static void executeAction(WebDriver driver, PhraseData phraseData) {
        if(driver == null)
        {
            driver = GeneralSteps.getDriver();
        }


//        MatchNode actionNode = phraseData.phraseNode.getChild("action");


        System.out.println("@@##phraseData.elements: " + phraseData.elementMatches);
        System.out.println("@@##phraseData.previousPhrase: " + phraseData.previousPhrase);
        System.out.println("@@##action: " + phraseData.actionOperation);


        LinkedHashSet<ElementMatch> generalValueElements = phraseData.getElementMatch(ElementType.RETURNS_VALUE);
        LinkedHashSet<ElementMatch> htmlElements = phraseData.getElementMatch(ElementType.HTML_ELEMENT);
        LinkedHashSet<ElementMatch> nonHtmlElements = phraseData.getElementMatch(ElementType.VALUE_TYPE);
        ElementMatch elementMatch1 = htmlElements.getFirst();


        System.out.println("Attempting " + phraseData.actionOperation);
        System.out.println("@@elementMatch1 " + elementMatch1);
        System.out.println("@@phraseData.firstElement)--- " + phraseData.getFirstElement());
        System.out.println("@@phraseData.secondElement)--- " + phraseData.getSecondElement());


        switch (phraseData.actionOperation) {
            case SAVE -> {
                ElementMatch keyElement = phraseData.getSingleElementMatch(ElementType.KEY_VALUE);
                String keyName = keyElement == null ? "saved" : keyElement.getValue().toString();
                ElementMatch valueMatch =  generalValueElements.stream().filter(e -> !e.elementTypes.contains(ElementType.KEY_VALUE)).findFirst().orElse(null);
                for (ValueWrapper valueWrapper : valueMatch.getValues()) {
                    printDebug("##Actions: saving '" + valueWrapper + "' to key: " + keyName);
                    getRunningStep().getStepParsingMap().put(keyName, valueWrapper.getValue());
                }
            }
            case WAIT -> {
                ElementMatch waitElementMatch = phraseData.getSingleElementMatch(ElementType.TIME_VALUE);
                waitForDuration(waitElementMatch.getValue().asDuration(waitElementMatch.category));
            }
            case SELECT -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    selectDropdownByVisibleText(driver, elementWrapper.getElement(), nonHtmlElements.getFirst().getValue().toString());
                }
            }
            case CLICK -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    click(driver, elementWrapper.getElement());
                }
            }
            case DOUBLE_CLICK -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    doubleClick(driver, elementWrapper.getElement());
                }
            }
            case RIGHT_CLICK -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    contextClick(driver, elementWrapper.getElement());
                }
            }
            case ENTER -> {
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
            case SCROLL -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    wheelScrollBy(driver, elementWrapper.getElement());
                }
            }
            case OVERWRITE -> {
                for (ElementWrapper elementWrapper : elementMatch1.getElementWrappers()) {
                    clearAndType(driver, elementWrapper.getElement(),  nonHtmlElements.getFirst().getValue().toString());
                }
            }
            default -> {
                throw new RuntimeException("Unknown action: " + phraseData.actionOperation);
            }
        }
    }


}
