package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import org.openqa.selenium.Keys;
import org.openqa.selenium.NoAlertPresentException;
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
import java.util.ArrayList;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.browseroperations.BrowserAlerts.accept;
import static tools.dscode.common.browseroperations.BrowserAlerts.dismiss;
import static tools.dscode.common.domoperations.HumanInteractions.blur;
import static tools.dscode.common.domoperations.HumanInteractions.clear;
import static tools.dscode.common.domoperations.HumanInteractions.clearAndType;
import static tools.dscode.common.domoperations.HumanInteractions.click;
import static tools.dscode.common.domoperations.HumanInteractions.contextClick;
import static tools.dscode.common.domoperations.HumanInteractions.doubleClick;
import static tools.dscode.common.domoperations.HumanInteractions.selectDropdownByIndex;
import static tools.dscode.common.domoperations.HumanInteractions.selectDropdownByVisibleText;
import static tools.dscode.common.domoperations.HumanInteractions.sendKeys;
import static tools.dscode.common.domoperations.HumanInteractions.typeText;
import static tools.dscode.common.domoperations.HumanInteractions.wheelScrollBy;
import static tools.dscode.common.domoperations.KeyParser.sendComplexKeys;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady;
import static tools.dscode.common.domoperations.SeleniumUtils.waitForDuration;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.seleniumextensions.ElementWrapper.getWrappedElements;
import static tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatching.processElementMatches;
import static tools.dscode.coredefinitions.GeneralSteps.getDefaultDriver;

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
//            System.out.println(phraseData + " : Executing " + this.name());
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

    TAB {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            sendKeys(getDefaultDriver(), Keys.chord(Keys.CONTROL, Keys.SHIFT));
        }
    },


    WAIT {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher().mustMatchAtLeastOne(ElementType.TIME_VALUE, ElementType.HTML_ELEMENT)
            );
            ElementMatch waitElementMatch = phraseData.resultElements.getFirst();

            phraseData.result = Attempt.run(() -> {
                if (waitElementMatch.elementTypes.contains(ElementType.HTML_ELEMENT)) {
                    safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    boolean waitingOnLoading = waitElementMatch.elementTypes.contains(ElementType.HTML_LOADING);
                    while (true) {
                        List<ElementWrapper> wrappers = getWrappedElements(waitElementMatch);
                        if (wrappers.isEmpty()) {
                            if (waitingOnLoading)
                                return true;
                        } else if (!waitingOnLoading) {
                            return true;
                        }
                        System.out.println("Waiting on " +(waitingOnLoading? "LOADING" : waitElementMatch ));
                        waitMilliseconds(3000);
                    }
                } else {
                    waitForDuration(waitElementMatch.getValue().asDuration(waitElementMatch.category));
                }
                return true;
            });
//            phraseData.blurAfterOperation = true;
        }
    },

    SELECT {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());

            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAtLeastOne(ElementType.RETURNS_VALUE).mustNotMatchAny(ElementType.HTML_DROPDOWN),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT, ElementType.HTML_DROPDOWN)
            );

            ElementMatch selection = phraseData.resultElements.getFirst();
            ElementMatch dropDowns = phraseData.resultElements.get(1);

            phraseData.result = Attempt.run(() -> {
                int count = 0;
                for (ElementWrapper dropDownWrapper : dropDowns.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if (count > 0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }

                    if (selection.elementTypes.contains(ElementType.HTML_TYPE)) {


                        ElementWrapper optionElement = selection.getElementWrappers().getFirst();

                        if (optionElement.getTagName().equalsIgnoreCase("option")) {
                            selectDropdownByIndex(GeneralSteps.getDefaultDriver(), dropDownWrapper.getElement(), optionElement.getSameTagIndex());
                        } else {
                            click(GeneralSteps.getDefaultDriver(), optionElement.getElement());
                        }
                    } else {
                        selectDropdownByVisibleText(GeneralSteps.getDefaultDriver(), dropDownWrapper.getElement(), selection.getValue());
                    }
                    count++;
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
            ElementMatch element = phraseData.resultElements.getFirst();


            phraseData.result = Attempt.run(() -> {
                int count = 0;
                for (ElementWrapper elementWrapper : element.getElementThrowErrorIfEmptyWithNoModifier()) {
                    if (count > 0) {
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
                    if (count > 0) {
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
                    if (count > 0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }
                    contextClick(GeneralSteps.getDefaultDriver(), elementWrapper.getElement());
                    count++;
                }
                return true;
            });
        }
    },
    PRESS {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            textOperation(phraseData, false, true, true);
        }
    },
    ENTER {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            textOperation(phraseData, false, true);
        }
    },
    CLEAR {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            textOperation(phraseData, true, false);
        }
    },
    OVERWRITE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing " + this.name());
            textOperation(phraseData, true, true);
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
                    if (count > 0) {
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
                String windowHandle = element.category.toLowerCase().contains("new") || element.category.toLowerCase().contains("previous") ?
                        handleWrappers.getLast().getValue().toString() : handleWrappers.getFirst().getValue().toString();

                WindowSwitch.switchToHandleOrThrow(phraseData.getDriver(), windowHandle);
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
                    if (count > 0) {
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
            phraseData.result = Attempt.runVoid(() -> {
                try {
                    if(!phraseData.getFirstElement().category.startsWith("Alert"))
                        throw new RuntimeException("Accept only works on Alerts");
                    accept(GeneralSteps.getDefaultDriver());
                } catch (NoAlertPresentException e) {
                    if(!phraseData.getFirstElement().selectionType.equals("any"))
                        throw e;
                }
            });
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


    public void textOperation(PhraseData phraseData, boolean shouldClear, boolean shouldEnterText) {
        textOperation(phraseData, shouldClear, shouldEnterText, false);
    }

    public void textOperation(PhraseData phraseData,
                              boolean shouldClear,
                              boolean shouldEnterText,
                              boolean enterKeys) {

        // Keep your original semantics:
        // - if browserElement != null -> act once with null element
        // - else -> use htmlElementMatches; if empty -> act once with null element
        List<ElementMatch> elementMatches =
                (phraseData.browserElement != null) ? List.of() : phraseData.htmlElementMatches;

        // Precompute values once (so we don't re-walk value matches per input)
        List<ValueWrapper> valuesToEnter = phraseData.getValueTypeEntryElementMatches()
                .stream()
                .map(ElementMatch::getValue)
                .toList();

        phraseData.result = Attempt.run(() -> {
            int count = 0;

            // If no matches, do the operation once with null (no looping hacks).
            if (elementMatches == null || elementMatches.isEmpty()) {
                applyTextOps(null, valuesToEnter, shouldClear, shouldEnterText, enterKeys);
                return true;
            }

            for (ElementMatch inputMatch : elementMatches) {
                // If match is null, do the operation once with null and continue.
                if (inputMatch == null) {
                    applyTextOps(null, valuesToEnter, shouldClear, shouldEnterText, enterKeys);
                    continue;
                }

                List<ElementWrapper> wrappers = inputMatch.getElementThrowErrorIfEmptyWithNoModifier();

                // If wrappers empty, do the operation once with null (matches your prior behavior).
                if (wrappers == null || wrappers.isEmpty()) {
                    applyTextOps(null, valuesToEnter, shouldClear, shouldEnterText, enterKeys);
                    continue;
                }

                for (ElementWrapper wrapper : wrappers) {
                    WebElement webElement = (wrapper == null) ? null : wrapper.getElement();

                    if (count > 0) {
                        safeWaitForPageReady(GeneralSteps.getDefaultDriver(), Duration.ofSeconds(60), 300);
                    }

                    applyTextOps(webElement, valuesToEnter, shouldClear, shouldEnterText, enterKeys);
                    count++;
                }
            }

            return true;
        });
    }

    private void applyTextOps(WebElement webElement,
                              List<ValueWrapper> valuesToEnter,
                              boolean shouldClear,
                              boolean shouldEnterText,
                              boolean enterKeys) {

        if (shouldClear) {
            clear(GeneralSteps.getDefaultDriver(), webElement);
        }

        if (!shouldEnterText) {
            return;
        }

        for (ValueWrapper value : valuesToEnter) {
            String text = value.toString();

            if (enterKeys || value.type == ValueWrapper.ValueTypes.BACK_TICKED) {
                sendComplexKeys(getDefaultDriver(), webElement, text.toUpperCase());
            } else {
                typeText(GeneralSteps.getDefaultDriver(), webElement, text);
            }
            waitMilliseconds(300);
        }
    }






}
