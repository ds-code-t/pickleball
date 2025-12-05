package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.applyAttrOp;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.applyTextOp;

public class ElementMatch extends Component {
    public String text;
    public String category;
    public String selectionType;
    public String elementPosition;
    Attribute attribute;
    public XPathy xPathy;
    ElementMatch.ElementType elementType;
    public ContextWrapper contextWrapper;
//    public XPathChainResult matchedElements;
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

    public int elementIndex;



    public List<ElementWrapper> wrappedElements = new ArrayList<>();

    public void findWebElements(WebDriver driver) {
        List<WebElement> elements = contextWrapper.getElements(driver);
        wrappedElements.addAll(elements.stream().map(e -> new ElementWrapper(driver, e, this)).toList());
    }


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

        }


    }

    private List<PhraseData> phraseContextList;

    public List<PhraseData> getPhraseContextList() {
        if (phraseContextList == null)
            phraseContextList = parentPhrase.processContextList();
        return phraseContextList;
    }





}