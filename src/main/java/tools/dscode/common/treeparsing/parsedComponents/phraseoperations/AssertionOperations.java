package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapperCompareReducer;
import tools.dscode.common.assertions.ValueWrapperComparisons;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatching.processElementMatches;
import static tools.dscode.common.util.DebugUtils.printDebug;

public enum AssertionOperations implements OperationsInterface {


    EQUAL {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            System.out.println("@@phraseData.getElementMatchesBeforeAndAfterOperation(): " + phraseData.getElementMatchesBeforeAndAfterOperation());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesBeforeAndAfterOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.FIRST_ELEMENT),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.SECOND_ELEMENT)
            );

            System.out.println("@@resultElements: " + phraseData.resultElements);

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            ElementMatch secondElement = phraseData.resultElements.get(1);


            phraseData.result = Attempt.run(() -> {
                 return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::equals,
                        firstElement.getValues(),
                        secondElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    START_WITH {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesBeforeAndAfterOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.FIRST_ELEMENT),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.SECOND_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            ElementMatch secondElement = phraseData.resultElements.get(1);
            phraseData.result = Attempt.run(() -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::startsWith,
                        firstElement.getValues(),
                        secondElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    END_WITH {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesBeforeAndAfterOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.FIRST_ELEMENT),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.SECOND_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            ElementMatch secondElement = phraseData.resultElements.get(1);
            phraseData.result = Attempt.run(() -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::endsWith,
                        firstElement.getValues(),
                        secondElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },
    MATCH {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesBeforeAndAfterOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.FIRST_ELEMENT),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE, ElementType.SECOND_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            ElementMatch secondElement = phraseData.resultElements.get(1);


            phraseData.result = Attempt.run(() -> {
                return ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::matchesRegex,
                        firstElement.getValues(),
                        secondElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    HAS_VALUE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesProceedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                return  ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::hasValue,
                        firstElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },
    IS_BLANK {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesProceedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                return    ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::isBlank,
                        firstElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },
    DISPLAYED {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesProceedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );


            ElementMatch firstElement = phraseData.resultElements.getFirst();
            if(firstElement.getElementWrappers().isEmpty())
            {
                phraseData.result = new Attempt.Result(getModeSet(phraseData).contains(ValueWrapperCompareReducer.Mode.NOT), null);
                return;
            }
            phraseData.result = Attempt.run(() -> {
                return   ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isDisplayed,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
            




        }
    },

    ENABLED {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesProceedingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                return    ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isEnabled,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    SELECTED {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatchesFollowingOperation(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.HTML_ELEMENT)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                return    ValueWrapperCompareReducer.evalElements(
                        ElementWrapper::isSelected,
                        firstElement.getElementWrappers(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    TRUE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatches(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                return   ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::isTruthy,
                        firstElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    },

    FALSE {
        @Override
        public void execute(PhraseData phraseData) {
            System.out.println(phraseData + " : Executing Assertion " + this.name());
            phraseData.resultElements = processElementMatches(phraseData, phraseData.getElementMatches(),
                    new ElementMatcher()
                            .mustMatchAll(ElementType.RETURNS_VALUE)
            );

            ElementMatch firstElement = phraseData.resultElements.getFirst();
            phraseData.result = Attempt.run(() -> {
                return    ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::isFalsy,
                        firstElement.getValues(),
                        getModeSet(phraseData)
                );
            });
        }
    };


    public static AssertionOperations

    fromString(String input) {
        return OperationsInterface.requireOperationEnum(AssertionOperations.class, input);
    }


    public Set<ValueWrapperCompareReducer.Mode> getModeSet(PhraseData phraseData) {
        Set<ValueWrapperCompareReducer.Mode> modeSet = new HashSet<>();
        if (phraseData.resultElements.stream().anyMatch(e -> e.selectionType.equals("any")))
            modeSet.add(ValueWrapperCompareReducer.Mode.ANY);
        if (phraseData.resultElements.stream().anyMatch(e -> e.selectionType.equals("none")))
            modeSet.add(ValueWrapperCompareReducer.Mode.NONE);
        if (phraseData.hasNo)
            modeSet.add(ValueWrapperCompareReducer.Mode.NOT);
        if (phraseData.getAssertion().startsWith("un") || phraseData.getAssertion().startsWith("disable"))
            modeSet.add(ValueWrapperCompareReducer.Mode.UN);
        return modeSet;
    }


}
