package tools.dscode.common.domoperations;


import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.PhraseExecution;
import tools.dscode.common.treeparsing.PhraseExecution.*;

import java.util.List;

import static tools.dscode.common.domoperations.DomChecks.equalsNormalized;
import static tools.dscode.common.domoperations.DomChecks.hasAny;
import static tools.dscode.common.domoperations.HumanInteractions.clearAndType;
import static tools.dscode.common.domoperations.HumanInteractions.click;
import static tools.dscode.common.domoperations.HumanInteractions.contextClick;
import static tools.dscode.common.domoperations.HumanInteractions.doubleClick;
import static tools.dscode.common.domoperations.HumanInteractions.typeText;
import static tools.dscode.common.domoperations.HumanInteractions.wheelScrollBy;
import static tools.dscode.common.domoperations.SeleniumUtils.explicitWait;


public class ParsedAssertions {


    public static void executeAssertions(ChromiumDriver driver, PhraseExecution phraseExecution) {

        boolean isTrue = !phraseExecution.phraseNode.getChild("not").modifiedText().equals("not");
        MatchNode assertionNode = phraseExecution.phraseNode.getChild("assertion");
        String assertion = assertionNode == null ? "equals" : assertionNode.modifiedText();
        if (assertion.isEmpty())
            assertion = "equal";
        String assertionType = phraseExecution.phraseNode.getChild("assertionType").modifiedText();

        Component component1 = phraseExecution.components.getFirst();
        Component component2 = phraseExecution.components.size() < 2 ? null : phraseExecution.components.get(1);

        DomChecks.CheckResult result;
        switch (assertion) {
            case String s when s.contains("equal") -> {
                result = equalsNormalized(component1.getValue(driver), component2.getValue(driver));
            }
            case String s when s.contains("displayed") -> {
                result = hasAny(driver, ((ElementMatch) component1).xPathy);
            }
            default -> {
                throw new IllegalArgumentException("Unsupported assertion: " + assertion);
            }

        }

        boolean passed = (isTrue == result.result());

        if(!passed) {
            if(assertionType.equals("ensure"))
                throw new RuntimeException(result.description());
            else
                throw new SoftRuntimeException(result.description());
        }

    }
}



