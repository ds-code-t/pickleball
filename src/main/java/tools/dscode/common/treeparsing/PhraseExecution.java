package tools.dscode.common.treeparsing;

import com.xpathy.Tag;
import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.XPathyRegistry;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities;
import static tools.dscode.common.domoperations.ParsedActions.executeAction;
import static tools.dscode.common.domoperations.ParsedAssertions.executeAssertions;
import static tools.dscode.common.domoperations.SeleniumUtils.explicitWait;
import static tools.dscode.common.domoperations.XPathyMini.applyAttrOp;
import static tools.dscode.common.domoperations.XPathyMini.applyTextOp;
import static tools.dscode.common.domoperations.XPathyRegistry.orAll;
import static tools.dscode.common.domoperations.XPathyUtils.afterOf;
import static tools.dscode.common.domoperations.XPathyUtils.beforeOf;
import static tools.dscode.common.domoperations.XPathyUtils.inBetweenOf;
import static tools.dscode.common.domoperations.XPathyUtils.insideOf;
import static tools.dscode.common.domoperations.XPathyUtils.refine;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;

public class PhraseExecution {
    String text;
    public final MatchNode phraseNode;
    String context;
    XPathy contextXPathy;
    XPathy passedXPathy;
    String conjunction;
    String termination;
    String action;
    String assertion;
    String assertionType;
    boolean newContext = true;
    public List<Component> components;
    public List<PhraseExecution> executedPhrases = new ArrayList<>();

    public PhraseType phraseType;

    public enum PhraseType {
        INITIAL, CONTEXT, ACTION, ASSERTION
    }

    public static PhraseExecution initiateFirstPhraseExecution(MatchNode phraseNode) {
        PhraseExecution pe = new PhraseExecution(phraseNode);
        pe.phraseType = PhraseType.INITIAL;
        return pe;
    }

    public static PhraseExecution initiateFirstPhraseExecution(PhraseExecution passedPhraseExecution) {
        PhraseExecution pe = new PhraseExecution(passedPhraseExecution);
        return pe;
    }

    public PhraseExecution initiateNextPhraseExecution(MatchNode phraseNode) {
        System.out.println("@@initiateNextPhraseExecution: " + phraseNode.modifiedText());
        System.out.println("@@phraseType-initial : " + text);
        System.out.println("@@phraseType-initialphraseType  : " + phraseType);
        PhraseExecution pe = new PhraseExecution(phraseNode);
        if (phraseType != null && !phraseType.equals(PhraseType.INITIAL)) {
            pe.executedPhrases.addAll(executedPhrases);
            pe.newContext = !termination.equals(",") || Character.isUpperCase(pe.context.charAt(0));
            if (!pe.newContext) {
                if (pe.phraseType.equals(PhraseType.CONTEXT)) {
                    pe.passedXPathy = refine(passedXPathy, pe.contextXPathy);
                } else {
                    pe.passedXPathy = passedXPathy;
                }
            } else {
                pe.passedXPathy = pe.contextXPathy;
            }
        }
        System.out.println("@@pe.text: " + pe.text);
        System.out.println("@@pe.phraseType: " + pe.phraseType);
        if (pe.phraseType.equals(PhraseType.ASSERTION) || pe.phraseType.equals(PhraseType.ACTION))
            pe.runPhrase();

        return pe;
    }

    private PhraseExecution(PhraseExecution passedPhraseExecution) {
        phraseNode = passedPhraseExecution == null ? null : passedPhraseExecution.phraseNode;
        phraseType = PhraseType.INITIAL;
        termination = "";
    }

    private PhraseExecution(MatchNode phraseNode) {
//        this.previousPhraseNode = previousPhraseNode;
        this.phraseNode = phraseNode;
        text = phraseNode.toString();
        System.out.println("@@PhraseExecution-text: " + text);

        conjunction = phraseNode.resolvedGroupText("conjunctions");
        termination = phraseNode.resolvedGroupText("termination");

        context = phraseNode.resolvedGroupText("context");
        if (context == null || context.isEmpty())
            context = " ";

        if (!context.isBlank()) {
            List<ElementMatch> elements = getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
            XPathy xPathy = elements.getFirst().xPathy;
            phraseType = PhraseType.CONTEXT;
            contextXPathy = switch (context) {
                case String s when s.startsWith("from") -> insideOf(xPathy);
                case String s when s.startsWith("in") -> insideOf(xPathy);
                case String s when s.startsWith("after") -> afterOf(xPathy);
                case String s when s.startsWith("before") -> beforeOf(xPathy);
                case String s when s.startsWith("between") -> inBetweenOf(xPathy, elements.get(1).xPathy);
                default -> null;
            };


//            contextElementMatch = new ElementMatch(phraseNode.getChild("elementMatch"));
        } else {
            action = phraseNode.getChild("action").toString();
            if (action != null) {
                phraseType = PhraseType.ACTION;
            } else {
                assertionType = phraseNode.resolvedGroupText("assertionType");
                if (assertionType != null) {
                    assertion = phraseNode.resolvedGroupText("assertion");
                    phraseType = PhraseType.ASSERTION;
                }
            }
        }

        System.out.println("@@PhraseExecutipn-text:  " + text);
        System.out.println("@@action:  " + action);
        System.out.println("@@phraseType:  " + phraseType);
        System.out.println("@@context:  " + context);
        System.out.println("@@assertionType:  " + assertionType);

        components = phraseNode.getOrderedChildren("elementMatch", "valueMatch").stream().map(m -> {
            if (m.name().equals("valueMatch"))
                return new ValueMatch(m);
            return new ElementMatch(m);
        }).toList();

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
        lifecycle.fire(Phase.BEFORE_DOM_LOAD_CHECK);
        ChromiumDriver driver = getBrowser("CHROME");
        waitForPhraseEntities(driver, this);
        explicitWait(3);
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
            this.value = valueNode.resolvedGroupText("value");
            this.unit = valueNode.resolvedGroupText("unit");
        }

    }


    public static class ElementMatch extends Component {
        String text;
        String type;
        String selectionType;
        String elementPosition;
        Attribute attribute;
        public XPathy xPathy;
        ElementType elementType;
        public List<WebElement> matchedElements;

        public enum ElementType {
            HTML, ALERT, BROWSER, BROWSER_TAB, URL, VALUE
        }

        public enum SelectType {
            ANY, EVERY, FIRST, LAST
        }

        int elementIndex;

        public ElementMatch(MatchNode elementNode) {
            super(elementNode);
            this.text = elementNode.resolvedGroupText("text");
            this.type = elementNode.resolvedGroupText("type");
            this.elementPosition = elementNode.resolvedGroupText("elementPosition");
            this.selectionType = elementNode.resolvedGroupText("selectionType");

            try {
                this.elementType = ElementType.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                this.elementType = ElementType.HTML;
            }

            if (!this.elementType.equals(ElementType.HTML))
                return;

            XPathyRegistry.Op textOp = text == null ? null : XPathyRegistry.Op.EQUALS;
            xPathy = orAll(type, text, textOp).orElse(XPathy.from(Tag.any));
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
                System.out.println("@@interXPathy: " + xPathy + "  -op: " + op + "  -attribute: " + attribute + "");
                if (attribute.attrName.equals("TEXT"))
                    xPathy = applyTextOp(xPathy, op, text);
                else
                    xPathy = applyAttrOp(xPathy, com.xpathy.Attribute.custom(attribute.attrName), op, attribute.predicateVal);
            }

            if (elementPosition != null) {
                if (elementPosition.equals("last"))
                    xPathy = xPathy.last();
                else {
                    elementIndex = Integer.parseInt(elementPosition);
                    xPathy = xPathy.nth(elementIndex);
                }
            }
        }

        public List<WebElement> findWebElements(WebDriver driver) {
            matchedElements = driver.findElements(xPathy.toBy());
            return matchedElements;
        }

        public List<WebElement> findWebElements(WebElement element) {
            matchedElements = element.findElements(xPathy.toBy());
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

