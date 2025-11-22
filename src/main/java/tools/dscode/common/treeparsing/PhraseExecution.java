package tools.dscode.common.treeparsing;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.XPathChainResult;
import tools.dscode.common.domoperations.XPathData;
import tools.dscode.common.domoperations.XPathyRegistry;

import java.awt.*;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities;
import static tools.dscode.common.domoperations.ParsedActions.executeAction;
import static tools.dscode.common.domoperations.ParsedAssertions.executeAssertions;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.domoperations.SeleniumUtils.waitSeconds;
import static tools.dscode.common.domoperations.XPathChainResolver.resolveXPathChain;
import static tools.dscode.common.domoperations.XPathyMini.applyAttrOp;
import static tools.dscode.common.domoperations.XPathyMini.applyTextOp;
import static tools.dscode.common.domoperations.XPathyRegistry.getHtmlTypes;
import static tools.dscode.common.domoperations.XPathyRegistry.orAll;
import static tools.dscode.common.domoperations.XPathyUtils.afterOf;
import static tools.dscode.common.domoperations.XPathyUtils.beforeOf;
import static tools.dscode.common.domoperations.XPathyUtils.everyNth;
import static tools.dscode.common.domoperations.XPathyUtils.inBetweenOf;
import static tools.dscode.common.domoperations.XPathyUtils.insideOf;
import static tools.dscode.common.domoperations.XPathyUtils.refine;
import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;

public class PhraseExecution {
    List<XPathData> contextXPathDataList = new ArrayList<>();
    List<XPathData> executionXPathDataList = new ArrayList<>();




    public static List<XPathData> updateXPathData(List<XPathData> inputList, XPathData newXPathData) {
        printDebug("@@##updateXPathData: " + newXPathData);
        List<XPathData> returnList = new ArrayList<>();
        XPathData lastXPathData = inputList.isEmpty() ? null : inputList.getLast();
        printDebug("@@##lastXPathData: " + lastXPathData);
        if (lastXPathData == null) {
            returnList.add(newXPathData);
        } else {
            printDebug("@@##lastXPathData.isFrom: " + lastXPathData.isFrom());

            if (lastXPathData.isFrom()) {
                returnList.addAll(inputList);
                returnList.add(newXPathData);
            } else {
                returnList.addAll(new ArrayList<>(inputList.subList(0, inputList.size() - 1)));
                returnList.add(new XPathData(newXPathData, refine(lastXPathData.xPathy(), newXPathData.xPathy())));
            }
        }
        return returnList;
    }

    public void mergeXPathDataList(List<XPathData> passedXPathDataList) {
        printDebug("@@##mergeXPathDataList: " + this.text);
        printDebug("@@##newContext: " + newContext);
        printDebug("@@##phraseType: " + phraseType);
        printDebug("@@##passedXPathDataList: " + passedXPathDataList);
        if (newContext) {
            this.contextXPathDataList = new ArrayList<>();
            if (phraseType == PhraseType.CONTEXT)
                this.contextXPathDataList.add(new XPathData(this));
            return;
        }
        if (phraseType != PhraseType.CONTEXT) {
            this.contextXPathDataList.addAll(passedXPathDataList);
            return;
        }
        this.contextXPathDataList.addAll(updateXPathData(passedXPathDataList, new XPathData(this)));
    }



    String text;
    public final MatchNode phraseNode;
    public boolean newContext;
    public String context;
    public List<ElementMatch> elements;
    //    XPathy contextXPathy;
    //    XPathy executionXPathy;
    XPathy fullXPathy;
    String conjunction;
    String termination;
    String action;
    String assertion;
    String assertionType;
    public List<Component> components;
    public List<PhraseExecution> executedPhrases = new ArrayList<>();

    public PhraseType phraseType;

    public enum PhraseType {
        INITIAL, CONTEXT, ACTION, ASSERTION
    }

    public static PhraseExecution initiateFirstPhraseExecution() {
        return new PhraseExecution();
    }

//    public static PhraseExecution initiateFirstPhraseExecution(PhraseExecution passedPhraseExecution) {
//        PhraseExecution pe = new PhraseExecution(passedPhraseExecution);
//        return pe;
//    }

    public PhraseExecution initiateNextPhraseExecution(MatchNode phraseNode) {
        printDebug("@@##initiateNextPhraseExecution: " + phraseType + " : " + phraseNode.modifiedText());
        PhraseExecution pe = new PhraseExecution(phraseNode);
        if (pe.phraseType != null && !pe.phraseType.equals(PhraseType.INITIAL)) {
            pe.executedPhrases.addAll(executedPhrases);
            pe.mergeXPathDataList(contextXPathDataList);
        }
        if (pe.phraseType.equals(PhraseType.ASSERTION) || pe.phraseType.equals(PhraseType.ACTION))
            pe.runPhrase();

        return pe;
    }

//    private PhraseExecution(PhraseExecution passedPhraseExecution) {
//        phraseNode = passedPhraseExecution == null ? null : passedPhraseExecution.phraseNode;
//        phraseType = PhraseType.INITIAL;
//        termination = "";
//    }

    private PhraseExecution() {
        phraseNode = null;
        phraseType = PhraseType.INITIAL;
        termination = "";
    }
    private PhraseExecution(MatchNode phraseNode) {
        components = phraseNode.getOrderedChildren("elementMatch", "valueMatch").stream().map(m -> {
            if (m.name().equals("valueMatch"))
                return new ValueMatch(m);
            ElementMatch newElementMatch = new ElementMatch(m);
            newElementMatch.parentPhrase = this;
            return newElementMatch;
        }).toList();
        elements = getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
        this.phraseNode = phraseNode;
        text = phraseNode.toString();
        printDebug("@@##text----: " + text);
        printDebug("@@##phraseNode.localStateBoolean(\"newContext\"): " + phraseNode.localStateBoolean("newContext"));
        newContext = phraseNode.localStateBoolean("newContext");
        conjunction = phraseNode.getStringFromLocalState("conjunction");
        termination =   phraseNode.getStringFromLocalState("termination");

        context = phraseNode.getStringFromLocalState("context");
        printDebug("@@##context2: " + context);

        if (!context.isBlank()) {
//            List<ElementMatch> elements = getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
            phraseType = PhraseType.CONTEXT;
//            contextXPathy = getXPathyContext(context, elements);
            //            contextElementMatch = new ElementMatch(phraseNode.getChild("elementMatch"));
        } else {
            action = phraseNode.getChild("action").toString();
            if (action != null) {
                phraseType = PhraseType.ACTION;
            } else {
                assertionType = phraseNode.getStringFromLocalState("assertionType");
                if (assertionType != null) {
                    assertion = phraseNode.getStringFromLocalState("assertion");
                    phraseType = PhraseType.ASSERTION;
                }
            }
        }


    }

    public static XPathy getXPathyContext(String context, List<ElementMatch> elements) {
        printDebug("@@##getXPathyContext: " + context);
        printDebug("@@##elements: " + elements);
        if(elements.isEmpty()) return null;
        XPathy xPathy = elements.getFirst().xPathy;
        return switch (context.toLowerCase()) {
            case String s when s.startsWith("from") -> xPathy;
            case String s when s.startsWith("in") -> insideOf(xPathy);
            case String s when s.startsWith("after") -> afterOf(xPathy);
            case String s when s.startsWith("before") -> beforeOf(xPathy);
            case String s when s.startsWith("between") -> inBetweenOf(xPathy, elements.get(1).xPathy);
            default -> null;
        };
    }

    public List<Component> getNextComponents(int position, String... nodeNames) {
        List<String> names = Arrays.asList(nodeNames);
        return components.stream().filter(c -> c.position > position && (names.isEmpty() || names.contains(c.name))).toList();
    }

    public List<Component> getPreviousComponents(int position, String... nodeNames) {
        List<String> names = Arrays.asList(nodeNames);
        return components.stream()
                .filter(c -> c.position < position && (names.isEmpty() || names.contains(c.name)))
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(ArrayList::new),
                        list -> {
                            Collections.reverse(list);
                            return list;
                        }
                ));
    }

    private final LifecycleManager lifecycle = new LifecycleManager();

    public void runPhrase() {
        waitSeconds(1);
        lifecycle.fire(Phase.BEFORE_DOM_LOAD_CHECK);
        ChromiumDriver driver = getBrowser("CHROME");
        waitForPhraseEntities(driver, this);
        waitMilliseconds(300);
        lifecycle.fire(Phase.BEFORE_DOM_INTERACTION);

        if (phraseType.equals(PhraseType.ASSERTION)) {
            executeAssertions(driver, this);
        } else if (phraseType.equals(PhraseType.ACTION)) {
            executeAction(driver, this);
        }
    }


    public static abstract class Component {
        public final int position;
        public final String name;

        public Component(MatchNode matchNode) {
            this.name = matchNode.name();
            this.position = matchNode.position;
        }

        public Object getValue(WebDriver driver) {
            if (this instanceof ElementMatch elementMatch1) {
                WebElement element = driver.findElement(elementMatch1.xPathy.getLocator());
                return element.getTagName().equals("input") ? element.getAttribute("value") : element.getText();
            } else {
                return ((ValueMatch) this).value;
            }
        }
    }


    public static class ValueMatch extends Component {
        public String value;
        public String unit;

        public ValueMatch(MatchNode valueNode) {
            super(valueNode);
            this.value = valueNode.getStringFromLocalState("value");
            this.unit = valueNode.getStringFromLocalState("unit");
        }
    }


    public static class ElementMatch extends Component {
        String text;
        String category;
        public String selectionType;
        String elementPosition;
        Attribute attribute;
        public XPathy xPathy;
        ElementType elementType;
        public XPathChainResult matchedElements;
        public Set<XPathyRegistry.HtmlType> htmlTypes;
        public PhraseExecution parentPhrase;
        public String toString() {
            return   (selectionType.isEmpty() ? "" : selectionType+ " ") +  (elementPosition.isEmpty() ? "" : elementPosition+ " ") + (text.isEmpty() ? "" :"'" + text + "' ") + category + "\n"+ xPathy.getXpath();
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

        public List<XPathData> getElementXPathy() {
            return updateXPathData(parentPhrase.contextXPathDataList, new XPathData(this));
        }

        public ElementMatch(MatchNode elementNode) {
            super(elementNode);

            this.text = elementNode.getStringFromLocalState("text");
            this.category = elementNode.getStringFromLocalState("type");
            this.elementPosition = elementNode.getStringFromLocalState("elementPosition");
            this.selectionType = elementNode.getStringFromLocalState("selectionType");
//            if(selectionType.isEmpty())
//                selectionType = "single";
            for (ElementType et : ElementType.values()) {
                if (this.category.startsWith(et.name())) {
                    this.elementType = et;
                    break;
                }
            }
            if (elementType == null)
                elementType = ElementType.HTML;

            htmlTypes = getHtmlTypes(category);


            if (this.elementType != ElementType.HTML)
                return;


            XPathyRegistry.Op textOp = text.isBlank() ? null : XPathyRegistry.Op.EQUALS;
            xPathy = orAll(category, text, textOp).orElse(XPathy.from(Tag.any));
            MatchNode predicateNode = (MatchNode) elementNode.getFromGlobalState((String) elementNode.getFromLocalState("predicate"));

            if (predicateNode != null) {
                this.attribute = new Attribute((String) elementNode.getFromLocalState("attrName"), (String) predicateNode.getFromLocalState("predicateType"), (String) predicateNode.getFromLocalState("predicateVal"));

                XPathyRegistry.Op op = switch (attribute.predicateType) {
                    case null -> null;
                    case String s when s.isBlank() -> null;
                    case String s when s.startsWith("equal") -> XPathyRegistry.Op.EQUALS;
                    case String s when s.startsWith("contain") -> XPathyRegistry.Op.CONTAINS;
                    case String s when s.startsWith("start") -> XPathyRegistry.Op.STARTS_WITH;
                    default -> null;
                };
                if (attribute.attrName.equals("TEXT"))
                    xPathy = applyTextOp(xPathy, op, text);
                else
                    xPathy = applyAttrOp(xPathy, com.xpathy.Attribute.custom(attribute.attrName), op, attribute.predicateVal);
            }

            if (!selectionType.isEmpty()) {

            }

            if (elementPosition.isEmpty())
                elementPosition = "1";
            if (elementPosition.equals("last")) {
                xPathy = xPathy.last();
            } else {
                elementIndex = Integer.parseInt(elementPosition);
                if (selectionType.isEmpty())
                    xPathy = xPathy.nth(elementIndex);
                else
                    xPathy = everyNth(xPathy, elementIndex);
            }


        }

        public XPathChainResult findWebElements(WebDriver driver) {
            List<XPathData> xpathDataList = getElementXPathy();
            System.out.println(this);
            System.out.println("Finding Elements at: " + xpathDataList);
            matchedElements = new XPathChainResult(driver, xpathDataList);
            return matchedElements;
        }
    }


    public static class Attribute {
        String attrName;
        String predicateType;
        String predicateVal;

        public Attribute(String attrName, String predicateType, String predicateVal) {
            this.attrName = attrName == null || attrName.isBlank() ? "TEXT" : attrName;
            this.predicateType = predicateType;
            this.predicateVal = predicateVal;
        }
    }


}

