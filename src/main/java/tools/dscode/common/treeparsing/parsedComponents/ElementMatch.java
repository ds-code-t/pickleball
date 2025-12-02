package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.xpathcomponents.XPathChainResult;
import tools.dscode.common.treeparsing.xpathcomponents.XPathData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyMini.applyAttrOp;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyMini.applyTextOp;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.everyNth;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.refine;

public class ElementMatch extends Component {
    String text;
    String category;
    public String selectionType;
    String elementPosition;
    Attribute attribute;
    public XPathy xPathy;
    ElementMatch.ElementType elementType;
    public XPathChainResult matchedElements;
    //        public Set<XPathyRegistry.HtmlType> htmlTypes;
    public Phrase parentPhrase;

    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();

    public String toString() {
        return (selectionType.isEmpty() ? "" : selectionType + " ") + (elementPosition.isEmpty() ? "" : elementPosition + " ") + (text == null ? "" : "'" + text + "' ") + category + (xPathy == null ? "" : "\n" + xPathy.getXpath());
    }

    public enum ElementType {
        HTML, ALERT, BROWSER, BROWSER_WINDOW, BROWSER_TAB, URL, VALUE;

        @Override
        public String toString() {
            return name().replace('_', ' ');
        }
    }

    public enum SelectType {
        ANY, EVERY, FIRST, LAST
    }

    int elementIndex;

    public List<XPathy> elementXpathyList;

    public List<XPathy> getElementXPathyList() {
        if (elementXpathyList == null) {
            elementXpathyList = new ArrayList<>();
            List<XPathy> contextList = parentPhrase.getContextXpathyList();
            PhraseData lastPhrase = parentPhrase.usedContextPhrases.isEmpty() ? null : parentPhrase.usedContextPhrases.getLast();
            elementXpathyList.addAll(contextList);
            if (elementXpathyList.isEmpty() || lastPhrase == null || lastPhrase.isContext) {
                elementXpathyList.add(xPathy);
            } else {
                XPathy mergedXpathy = refine(elementXpathyList.getLast(), xPathy);
                elementXpathyList.set(elementXpathyList.size() - 1, mergedXpathy);
            }

        }
        if (elementPosition.isEmpty() && selectionType.isEmpty()) {
            XPathy singleMatch = elementXpathyList.getLast().nth(1);
            elementXpathyList.set(elementXpathyList.size() - 1, singleMatch);
        }
        return elementXpathyList;
    }

    public ElementMatch(MatchNode elementNode) {
        super(elementNode);

        this.text = elementNode.getStringFromLocalState("text");
        this.category = elementNode.getStringFromLocalState("type");
        this.elementPosition = elementNode.getStringFromLocalState("elementPosition");
        this.selectionType = elementNode.getStringFromLocalState("selectionType");
//            if(selectionType.isEmpty())
//                selectionType = "single";
        for (ElementMatch.ElementType et : ElementMatch.ElementType.values()) {
            if (this.category.startsWith(et.name())) {
                this.elementType = et;
                break;
            }
        }

        System.out.println("@@ElementMatch:: " + this);
        System.out.println("@@elementType:: " + elementType);
        if (elementType == null)
            elementType = ElementMatch.ElementType.HTML;

        ExecutionDictionary.Op textOp = text.isBlank() ? ExecutionDictionary.Op.DEFAULT : ExecutionDictionary.Op.EQUALS;
        ExecutionDictionary.CategoryResolution categoryResolution = getExecutionDictionary().andThenOrWithFlags(category, text, textOp);
        categoryFlags.addAll(categoryResolution.flags());
        xPathy = categoryResolution.xpath();
        MatchNode predicateNode = (MatchNode) elementNode.getFromGlobalState((String) elementNode.getFromLocalState("predicate"));

        if (predicateNode != null) {
            this.attribute = new Attribute((String) elementNode.getFromLocalState("attrName"), (String) predicateNode.getFromLocalState("predicateType"), (String) predicateNode.getFromLocalState("predicateVal"));

            ExecutionDictionary.Op op = switch (attribute.predicateType) {
                case null -> null;
                case String s when s.isBlank() -> null;
                case String s when s.startsWith("equal") -> ExecutionDictionary.Op.EQUALS;
                case String s when s.startsWith("contain") -> ExecutionDictionary.Op.CONTAINS;
                case String s when s.startsWith("start") -> ExecutionDictionary.Op.STARTS_WITH;
                default -> null;
            };
            if (attribute.attrName.equals("TEXT"))
                xPathy = applyTextOp(xPathy, op, text);
            else
                xPathy = applyAttrOp(xPathy, com.xpathy.Attribute.custom(attribute.attrName), op, attribute.predicateVal);
        }

        if (elementPosition.equals("last")) {
            xPathy = xPathy.last();
        } else if (!elementPosition.isEmpty()) {
            elementIndex = Integer.parseInt(elementPosition);
            if (selectionType.isEmpty()) {
                xPathy = xPathy.nth(elementIndex);
            } else {
                xPathy = everyNth(xPathy, elementIndex);
            }
        }

    }

    public XPathChainResult findWebElements(WebDriver driver) {
        List<XPathy> xpathDataList = getElementXPathyList();
        System.out.println(this);
        System.out.println("\n===============\nFinding Elements for:" + xpathDataList.size() + "\n" + xpathDataList);
        System.out.println("\n---------------\n");
        matchedElements = new XPathChainResult(driver, xpathDataList);
        return matchedElements;
    }
}