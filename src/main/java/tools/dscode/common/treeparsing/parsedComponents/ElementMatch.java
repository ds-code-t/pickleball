package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;

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
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.combineAnd;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.applyAttrOp;


public class ElementMatch {
    public final int position;
    public PhraseData parentPhrase;
    public static final String ELEMENT_LABEL_VALUE = "_elementLabelValue";
    public static final String ELEMENT_RETURN_VALUE = "_elementReturnValue";
    public List<TextOp> textOps = new ArrayList<>();
    public String category;
    public String selectionType;
    public String elementPosition;
    public List<String> valueTypes;
    public String state;
    public List<Attribute> attributes = new ArrayList<>();
    public XPathy xPathy;
    public ElementType elementType;
    public ContextWrapper contextWrapper;
    public List<String> defaultValueKeys = new ArrayList<>(List.of(ELEMENT_RETURN_VALUE, "value", "textContent"));
//    public XPathChainResult matchedElements;
    //        public Set<XPathyRegistry.HtmlType> htmlTypes;

    public String defaultText;
    public ExecutionDictionary.Op defaultTextOp;

    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();

    public String toString() {
        return (selectionType.isEmpty() ? "" : selectionType + " ") + (elementPosition.isEmpty() ? "" : elementPosition + " ") + textOps + " " + category;
    }

    public List<Object> nonHTMLValues = new ArrayList<>();

//    public enum SelectType {
//        ANY, EVERY, FIRST, LAST
//    }

    public int elementIndex;


    private List<ElementWrapper> wrappedElements = null;

    WebDriver driver;

    public List<ElementWrapper> findWrappedElements() {
        if(wrappedElements!=null) return wrappedElements;
        driver = parentPhrase.webDriver;
        try {
            wrappedElements = getWrappedElements(this);
        }
        catch (Throwable t) {
            if(!selectionType.equals("any") && parentPhrase.phraseType == PhraseData.PhraseType.ACTION)
            {
                throw new RuntimeException("Failed to find WebElements for " + this, t);
            }
            wrappedElements = new ArrayList<>();
        }
        parentPhrase.getWrappedElements().addAll(wrappedElements);
        return wrappedElements;
    }


//    public ExecutionDictionary.Op textOp;


    static final Pattern attributePattern = Pattern.compile("^(?<attrName>[a-z][a-z\\s]+)\\s+(?<predicate>.*)$");

    public record TextOp(String text, ExecutionDictionary.Op op) {
        public TextOp(String text, String op) {
            this(text, getOpFromString(op));
        }
    }

    public ElementMatch(MatchNode elementNode) {
        this.position = elementNode.position;
        this.state = elementNode.getStringFromLocalState("state");

        this.category = elementNode.getStringFromLocalState("type");
        this.elementPosition = elementNode.getStringFromLocalState("elementPosition");
        this.selectionType = elementNode.getStringFromLocalState("selectionType");

        this.valueTypes = Arrays.stream(elementNode.getStringFromLocalState("valueTypes").split("\\s+")).sorted(Comparator.reverseOrder()).toList();

        this.elementType = ElementType.fromString(this.category).orElse(ElementType.HTML);

        if (elementNode.localStateBoolean("text")) {
            textOps.add(new TextOp(elementNode.getStringFromLocalState("text"), ExecutionDictionary.Op.EQUALS));
        }


        categoryFlags.addAll(getExecutionDictionary().getResolvedCategoryFlags(category));


        if (elementNode.localStateBoolean("elPredicate")) {
            String elPredicates = elementNode.getStringFromLocalState("elPredicate");
            for (String elPredicate : elPredicates.split("\\s+")) {
                MatchNode elPredicateNode = elementNode.getMatchNode(elPredicate.trim());
                if (elPredicateNode != null) {
                    textOps.add(new TextOp(elPredicateNode.getStringFromLocalState("predicateVal"), elPredicateNode.getStringFromLocalState("predicateType")));
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
                    attributes.add(new Attribute(attrName, (String) predicateNode.getFromLocalState("predicateVal"), (String) predicateNode.getFromLocalState("predicateType")));
                } else {
                    throw new RuntimeException("Invalid attribute predicate: " + attr);
                }
            }
        }


        if (!categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) {
            ExecutionDictionary dict = getExecutionDictionary();
            List<XPathy> elPredictXPaths = new ArrayList<>();
            for (TextOp textOp : textOps) {
                ExecutionDictionary.CategoryResolution categoryResolution = dict.andThenOrWithFlags(category, textOp.text, textOp.op);
                elPredictXPaths.add(categoryResolution.xpath());
            }

            xPathy = combineAnd(elPredictXPaths);


            for (Attribute attribute : attributes) {
                ExecutionDictionary.Op op = getOpFromString(attribute.predicateType);
                xPathy = applyAttrOp(xPathy, com.xpathy.Attribute.custom(attribute.attrName), op, attribute.predicateVal);
            }


        }


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
        if (categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT)) return new ArrayList<>();
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

    public List<Object> getValues() {
        List<Object> returnList = new ArrayList<>();
        if (elementType == ElementType.HTML) {
            getElementWrappers().forEach(e -> returnList.add(e.getElementReturnValue()));
        } else {
            returnList.addAll(nonHTMLValues);
        }
        return returnList;
    }

    public List<ElementWrapper> getElementWrappers() {
        if (wrappedElements == null) {
            parentPhrase.syncWithDOM();
        }
        return wrappedElements;
    }


}