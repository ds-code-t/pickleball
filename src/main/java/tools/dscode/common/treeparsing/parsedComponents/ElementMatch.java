package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.browseroperations.WindowSwitch;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ElementMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.dscode.common.seleniumextensions.ElementWrapper.getWrappedElements;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.RETURNS_VALUE;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineAnd;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.applyAttrOp;


public class ElementMatch {

    public final String fullText;
    public final int startIndex;
    public final int position;
    public PhraseData parentPhrase;
    public static final String ELEMENT_LABEL_VALUE = "_elementLabelValue";
    public static final String ELEMENT_RETURN_VALUE = "_elementReturnValue";
    public List<TextOp> textOps = new ArrayList<>();
    public String category;
    public String selectionType = "";
    public String elementPosition;
    public List<String> valueTypes;
    public String state;
    public List<Attribute> attributes = new ArrayList<>();
    public XPathy xPathy;
    public Set<ElementType> elementTypes;
    public ElementMatcher elementMatcher;
    public ContextWrapper contextWrapper;
    public List<String> defaultValueKeys = new ArrayList<>(List.of(ELEMENT_RETURN_VALUE, "value", "textContent"));
//    public XPathChainResult matchedElements;
    //        public Set<XPathyRegistry.HtmlType> htmlTypes;

    public ValueWrapper defaultText;
    public ExecutionDictionary.Op defaultTextOp;

    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();

    public String toString() {
        if (previouslyReturnedValues == null)
            return "No Resolved Values , Element: " + fullText;
        return "Resolved Values: " + previouslyReturnedValues + " , Element: " + fullText;
    }

    public List<ValueWrapper> nonHTMLValues = new ArrayList<>();

//    public enum SelectType {
//        ANY, EVERY, FIRST, LAST
//    }

    public int elementIndex;


    protected List<ElementWrapper> wrappedElements = null;

    WebDriver driver;

    protected boolean isPlaceHolder = false;

    public boolean isPlaceHolder() {
        return isPlaceHolder;
    }

    public List<ElementWrapper> findWrappedElements() {


        if (wrappedElements != null) return wrappedElements;
        driver = parentPhrase.getDriver();

        wrappedElements = getWrappedElements(this);
//        try {
//            wrappedElements = getWrappedElements(this);
//        } catch (Throwable t) {
//            if (!selectionType.equals("any") && parentPhrase.phraseType == PhraseData.PhraseType.ACTION) {
//                throw new RuntimeException("Failed to find WebElements for " + this, t);
//            }
//            wrappedElements = new ArrayList<>();
//        }
        parentPhrase.getWrappedElements().addAll(wrappedElements);
        return wrappedElements;
    }


//    public ExecutionDictionary.Op textOp;


    static final Pattern attributePattern = Pattern.compile("^(?<attrName>[a-z][a-z\\s]+)\\s+(?<predicate>.*)$");

    public record TextOp(ValueWrapper text, ExecutionDictionary.Op op) {
        public TextOp(ValueWrapper text, String op) {
            this(text, getOpFromString(op));
        }
    }

    protected ElementMatch(PhraseData phraseData) {

        this.parentPhrase = phraseData;
        isPlaceHolder = true;
        this.startIndex = -1;
        this.position = -1;
        this.fullText = "PlaceHolder";
    }


//    public List<ElementMatch> createElementMatchesFromString(String elementString) {
//        MatchNode returnMatchNode = getNodeDictionary().parse(elementString);
//        returnMatchNode.getOrderedChildren("elementMatch").stream().map(this::getElementMatch).collect(Collectors.toList())
//    }


    public ElementMatch(PhraseData phraseData, MatchNode elementNode) {

        this.fullText = elementNode.getStringFromLocalState("fullText");
        this.parentPhrase = phraseData;
        this.startIndex = elementNode.start;
        this.position = elementNode.position;
        this.state = elementNode.getStringFromLocalState("state");

        this.category = elementNode.getStringFromLocalState("type");

        this.elementPosition = elementNode.getStringFromLocalState("elementPosition");
        this.selectionType = elementNode.getStringFromLocalState("selectionType");

        this.valueTypes = Arrays.stream(elementNode.getStringFromLocalState("valueTypes").split("\\s+")).sorted(Comparator.reverseOrder()).toList();


        if (elementNode.localStateBoolean("text")) {
            textOps.add(new TextOp(elementNode.getValueWrapper("text"), ExecutionDictionary.Op.EQUALS));
        }


        categoryFlags.addAll(getExecutionDictionary().getResolvedCategoryFlags(category));
        this.elementTypes = ElementType.fromString(this.category);


        if (elementNode.localStateBoolean("elPredicate")) {
            String elPredicates = elementNode.getStringFromLocalState("elPredicate");

            for (String elPredicate : elPredicates.split("\\s+")) {
                MatchNode elPredicateNode = elementNode.getMatchNode(elPredicate.trim());
                if (elPredicateNode != null) {
                    textOps.add(new TextOp(elPredicateNode.getValueWrapper("predicateVal"), elPredicateNode.getStringFromLocalState("predicateType")));
                }
            }
        }


        if (!textOps.isEmpty()) {
            defaultText = textOps.getFirst().text;
            defaultTextOp = textOps.getFirst().op;
        }


        if (elementNode.localStateBoolean("atrPredicate")) {
            String atrPredicate = elementNode.getStringFromLocalState("atrPredicate");
            for (String attr : atrPredicate.split("\\bwith\b")) {

                Matcher m = attributePattern.matcher(attr.trim());
                if (m.find()) {
                    String attrName = m.group("attrName");   // named group
                    String predicate = m.group("predicate");   // named group
                    MatchNode predicateNode = elementNode.getMatchNode(predicate.trim());

                    attributes.add(new Attribute(attrName, (String) predicateNode.getFromLocalState("predicateType"), predicateNode.getValueWrapper("predicateVal")));
                } else {
                    throw new RuntimeException("Invalid attribute predicate: " + attr);
                }
            }
        }

        if (elementTypes.contains(ElementType.HTML_TYPE)) {
            if (categoryFlags.contains(ExecutionDictionary.CategoryFlags.IFRAME)) {
                elementTypes.add(ElementType.HTML_IFRAME);
            } else if (categoryFlags.contains(ExecutionDictionary.CategoryFlags.SHADOW_HOST)) {
                elementTypes.add(ElementType.HTML_SHADOW_ROOT);
            } else {
                elementTypes.add(ElementType.HTML_ELEMENT);
                elementTypes.add(RETURNS_VALUE);
            }
        } else if (elementTypes.contains(ElementType.VALUE_TYPE)) {
            nonHTMLValues.add(defaultText);
            elementTypes.add(RETURNS_VALUE);
        }


        if (!elementTypes.contains(ElementType.HTML_TYPE)) {
            return;
        }

//        if (!categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) {
        ExecutionDictionary dict = getExecutionDictionary();
        List<XPathy> elPredictXPaths = new ArrayList<>();


        if (textOps.isEmpty()) {
            ExecutionDictionary.CategoryResolution categoryResolution = dict.andThenOrWithFlags(category, null, null);
            elPredictXPaths.add(categoryResolution.xpath());
        }

        for (TextOp textOp : textOps) {
            ExecutionDictionary.CategoryResolution categoryResolution = dict.andThenOrWithFlags(category, textOp.text, textOp.op);
            elPredictXPaths.add(categoryResolution.xpath());
        }

        xPathy = combineAnd(elPredictXPaths);

        for (Attribute attribute : attributes) {
            ExecutionDictionary.Op op = getOpFromString(attribute.predicateType);
            xPathy = applyAttrOp(xPathy, attribute.attrName, op, attribute.predicateVal);
        }
//        }


    }

    public static ExecutionDictionary.Op getOpFromString(String input) {
        return switch (input) {
            case null -> null;
            case String s when s.isBlank() -> null;
            case String s when s.startsWith("equal") -> ExecutionDictionary.Op.EQUALS;
            case String s when s.startsWith("contain") -> ExecutionDictionary.Op.CONTAINS;
            case String s when s.startsWith("start") -> ExecutionDictionary.Op.STARTS_WITH;
            case String s when s.startsWith("end") -> ExecutionDictionary.Op.ENDS_WITH;
            default -> null;
        };
    }

//    private List<PhraseData> phraseContextList;

    public List<PhraseData> getPhraseContextList() {
//        if (categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) return new ArrayList<>();
//        if (phraseContextList == null)
//            phraseContextList = parentPhrase.processContextList();
//        return phraseContextList;
        return parentPhrase.processContextList();
    }


    public String getDefaultElementValue(ElementWrapper elementWrapper) {
        String defaultValue = String.valueOf(elementWrapper.getElementReturnValue());
        String currentValue = defaultValue;

        for (String valueType : valueTypes) {
            switch (valueType) {
                case "length":
                    currentValue = String.valueOf(currentValue.length());
                    break;
                case "lowercase":
                    currentValue = currentValue.toLowerCase();
                    break;
                case "uppercase":
                    currentValue = currentValue.toUpperCase();
                    break;
                default:
                    break; // unknown escape -> literal char
            }
        }

        return currentValue;
    }

//    public List<WebElement> getWebElements() {
//        if (!elementTypes.contains(ElementType.HTML_TYPE))
//            return null;
//return getElementWrappers().stream().map(elementWrapper -> elementWrapper.getElement())
//    }

//    public List<ValueWrapper> getValues(String valueType) {
//        List<ValueWrapper> returnList = new ArrayList<>();
//        if (elementTypes.contains(ElementType.HTML_TYPE)) {
//            getElementWrappers().forEach(e -> returnList.add(e.getElementReturnValue()));
//        } else {
//            returnList.addAll(nonHTMLValues);
//        }
//        return returnList;
//    }

    private List<ValueWrapper> previouslyReturnedValues = null;

    public List<ValueWrapper> getValues() {

        List<ValueWrapper> returnList = new ArrayList<>();
        if (elementTypes.contains(ElementType.HTML_TYPE)) {
            getElementWrappers().forEach(e -> returnList.add(e.getElementReturnValue()));
        } else if (elementTypes.contains(ElementType.BROWSER_WINDOW)) {
            String normalized = category.toUpperCase().replaceAll("WINDOWS?", "").trim();
            if (normalized.isBlank())
                normalized = "TITLE";
            System.out.println("@@normalized:::: " + normalized);
            WindowSwitch.WindowSelectionType windowSelectionType = WindowSwitch.WindowSelectionType.LOOKUP.get(normalized);
            System.out.println("@@windowSelectionType:::: " + windowSelectionType);
            returnList.addAll(WindowSwitch.findMatchingHandles(parentPhrase.getDriver(), windowSelectionType, textOps).stream().map(ValueWrapper::createValueWrapper).toList());
            System.out.println("@@Windows-returnList:::: " + returnList);
        } else {
            returnList.addAll(nonHTMLValues);
        }
        previouslyReturnedValues = new ArrayList<>(returnList);
        return returnList;
    }

    public ValueWrapper getValue() {
        List<ValueWrapper> values = getValues();
        return values.isEmpty() ? null : values.getFirst();
    }

    public List<ElementWrapper> getElementThrowErrorIfEmptyWithNoModifier() {
        List<ElementWrapper> returnElements = getElementWrappers();
        if (returnElements.isEmpty() && !(selectionType.equals("any") || selectionType.equals("none")))
            throw new RuntimeException("No elements found: " + this);
        return returnElements;
    }

    public boolean hasElementWrappers() {
        return !getElementWrappers().isEmpty();
    }

    public List<ElementWrapper> getElementWrappers() {


        if (wrappedElements == null) {
            parentPhrase.syncWithDOM();
        }

        return wrappedElements;
    }


}