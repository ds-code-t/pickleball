package tools.dscode.common.domoperations;


import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.Component;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.DomChecks.endsWithNormalized;
import static tools.dscode.common.domoperations.DomChecks.equalsNormalized;
import static tools.dscode.common.domoperations.DomChecks.evalTextToBool;
import static tools.dscode.common.domoperations.DomChecks.hasAny;
import static tools.dscode.common.domoperations.DomChecks.isTruthy;
import static tools.dscode.common.domoperations.DomChecks.matchesNormalized;
import static tools.dscode.common.domoperations.DomChecks.startsWithNormalized;
import static tools.dscode.common.evaluations.AviatorUtil.evalToBoolean;
import static tools.dscode.common.evaluations.AviatorUtil.isStringTruthy;


public class ParsedAssertions {


    public static void executeAssertions(PhraseData phraseData) {

//        boolean isTrue = !phraseData.phraseNode.getChild("not").modifiedText().equals("not");
//        MatchNode assertionNode = phraseData.phraseNode.getChild("assertion");
//        String assertion = assertionNode == null ? "equals" : assertionNode.modifiedText();
//        if (assertion.isEmpty())
//            assertion = "equal";
////        phraseNode.getStringFromLocalState("conjunction");
//        String assertionType = phraseData.phraseNode.getChild("assertionType").modifiedText();


        Component component1 = phraseData.components.getFirst();
        Component component2 = phraseData.components.size() < 2 ? null : phraseData.components.get(1);


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

        if(phraseData.conditional.isEmpty()) {
            if (!passed) {
                if (phraseData.assertionType.equals("ensure"))
                    throw new RuntimeException(result.description());
                else
                    throw new SoftRuntimeException(result.description());
            }
        }
        else
        {ws
 start here
        }

    }
}



