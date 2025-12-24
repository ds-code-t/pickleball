package tools.dscode.common.domoperations;


import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapperCompareReducer;
import tools.dscode.common.assertions.ValueWrapperComparisons;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;

import java.util.Locale;
import java.util.Set;


public class ParsedAssertions {

    public enum AssertionOperations {
        EQUAL, START_WITH, END_WITH, MATCH, HAS_VALUE, IS_BLANK, DISPLAYED, ENABLED,  SELECTED, IS_TRUE, IS_FALSE;

        public static AssertionOperations requireAssertionFromContainingString(String input) {
            if (input == null || input.isBlank()) {
                throw new IllegalArgumentException("Action text was null. Expected one of: " + Arrays.toString(AssertionOperations.values()));
            }

            String normalized = input
                    .trim()
                    .toUpperCase(Locale.ROOT)
                    .replaceAll("\\s+", "_").replaceAll("", "_");

            return Arrays.stream(AssertionOperations.values())
                    .sorted(Comparator.comparingInt(a -> -a.name().length())) // longest first
                    .filter(a -> normalized.contains(a.name()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No Action matched text: '" + input + "' (normalized: '" + normalized + "'). " +
                                    "Expected one of: " + Arrays.toString(AssertionOperations.values())
                    ));
        }


    }


    public static void executeAssertions(PhraseData phraseData) {
        System.out.println("@@executeAssertions: " + phraseData);


//        LinkedHashSet<ElementMatch> generalValueElements = phraseData.getElementMatch(ElementType.RETURNS_VALUE);
//        LinkedHashSet<ElementMatch> htmlElements = phraseData.getElementMatch(ElementType.HTML_ELEMENT);
//        LinkedHashSet<ElementMatch> nonHtmlElements = phraseData.getElementMatch(ElementType.VALUE_TYPE);
//        ElementMatch elementMatch1 = htmlElements.getFirst();
//
//        ElementMatch valueBefore = phraseData.getElementMatch(phraseData.elementBeforeOperation, ElementType.RETURNS_VALUE).stream().findFirst().orElse(null);
//        ElementMatch firstElementValue = phraseData.getElementMatch(ElementType.FIRST_ELEMENT).stream().findFirst().orElse(null);
//                List<ElementMatch> beforeElementMatches = phraseData.elementMatchesProceedingOperation;
//        List<ElementMatch> afterElementMatches = phraseData.elementMatchesFollowingOperation;
//
//        ElementMatch firstElement = phraseData.getElementMatch(phraseData.elementAfterOperation, ElementType.RETURNS_VALUE).stream().findFirst().orElse(null);

        ElementMatch firstHTMLElementValue = phraseData.getElementMatch(ElementType.HTML_ELEMENT).stream().findFirst().orElse(null);
        ElementMatch firstValueElementValue = phraseData.getElementMatch(ElementType.VALUE_TYPE).stream().findFirst().orElse(null);
        ElementMatch firstReturnValue = phraseData.getElementMatch(ElementType.RETURNS_VALUE).stream().findFirst().orElse(null);

        ElementMatch elementPrecedingOperation = phraseData.getElementMatch(ElementType.PRECEDING_OPERATION).stream().findFirst().orElse(null);
        ElementMatch elementFollowingOperation = phraseData.getElementMatch(ElementType.FOLLOWING_OPERATION).stream().findFirst().orElse(null);

        ElementMatch precedingOperation = phraseData.getElementMatch(phraseData.getElementBeforeOperation(), ElementType.PRECEDING_OPERATION).stream().findFirst().orElse(null);


        Set<ValueWrapperCompareReducer.Mode> modeSet = new HashSet<>();
        if (phraseData.selectionType.equals("any"))
            modeSet.add(ValueWrapperCompareReducer.Mode.ANY);
        if (phraseData.selectionType.equals("none"))
            modeSet.add(ValueWrapperCompareReducer.Mode.NONE);
        if (phraseData.selectionType.equals("not"))
            modeSet.add(ValueWrapperCompareReducer.Mode.NOT);
        if (phraseData.getAssertion().startsWith("un") || phraseData.getAssertion().startsWith("disable"))
            modeSet.add(ValueWrapperCompareReducer.Mode.UN);




        ValueWrapperCompareReducer.Mode[] modeArray = modeSet.toArray(new ValueWrapperCompareReducer.Mode[0]);

        boolean passed;

        ElementMatch a = null;
        ElementMatch b = null;

        System.out.println("@@assertionMatch: " + phraseData.assertionOperation);
        switch (phraseData.assertionOperation) {
            case EQUAL -> {
                a = elementPrecedingOperation;
                b = elementFollowingOperation;
                passed = ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::equals,
                        a.getValues(),
                        b.getValues(),
                        modeArray
                );
            }
            case START_WITH -> {
                a = elementPrecedingOperation;
                b = elementFollowingOperation;
                passed = ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::startsWith,
                        a.getValues(),
                        b.getValues(),
                        modeArray
                );
            }
            case END_WITH -> {
                a = elementPrecedingOperation;
                b = elementFollowingOperation;
                passed = ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::endsWith,
                        a.getValues(),
                        b.getValues(),
                        modeArray
                );
            }
            case MATCH -> {
                a = elementPrecedingOperation;
                b = elementFollowingOperation;
                passed = ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::matchesRegex,
                        a.getValues(),
                        b.getValues(),
                        modeArray
                );
            }


            case DISPLAYED -> {
                a = firstHTMLElementValue;
                passed =
                        ValueWrapperCompareReducer.evalElements(
                                ElementWrapper::isDisplayed,
                                a.getElementWrappers(),
                                modeArray
                        );
            }

            case ENABLED -> {
                a = firstHTMLElementValue;
                passed =
                        ValueWrapperCompareReducer.evalElements(
                                ElementWrapper::isEnabled,
                                a.getElementWrappers(),
                                modeArray
                        );
            }

            case SELECTED -> {
                a = firstHTMLElementValue;
                passed =
                        ValueWrapperCompareReducer.evalElements(
                                ElementWrapper::isSelected,
                                a.getElementWrappers(),
                                modeArray
                        );
            }

            case HAS_VALUE -> {
                a = precedingOperation;
                passed = ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::hasValue,
                        a.getValues(),
                        modeArray
                );
            }

            case IS_BLANK -> {
                a = precedingOperation;
                passed = ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::isBlank,
                        a.getValues(),
                        modeArray
                );
            }


            default -> {
                a = phraseData.getFirstElement();
                b = phraseData.getSecondElement();
                if (a != null && b != null) {
                    passed = ValueWrapperCompareReducer.eval(
                            ValueWrapperComparisons::equals,
                            a.getValues(),
                            b.getValues(),
                            modeArray
                    );
                } else {
                    ElementMatch singleMatch = a == null ? b : a;

                    if (singleMatch == null) {
                        passed = false;
                    } else {
                        passed = ValueWrapperCompareReducer.evalValues(
                                ValueWrapper::isTruthy,
                                singleMatch.getValues(),
                                modeArray
                        );
                    }
                }
            }

        }
        String assertionMessage = "Assertion phrase '" + phraseData.text + "' evaluates to: " + passed;
        if (a != null) {
            assertionMessage += "\n" + a + ":\n" + a.getValues();
        }
        if (b != null) {
            assertionMessage += "\n" + b + ":\n" + b.getValues();
        }

        System.out.println(assertionMessage);
        switch (phraseData.assertionType) {
            case "ensure" -> {
                if (!passed) {
                    throw new RuntimeException("FAILED  " + assertionMessage);
                }
            }
            case "verify" -> {
                if (!passed) {
                    throw new SoftRuntimeException("FAILED  " + assertionMessage);
                }
            }
            case "conditional" -> {
                phraseData.phraseConditionalMode = passed ? 1 : -1;
            }
        }


    }
}



