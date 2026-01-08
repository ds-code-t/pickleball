package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import org.openqa.selenium.WebElement;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.browseroperations.WindowSwitch;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.util.FileUploadUtil;
import tools.dscode.coredefinitions.GeneralSteps;

import java.time.Duration;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.browseroperations.BrowserAlerts.accept;
import static tools.dscode.common.browseroperations.BrowserAlerts.dismiss;
import static tools.dscode.common.domoperations.HumanInteractions.clearAndType;
import static tools.dscode.common.domoperations.HumanInteractions.click;
import static tools.dscode.common.domoperations.HumanInteractions.contextClick;
import static tools.dscode.common.domoperations.HumanInteractions.doubleClick;
import static tools.dscode.common.domoperations.HumanInteractions.selectDropdownByVisibleText;
import static tools.dscode.common.domoperations.HumanInteractions.typeText;
import static tools.dscode.common.domoperations.HumanInteractions.wheelScrollBy;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady;
import static tools.dscode.common.domoperations.LeanWaits.waitForPageReady;
import static tools.dscode.common.domoperations.SeleniumUtils.waitForDuration;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatching.processElementMatches;
import static tools.dscode.coredefinitions.GeneralSteps.getJavascriptExecutor;

public enum ActionOperations implements OperationsInterface {
    CREATE_AND_ATTACH {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE)
            );
            ElementMatch fileInputElement = phraseData.resultElements.getFirst();


            ElementMatch filePathElement = phraseData.resultElements.get(1);

            phraseData.result = Attempt.run(() -> {
                WebElement inputElement = fileInputElement.getElementWrappers().getFirst().getElement();
                FileUploadUtil.createTempAndUpload(GeneralSteps.getDefaultDriver(), inputElement, filePathElement.getValue().toString());
                return true;
            });
        }
    },
    ATTACH {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE)
            );
            ElementMatch fileInputElement = phraseData.resultElements.getFirst();


            ElementMatch filePathElement = phraseData.resultElements.get(1);

            phraseData.result = Attempt.run(() -> {
                WebElement inputElement = fileInputElement.getElementWrappers().getFirst().getElement();
                FileUploadUtil.upload(GeneralSteps.getDefaultDriver(), inputElement, filePathElement.getValue().toString());
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
                int count = 0;
                for (ElementWrapper elementWrapper : dropDowns.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if(count>0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    selectDropdownByVisibleText(GeneralSteps.getDefaultDriver(), elementWrapper.getElement(), selection.getValue().toString());
                    count++;
                }
                System.out.println("@@dropDowns.getElementWrappers().size() " + dropDowns.getElementWrappers().size());
                return true;
            });
        }
    },

    CLICK {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());

            System.out.println("@@phraseData.getElementMatches(): " + phraseData.getElementMatches());
            System.out.println("@@ phraseData.getFirstElement().startIndex: " +  phraseData.getFirstElement().startIndex);
            System.out.println("@@phraseData.operationIndex: " +  phraseData.operationIndex);

            System.out.println("@@phraseData.getElementMatchesFollowingOperation(): " + phraseData.getElementMatchesFollowingOperation());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            System.out.println("@WphraseData.resultElements: " + phraseData.resultElements);

            ElementMatch element = phraseData.resultElements.getFirst();


            phraseData.result = Attempt.run(() -> {
                int count = 0;
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if(count>0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    click(GeneralSteps.getDefaultDriver(), elementWrapper.getElement());
                    count++;
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
                int count = 0;
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if(count>0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    doubleClick(GeneralSteps.getDefaultDriver(), elementWrapper.getElement());
                    count++;
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
                int count = 0;
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if(count>0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    contextClick(GeneralSteps.getDefaultDriver(), elementWrapper.getElement());
                    count++;
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
                int count = 0;
                for (ElementWrapper elementWrapper : inputElement.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if(count>0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    typeText(GeneralSteps.getDefaultDriver(), elementWrapper.getElement(), value.getValue().toString());
                    count++;
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
                int count = 0;
                for (ElementWrapper elementWrapper : inputElement.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if(count>0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    clearAndType(GeneralSteps.getDefaultDriver(), elementWrapper.getElement(), value.getValue().toString());
                    count++;
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
                int count = 0;
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if(count>0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    wheelScrollBy(GeneralSteps.getDefaultDriver(), elementWrapper.getElement());
                    count++;
                }
                return true;
            });
        }
    },
    SWITCH {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.BROWSER_WINDOW)
            );

            ElementMatch element = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                List<ValueWrapper> handleWrappers = element.getValues();
                if (handleWrappers.isEmpty() && !element.selectionType.equals("any")) {
                    throw new RuntimeException("No matching Windows or Tabs found for " + element);
                }
                WindowSwitch.switchToHandleOrThrow(phraseData.getDriver(), handleWrappers.getFirst().getValue().toString());
                return true;
            });
        }
    },
    CLOSE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());


            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );


            ElementMatch element = phraseData.resultElements.getFirst();

            phraseData.result = Attempt.run(() -> {
                int count = 0;
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if(count>0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    elementWrapper.close();
                    count++;
                }
                return true;
            });

        }
    },
    ACCEPT {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.result = Attempt.runVoid(() ->
                    accept(GeneralSteps.getDefaultDriver())
            );
        }
    },
    DISMISS {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.result = Attempt.runVoid(() ->
                    dismiss(GeneralSteps.getDefaultDriver())
            );
        }
    };


    public static ActionOperations fromString(String input) {
        return OperationsInterface.requireOperationEnum(ActionOperations.class, input);
    }


}
