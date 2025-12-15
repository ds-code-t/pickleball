package tools.dscode.common.domoperations;


import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.parsedComponents.Component;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import static tools.dscode.common.domoperations.DomChecks.endsWithNormalized;
import static tools.dscode.common.domoperations.DomChecks.equalsNormalized;
import static tools.dscode.common.domoperations.DomChecks.evalTextToBool;
import static tools.dscode.common.domoperations.DomChecks.hasAny;
import static tools.dscode.common.domoperations.DomChecks.hasValue;
import static tools.dscode.common.domoperations.DomChecks.isBlank;
import static tools.dscode.common.domoperations.DomChecks.isTruthy;
import static tools.dscode.common.domoperations.DomChecks.matchesNormalized;
import static tools.dscode.common.domoperations.DomChecks.startsWithNormalized;


public class ParsedAssertions {


    public static void executeAssertions(PhraseData phraseData) {

        Component component1 = phraseData.components.getFirst();
        Component component2 = phraseData.components.size() < 2 ? null : phraseData.components.get(1);
        boolean anyTrue = phraseData.selectionType.equals("any");
        System.out.println("@@phraseData.assertion:: " + phraseData.assertion);
        DomChecks.CheckResult result;
        switch (phraseData.assertion) {
            case String s when s.contains("equal") -> {
                result = equalsNormalized(component1.getValue(), component2.getValue());
            }
            case String s when s.contains("start with") -> {
                result = startsWithNormalized(component1.getValue(), component2.getValue());
            }
            case String s when s.contains("end with") -> {
                result = endsWithNormalized(component1.getValue(), component2.getValue());
            }
            case String s when s.contains("match") -> {
                result = matchesNormalized(component1.getValue(), String.valueOf(component2.getValue()));
            }
            case String s when s.contains("displayed") -> {
                result = hasAny(phraseData.wrappedElements.stream().map(w -> w.element).toList());
            }
            case String s when s.contains("has value") -> {
                result = hasValue(phraseData.components, anyTrue);
            }
            case String s when s.contains("isBlank") -> {
                result = isBlank(phraseData.components, anyTrue);
            }
            default -> {
                if (!phraseData.wrappedElements.isEmpty()) {
                    result = hasAny(phraseData.wrappedElements.stream().map(w -> w.element).toList());
                } else if (!phraseData.components.isEmpty()) {
                    result = isTruthy(phraseData.getAllPhraseValues());
                } else {
                    try {
                        result = evalTextToBool(phraseData.body);
                    }
                    catch (Exception e) {
                        System.out.println("Failed default evaluation of '" + phraseData.body + "'");
                        throw new IllegalArgumentException("Unsupported assertion: " + phraseData.assertion);
                    }
                }
            }

        }


        boolean passed = (!phraseData.hasNot == result.result());

        if(phraseData.phraseType.equals(PhraseData.PhraseType.ASSERTION)) {
            if (!passed) {
                if (phraseData.assertionType.equals("ensure"))
                    throw new RuntimeException(result.description());
                else
                    throw new SoftRuntimeException(result.description());
            }
        }
        else
        {
            System.out.println("@@passed: " + passed);
            phraseData.phraseConditionalMode = passed ? 1 : -1;
            System.out.println("@@phraseData.phraseConditionalMode3: " + phraseData.phraseConditionalMode);

        }

    }
}



