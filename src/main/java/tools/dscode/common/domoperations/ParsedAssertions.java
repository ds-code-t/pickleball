package tools.dscode.common.domoperations;


import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.Component;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import static tools.dscode.common.domoperations.DomChecks.equalsNormalized;
import static tools.dscode.common.domoperations.DomChecks.hasAny;


public class ParsedAssertions {


    public static void executeAssertions(WebDriver driver, PhraseData phraseData) {
        System.out.println("@@executeAssertions: " + phraseData);
//        boolean isTrue = !phraseData.phraseNode.getChild("not").modifiedText().equals("not");
//        MatchNode assertionNode = phraseData.phraseNode.getChild("assertion");
//        String assertion = assertionNode == null ? "equals" : assertionNode.modifiedText();
//        if (assertion.isEmpty())
//            assertion = "equal";
////        phraseNode.getStringFromLocalState("conjunction");
//        String assertionType = phraseData.phraseNode.getChild("assertionType").modifiedText();

        System.out.println("@@components.size(): " + phraseData.components.size() + "");
        System.out.println("@@elements.size(): " + phraseData.elements.size() + "");
        Component component1 = phraseData.components.getFirst();
        Component component2 = phraseData.components.size() < 2 ? null : phraseData.components.get(1);

        System.out.println("@@phraseData.assertion: " + phraseData.assertion);
        DomChecks.CheckResult result;
        switch (phraseData.assertion) {
            case String s when s.contains("equal") -> {
                System.out.println("@@component1.getValue(driver): " + component1.getValue(driver));
                System.out.println("@@component2.getValue(driver): " + component2.getValue(driver));
                result = equalsNormalized(component1.getValue(driver), component2.getValue(driver));
            }
            case String s when s.contains("displayed") -> {
                result = hasAny(driver, phraseData.elementMatch.xPathy);
            }
            default -> {
                throw new IllegalArgumentException("Unsupported assertion: " + phraseData.assertion);
            }

        }

        System.out.println("@@result: " + result);
        System.out.println("@@phraseData.assertionTyp: " + phraseData.assertionType);


        boolean passed = (!phraseData.hasNot == result.result());
        System.out.println("@@passed: " + passed);
        if(!passed) {
            if(phraseData.assertionType.equals("ensure"))
                throw new RuntimeException(result.description());
            else
                throw new SoftRuntimeException(result.description());
        }

    }
}



