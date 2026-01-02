package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import org.apache.poi.xssf.usermodel.XSSFPivotTable;
import org.openqa.selenium.WebElement;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.util.FileUploadUtil;

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
import static tools.dscode.common.treeparsing.DefinitionContext.FILE_INPUT;
import static tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatching.processElementMatches;
import static tools.dscode.coredefinitions.GeneralSteps.getDriver;

public enum ActionOperations implements OperationsInterface {
    ATTACH {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE)
            );

            ElementMatch pathElement = phraseData.resultElements.getFirst();
            ElementMatch fileInput = new ElementMatch(phraseData, FILE_INPUT);
            phraseData.result = Attempt.run(() -> {
                WebElement inputElement = fileInput.getElementWrappers().getFirst().getElement();
                FileUploadUtil.upload(getDriver(), inputElement, pathElement.getValue().toString());
                return true;
            });
        }
    },
    SAVE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE).mustNotMatchAny(ElementType.KEY_VALUE),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.KEY_VALUE)
            );

            ElementMatch valueElement = phraseData.resultElements.getFirst();
            ElementMatch keyElement = phraseData.resultElements.get(1);
            String keyName = keyElement == null ? "saved" : keyElement.getValue().toString();
            phraseData.result = Attempt.run(() -> {
                for (ValueWrapper valueWrapper : valueElement.getValues()) {
                    System.out.println(phraseData + " : Executing " + this.name());
                    System.out.println("Action: " + this.name() + " : '" + valueWrapper + "' to key: '" + keyName + "'");
                    getRunningStep().getStepParsingMap().put(keyName, valueWrapper.getValue());
                }
                return true;
            });
        }
    },

    WAIT {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.TIME_VALUE, ElementType.FOLLOWING_OPERATION)
            );
            ElementMatch waitElementMatch = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                waitForDuration(waitElementMatch.getValue().asDuration(waitElementMatch.category));
                return true;
            });
        }
    },

    SELECT {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch selection = phraseData.resultElements.getFirst();
            ElementMatch dropDowns = phraseData.resultElements.get(1);
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : dropDowns.getElementThrowErrorIfEmptyWithNoModifier()) {
                    selectDropdownByVisibleText(getDriver(), elementWrapper.getElement(), selection.getValue().toString());
                }
                return true;
            });
        }
    },

    CLICK {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());


            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            System.out.println("@WphraseData.resultElements: " + phraseData.resultElements);

            ElementMatch element = phraseData.resultElements.getFirst();

            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    click(getDriver(), elementWrapper.getElement());
                }
                return true;
            });

        }
    },

    DOUBLE_CLICK {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch element = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    doubleClick(getDriver(), elementWrapper.getElement());
                }
                return true;
            });
        }
    },

    RIGHT_CLICK {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch element = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    contextClick(getDriver(), elementWrapper.getElement());
                }
                return true;
            });
        }
    },

    ENTER {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch value = phraseData.resultElements.getFirst();
            ElementMatch inputElement = phraseData.resultElements.get(1);
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : inputElement.getElementThrowErrorIfEmptyWithNoModifier()) {
                    typeText(getDriver(), elementWrapper.getElement(), value.getValue().toString());
                }
                return true;
            });
        }
    },
    OVERWRITE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch value = phraseData.resultElements.getFirst();
            ElementMatch inputElement = phraseData.resultElements.get(1);
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : inputElement.getElementThrowErrorIfEmptyWithNoModifier()) {
                    clearAndType(getDriver(), elementWrapper.getElement(), value.getValue().toString());
                }
                return true;
            });
        }
    },
    SCROLL {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch element = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    wheelScrollBy(getDriver(), elementWrapper.getElement());
                }
                return true;
            });
        }
    };


    public static ActionOperations fromString(String input) {
        return OperationsInterface.requireOperationEnum(ActionOperations.class, input);
    }


}
