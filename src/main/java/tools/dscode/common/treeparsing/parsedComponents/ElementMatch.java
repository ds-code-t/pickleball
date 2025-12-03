package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.WrappedContext;
import tools.dscode.common.domoperations.WrappedWebElement;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.xpathcomponents.XPathChainResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tools.dscode.common.domoperations.SeleniumUtils.wrapContext;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineAnd;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.prettyPrintXPath;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.applyAttrOp;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.applyTextOp;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.everyNth;

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

//    public List<XPathy> elementXpathyList;

    //    public List<XPathy> getElementXPathyList() {
//        if (elementXpathyList == null) {
//            elementXpathyList = new ArrayList<>();
//            List<XPathy> contextList = parentPhrase.getContextXpathyList();
//            PhraseData lastPhrase = parentPhrase.usedContextPhrases.isEmpty() ? null : parentPhrase.usedContextPhrases.getLast();
//            elementXpathyList.addAll(contextList);
//            if (elementXpathyList.isEmpty() || lastPhrase == null || lastPhrase.isContext) {
//                elementXpathyList.add(xPathy);
//            } else {
//                XPathy mergedXpathy = refine(elementXpathyList.getLast(), xPathy);
//                elementXpathyList.set(elementXpathyList.size() - 1, mergedXpathy);
//            }
//        }
//        if (elementPosition.isEmpty() && selectionType.isEmpty()) {
//            XPathy singleMatch = elementXpathyList.getLast().nth(1);
//            elementXpathyList.set(elementXpathyList.size() - 1, singleMatch);
//        }
//        return elementXpathyList;
//    }
    public ExecutionDictionary.Op textOp;

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

        textOp = text.isBlank() ? ExecutionDictionary.Op.DEFAULT : ExecutionDictionary.Op.EQUALS;
        categoryFlags.addAll(getExecutionDictionary().getResolvedCategoryFlags(category));

        if (!categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) {
            ExecutionDictionary.CategoryResolution categoryResolution = getExecutionDictionary().andThenOrWithFlags(category, text, textOp);
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
    }

    private List<PhraseData> phraseContextList;

    public List<PhraseData> getPhraseContextList() {
        if (phraseContextList == null)
            phraseContextList = parentPhrase.processContextList();
        return phraseContextList;
    }

    private XPathy elementTerminalXPath;

    public XPathy getTerminalXPathy() {
        if (elementTerminalXPath == null)
            elementTerminalXPath = (elementPosition.isEmpty() && selectionType.isEmpty()) ? xPathy.nth(1) : xPathy;
        return elementTerminalXPath;
    }

    public WrappedContext getWrappedContext(WrappedContext currentWrappedContext) {
        System.out.println("@@getWrappedContext:: " + this);
        System.out.println("@@category:: " + category);
        SearchContext searchContext = getExecutionDictionary().applyContextBuilder(category, text, textOp, currentWrappedContext);
        if(searchContext instanceof  WrappedContext)
            return (WrappedContext) searchContext;
        return wrapContext(searchContext);
    }

    public XPathChainResult findWebElements(WebDriver driver) {

//        WrappedContext currentWrappedContext = new WrappedWebElement(driver);
        List<PhraseData> contextList = getPhraseContextList();
        List<XPathy> xPathyList = new ArrayList<>();
        System.out.println("@@CurrentPhrase-- " + parentPhrase);
        for (int j = 0; j < contextList.size(); j++) {
            PhraseData phraseData = contextList.get(j);
            System.out.println("@@phraseData-- " + phraseData);
            System.out.println("@@phraseData.categoryFlags-- " + phraseData.categoryFlags);
            if (phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) {
                System.out.println("@@phraseData-- 1 " + xPathyList);
                if (!xPathyList.isEmpty()) {
                    XPathy combinedXPathy = combineAnd(xPathyList);
                    parentPhrase.setCurrentWrappedContext(parentPhrase.getCurrentWrappedContext().findElement(combinedXPathy.getLocator()));
                    xPathyList.clear();
                }
                System.out.println("@@phraseData-- 2a " + parentPhrase.getCurrentWrappedContext());
                parentPhrase.setCurrentWrappedContext(phraseData.elementMatch.getWrappedContext(parentPhrase.getCurrentWrappedContext()));
                System.out.println("@@phraseData-- 2b " + parentPhrase.getCurrentWrappedContext());
            } else {
                xPathyList.add(phraseData.contextXPathy);
                System.out.println("@@phraseData-- 3 " + xPathyList);
            }
        }
        xPathyList.add(getTerminalXPathy());
        System.out.println("@@phraseData-- 3b " + getTerminalXPathy());
        System.out.println("@@phraseData-- 3c " + xPathyList);
        XPathy combinedXPathy = combineAnd(xPathyList);
        System.out.println("\n\n@@prettyPrintXPath-combinedXPathy ");
        System.out.println(prettyPrintXPath(combinedXPathy));
        System.out.println("\n---\n");

        matchedElements = new XPathChainResult(parentPhrase.getCurrentWrappedContext(), combinedXPathy);
        System.out.println("@@matchedElements: "  + matchedElements);
        return matchedElements;
    }




}