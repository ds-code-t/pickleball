package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import org.apache.poi.xssf.usermodel.XSSFPivotTable;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

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
import static tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatching.processElementMatches;
import static tools.dscode.coredefinitions.GeneralSteps.getDriver;

public enum ActionOperations implements OperationsInterface {

    SAVE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE).mustNotMatchAny(ElementType.KEY_VALUE),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.KEY_VALUE)
            );

            ElementMatch valueElement = list.getFirst();
            ElementMatch keyElement = list.get(1);
            String keyName = keyElement == null ? "saved" : keyElement.getValue().toString();
            phraseData.result = Attempt.run(() -> {
                for (ValueWrapper valueWrapper : valueElement.getValues()) {
                    System.out.println(phraseData + " : Executing " + this.name());
                    System.out.println("Action: " + this.name() + " : '" + valueWrapper + "' to key: " + keyName);
                    getRunningStep().getStepParsingMap().put(keyName, valueWrapper.getValue());
                }
            });
        }
    },

    WAIT {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.TIME_VALUE, ElementType.FOLLOWING_OPERATION)
            );
            ElementMatch waitElementMatch = list.getFirst();
            phraseData.result = Attempt.run(() -> {
                waitForDuration(waitElementMatch.getValue().asDuration(waitElementMatch.category));
            });
        }
    },

    SELECT {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch selection = list.getFirst();
            ElementMatch dropDowns = list.get(1);
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : dropDowns.getElementWrappers()) {
                    selectDropdownByVisibleText(getDriver(), elementWrapper.getElement(), selection.getValue().toString());
                }
            });
        }
    },

    CLICK {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch element = list.getFirst();
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : element.getElementWrappers()) {
                    click(getDriver(), elementWrapper.getElement());
                }
            });
        }
    },

    DOUBLE_CLICK {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch element = list.getFirst();
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : element.getElementWrappers()) {
                    doubleClick(getDriver(), elementWrapper.getElement());
                }
            });
        }
    },

    RIGHT_CLICK {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch element = list.getFirst();
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : element.getElementWrappers()) {
                    contextClick(getDriver(), elementWrapper.getElement());
                }
            });
        }
    },

    ENTER {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch value = list.getFirst();
            ElementMatch inputElement = list.get(1);
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : inputElement.getElementWrappers()) {
                    typeText(getDriver(), elementWrapper.getElement(), value.getValue().toString());
                }
            });
        }
    },
    OVERWRITE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch value = list.getFirst();
            ElementMatch inputElement = list.get(1);
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : inputElement.getElementWrappers()) {
                    clearAndType(getDriver(), elementWrapper.getElement(), value.getValue().toString());
                }
            });
        }
    },
    SCROLL {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            List<ElementMatch> list = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch element = list.getFirst();
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : element.getElementWrappers()) {
                    wheelScrollBy(getDriver(), elementWrapper.getElement());
                }
            });
        }
    };


    public static ActionOperations fromString(String input) {
        return OperationsInterface.requireOperationEnum(ActionOperations.class, input);
    }


}
