//package tools.dscode.common.domoperations;
//
//
//import tools.dscode.common.status.SoftRuntimeException;
//import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
//import tools.dscode.common.treeparsing.parsedComponents.ElementType;
//import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
//
//import java.util.LinkedHashSet;
//import java.util.List;
//
//import static tools.dscode.common.domoperations.DomChecks.endsWithNormalized;
//import static tools.dscode.common.domoperations.DomChecks.equalsNormalized;
//import static tools.dscode.common.domoperations.DomChecks.evalTextToBool;
//import static tools.dscode.common.domoperations.DomChecks.hasAny;
//import static tools.dscode.common.domoperations.DomChecks.hasValue;
//import static tools.dscode.common.domoperations.DomChecks.isBlank;
//import static tools.dscode.common.domoperations.DomChecks.isTruthy;
//import static tools.dscode.common.domoperations.DomChecks.matchesNormalized;
//import static tools.dscode.common.domoperations.DomChecks.startsWithNormalized;
//
//
//public class ParsedAssertions_bu {
//
//
//    public static void executeAssertions(PhraseData phraseData) {
//
//        boolean anyTrue = phraseData.selectionType.equals("any");
//        boolean not = phraseData.hasNot;
//
//        LinkedHashSet<ElementMatch> generalValueElements = phraseData.getElementMatch(ElementType.RETURNS_VALUE);
//        LinkedHashSet<ElementMatch> htmlElements = phraseData.getElementMatch(ElementType.HTML_ELEMENT);
//        LinkedHashSet<ElementMatch> nonHtmlElements = phraseData.getElementMatch(ElementType.VALUE_TYPE);
//        ElementMatch elementMatch1 = htmlElements.getFirst();
//
//        ElementMatch valueBefore = phraseData.getElementMatch(phraseData.elementBeforeOperation , ElementType.RETURNS_VALUE).stream().findFirst().orElse(null);
////        ElementMatch valueAfter = phraseData.getElementMatch(phraseData.elementAfterOperation , ElementType.RETURNS_VALUE).stream().findFirst().orElse(null);
//        ElementMatch firstElementValue = phraseData.getElementMatch(ElementType.FIRST_ELEMENT).stream().findFirst().orElse(null);
//
//
//        List<ElementMatch> beforeElementMatches = phraseData.elementMatchesProceedingOperation;
//        List<ElementMatch> afterElementMatches = phraseData.elementMatchesFollowingOperation;
//
//
//        DomChecks.CheckResult result;
//        switch (phraseData.assertion) {
//            case String s when s.contains("equal") -> {
//                result = equalsNormalized(phraseData.getElementMatch(phraseData.elementBeforeOperation), phraseData.elementAfterOperation.getValue());
//            }
//            case String s when s.contains("start with") -> {
//                result = startsWithNormalized(valueBefore.getValue(), phraseData.elementAfterOperation.getValue());
//            }
//            case String s when s.contains("end with") -> {
//                result = endsWithNormalized(valueBefore.getValue(), phraseData.elementAfterOperation.getValue());
//            }
//            case String s when s.contains("match") -> {
//                result = matchesNormalized(valueBefore.getValues(), phraseData.elementAfterOperation.getValue().toString());
//            }
//            case String s when s.contains("displayed") -> {
//                result = hasAny(elementMatch1.findWrappedElements().stream().map(w -> w.element).toList());
//            }
//            case String s when s.contains("has value") -> {
//                result = hasValue(firstElementValue.getValues(), anyTrue);
//            }
//            case String s when s.contains("isBlank") -> {
//                result = isBlank(phraseData.components, anyTrue);
//            }
//            default -> {
//                if (!phraseData.wrappedElements.isEmpty()) {
//                    result = hasAny(phraseData.wrappedElements.stream().map(w -> w.element).toList());
//                } else if (!phraseData.components.isEmpty()) {
//                    result = isTruthy(firstElementValue.getValues());
//                } else {
//                    try {
//                        result = evalTextToBool(phraseData.body);
//                    }
//                    catch (Exception e) {
//                        System.out.println("Failed default evaluation of '" + phraseData.body + "'");
//                        throw new IllegalArgumentException("Unsupported assertion: " + phraseData.assertion);
//                    }
//                }
//            }
//
//        }
//
//
//        boolean passed = (!phraseData.hasNot == result.result());
//
//        if(phraseData.phraseType.equals(PhraseData.PhraseType.ASSERTION)) {
//            if (!passed) {
//                if (phraseData.assertionType.equals("ensure"))
//                    throw new RuntimeException(result.description());
//                else
//                    throw new SoftRuntimeException(result.description());
//            }
//        }
//        else
//        {
//
//            phraseData.phraseConditionalMode = passed ? 1 : -1;
//
//
//        }
//
//    }
//}
//
//
//
