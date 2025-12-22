package tools.dscode.common.domoperations;



import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.assertions.ValueWrapperCompareReducer;
import tools.dscode.common.assertions.ValueWrapperComparisons;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.HashSet;

import java.util.Set;


public class ParsedAssertions {


    public static void executeAssertions(PhraseData phraseData) {

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

        ElementMatch precedingOperation = phraseData.getElementMatch(phraseData.elementBeforeOperation, ElementType.PRECEDING_OPERATION).stream().findFirst().orElse(null);



        Set<ValueWrapperCompareReducer.Mode> modeSet = new HashSet<>();
        if (phraseData.selectionType.equals("any"))
            modeSet.add(ValueWrapperCompareReducer.Mode.ANY);
        if (phraseData.selectionType.equals("none"))
            modeSet.add(ValueWrapperCompareReducer.Mode.NONE);
        if (phraseData.selectionType.equals("not"))
            modeSet.add(ValueWrapperCompareReducer.Mode.NOT);
        if (phraseData.assertion.startsWith("un") || phraseData.assertion.startsWith("disable"))
            modeSet.add(ValueWrapperCompareReducer.Mode.UN);

        String assertionMatch = phraseData.assertion
                .replaceAll("disable", "enable")
                .replaceAll("^un", "")
                .replaceAll("check", "select");

        ValueWrapperCompareReducer.Mode[] modeArray = modeSet.toArray(new ValueWrapperCompareReducer.Mode[0]);

        boolean passed;

        ElementMatch a  = null;
        ElementMatch b = null;

        switch (assertionMatch) {
            case String s when s.contains("equal") -> {
                a = elementPrecedingOperation;
                b = elementFollowingOperation;
                passed = ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::equals,
                        a.getValues(),
                        b.getValues(),
                        modeArray
                );
            }
            case String s when s.contains("start with") -> {
                a = elementPrecedingOperation;
                b = elementFollowingOperation;
                passed = ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::startsWith,
                        a.getValues(),
                        b.getValues(),
                        modeArray
                );
            }
            case String s when s.contains("end with") -> {
                a = elementPrecedingOperation;
                b = elementFollowingOperation;
                passed = ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::endsWith,
                        a.getValues(),
                        b.getValues(),
                        modeArray
                );
            }
            case String s when s.contains("match") -> {
                a = elementPrecedingOperation;
                b = elementFollowingOperation;
                passed = ValueWrapperCompareReducer.eval(
                        ValueWrapperComparisons::matchesRegex,
                        a.getValues(),
                        b.getValues(),
                        modeArray
                );            }



            case String s when s.contains("display") -> {
                a = firstHTMLElementValue;
                passed =
                        ValueWrapperCompareReducer.evalElements(
                                ElementWrapper::isDisplayed,
                                a.getElementWrappers(),
                                modeArray
                        );
            }

            case String s when s.contains("enable") -> {
                a = firstHTMLElementValue;
                passed =
                        ValueWrapperCompareReducer.evalElements(
                                ElementWrapper::isEnabled,
                                a.getElementWrappers(),
                                modeArray
                        );
            }

            case String s when s.contains("selected") -> {
                a = firstHTMLElementValue;
                passed =
                        ValueWrapperCompareReducer.evalElements(
                                ElementWrapper::isSelected,
                                a.getElementWrappers(),
                                modeArray
                        );
            }

            case String s when s.contains("has value") -> {
                a = precedingOperation;
                passed =  ValueWrapperCompareReducer.evalValues(
                        ValueWrapper::hasValue,
                        a.getValues(),
                        modeArray
                );
            }

            case String s when s.contains("isBlank") -> {
                a = precedingOperation;
                passed =  ValueWrapperCompareReducer.evalValues(
                            ValueWrapper::isBlank,
                            a.getValues(),
                            modeArray
                    );
            }


            default -> {
                a = phraseData.firstElement;
                b = phraseData.secondElement;
                if(a != null && b != null) {
                    passed = ValueWrapperCompareReducer.eval(
                            ValueWrapperComparisons::equals,
                            a.getValues(),
                            b.getValues(),
                            modeArray
                    );
                }
                else {
                    ElementMatch singleMatch = a == null ? b : a;

                    if(singleMatch == null) {
                        passed = false;
                    }
                    else {
                        passed =  ValueWrapperCompareReducer.evalValues(
                                ValueWrapper::isTruthy,
                                singleMatch.getValues(),
                                modeArray
                        );
                    }


                }
            }

        }
        String assertionMessage = "Assertion phrase " + phraseData.text + " is " + passed;
        if(a!= null)
        {
            assertionMessage+= "\n" + a + ":\n"+ a.getValues();
        }
        if(b!= null)
        {
            assertionMessage+= "\n" + b + ":\n"+ b.getValues();
        }

        if (phraseData.phraseType.equals(PhraseData.PhraseType.ASSERTION)) {
            if (!passed) {String errorMessage = "FAILED  " + assertionMessage;
                if (phraseData.assertionType.equals("ensure"))
                    throw new RuntimeException(errorMessage);
                else
                    throw new SoftRuntimeException(errorMessage);
            }
        } else {
            System.out.println(assertionMessage);
            phraseData.phraseConditionalMode = passed ? 1 : -1;
        }

    }
}



