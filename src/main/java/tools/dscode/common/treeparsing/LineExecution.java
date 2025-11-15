package tools.dscode.common.treeparsing;

import com.xpathy.XPathy;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumDriver;

import java.util.List;

import static tools.dscode.common.domoperations.HumanInteractions.click;

public class LineExecution extends LineData {

    public LineExecution(MatchNode lineNode) {
        super(lineNode);
    }

    public boolean execute(ChromiumDriver driver) {
        for (Phrase phrase : phrases) {
            System.out.println("@@phrase: " + phrase.text);
            System.out.println("@@phrase.type: " + phrase.type);
            System.out.println("@@Phrase.PhraseType.ACTION: " + Phrase.PhraseType.ACTION);
            if (phrase.type.equals(Phrase.PhraseType.ACTION)) {
                MatchNode actionNode = phrase.phraseNode.getChild("action");
                System.out.println("@@actionNode: " + actionNode);
                String action = actionNode.modifiedText();
                System.out.println("@@action: " + action);
                List<MatchNode> nextNodes = actionNode.getNextSiblings("value", "element");
                List<MatchNode> previousNodes = actionNode.getPreviousSiblings("value", "element");
                System.out.println("@@actionNode.nextSiblin: " + actionNode.nextSibling);
                System.out.println("@@actionNode.nextSiblin.name: " + actionNode.nextSibling.name());
                System.out.println("@@nextNodes: " + nextNodes);
                System.out.println("@@nextNodes size : " + nextNodes.size());
                System.out.println("@@action: " + action);
                switch (action.toLowerCase()) {
                    case "click" -> click(driver, getFirstElement(driver, nextNodes));
                    case "banana" -> System.out.println("It's a banana!");
                    default -> System.out.println("Something else: " + action);
                }
            }
        }

        return true;
    }

    public static Element getNextElement(List<MatchNode> nextNodes) {
        MatchNode nextElementNode = nextNodes.stream()
                .filter(matchNode -> "element".equals(matchNode.name())).findFirst().orElse(null);
        return new Element(nextElementNode);
    }
    public static XPathy getNextXpathy(List<MatchNode> nextNodes) {
        Element element = getNextElement(nextNodes);
        System.out.println("@@element--: " + element);
        System.out.println("@@element.xPathy--: " + element.xPathy);
        System.out.println("@@element.xPathy--: " + element.xPathy.getLocator());
        return element.xPathy;
    }

    public static WebElement getFirstElement(ChromiumDriver driver, List<MatchNode> nextNodes) {
        return driver.findElement(getNextXpathy(nextNodes).toBy());
    }
}
