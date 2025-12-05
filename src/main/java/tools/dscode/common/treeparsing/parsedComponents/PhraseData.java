package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.SearchContext;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.WrappedContext;
import tools.dscode.common.domoperations.WrappedWebElement;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.common.treeparsing.xpathcomponents.XPathChainResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.domoperations.SeleniumUtils.wrapContext;
import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.afterOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.beforeOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.inBetweenOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.insideOf;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;


public abstract class PhraseData {
//    List<XPathData> contextXPathDataList = new ArrayList<>();

    public final String text;
    public Character termination; // nullable
    public boolean contextTermination;
    public boolean hasNot;
    public final LineData parsedLine;

    public PhraseData previousPhrase;
    public PhraseData nextPhrase;
    public int position;
    public boolean newContext = false;
    public MatchNode phraseNode;

    public String context;
    public boolean isFrom;
    public boolean isIn;
    public boolean isTopContext;
    public boolean isContext;
    public boolean hasDOMInteraction;
    public List<ElementMatch> elements;
    public ElementMatch elementMatch;
    public String conjunction;
    public String action;
    public String assertion;
    public String assertionType;
    public List<Component> components;
    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();
    public PhraseType phraseType;

    public XPathy contextXPathy;

    public WrappedContext getCurrentWrappedContext() {
        if (currentWrappedContext == null) {
            currentWrappedContext = wrapContext(getBrowser("BROWSER"));
        }
        return currentWrappedContext;
    }

    public void setCurrentWrappedContext(SearchContext searchContext) {
        if (searchContext instanceof WrappedContext)
            this.currentWrappedContext = (WrappedContext) searchContext;
        else
            this.currentWrappedContext = wrapContext(searchContext);
    }

    private WrappedContext currentWrappedContext;


    public enum PhraseType {
        INITIAL, CONTEXT, ACTION, ASSERTION
    }

    public List<PhraseData> contextPhrases = new ArrayList<>();
//    public List<XPathy> contextXpathyList;
//    List<PhraseData> usedContextPhrases;

    public String selectionType;

    //    public XPathChainResult contextMatch;
    @Override
    public String toString() {
        return text;
    }

    public PhraseData(String inputText, Character delimiter, LineData lineData) {
        parsedLine = lineData;
        text = inputText;
        termination = delimiter;
        contextTermination = termination.equals('.') || termination.equals(':');
        MatchNode returnMatchNode = getNodeDictionary().parse(inputText);
        phraseNode = returnMatchNode.getChild("phrase");
        assert phraseNode != null;
        hasNot = phraseNode.localStateBoolean("not");
        components = phraseNode.getOrderedChildren("elementMatch", "valueMatch").stream().map(m -> {
            if (m.name().equals("valueMatch"))
                return new ValueMatch(m);
            ElementMatch newElementMatch = new ElementMatch(m);
            return newElementMatch;
        }).toList();
        components.forEach(component -> component.parentPhrase = this);
        elements = getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
        elements.forEach(element -> categoryFlags.addAll(element.categoryFlags));
        elementMatch = elements.isEmpty() ? null : elements.getFirst();
        selectionType = elementMatch == null ? "" : elementMatch.selectionType;
        isTopContext = categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_TOP_CONTEXT);
        isContext = isTopContext || categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);

        conjunction = phraseNode.getStringFromLocalState("conjunction");
        position = lineData.phrases.size();
        context = phraseNode.getStringFromLocalState("context");
        System.out.println("@@context:: " + context);
        if (!context.isBlank()) {
            phraseType = PhraseType.CONTEXT;
            isFrom = context.equals("from");
            contextXPathy = getXPathyContext(context, elements);
        } else {
            action = phraseNode.getStringFromLocalState("action");
            System.out.println("@@actionSet to: " + action);
            if (!action.isBlank()) {
                phraseType = PhraseType.ACTION;
            } else {
                assertionType = phraseNode.getStringFromLocalState("assertionType");
                if (!assertionType.isBlank()){
                    assertion = phraseNode.getStringFromLocalState("assertion");
                    phraseType = PhraseType.ASSERTION;
                }
            }
        }
        if (phraseType == null) {
            phraseType = PhraseType.CONTEXT;
        }
        hasDOMInteraction = phraseType.equals(PhraseType.ASSERTION) || phraseType.equals(PhraseType.ACTION);

        newContext = phraseNode.localStateBoolean("newStartContext");
        if (position > 0) {
            previousPhrase = lineData.phrases.get(position - 1);
            previousPhrase.nextPhrase = this;
        }
    }


    public List<PhraseData> processContextList() {
        System.out.println("@@processContextList1");
        List<PhraseData> returnList = new ArrayList<>();
        returnList.add(new Phrase("from " + STARTING_CONTEXT, ',', parsedLine));
        for (List<PhraseData> inner : parsedLine.inheritedContextPhrases) {
            returnList.addAll(inner);
        }
        returnList.addAll(contextPhrases);
        System.out.println("@@returnList1: " + returnList);
        for (int i = returnList.size() - 1; i >= 0; i--) {
            PhraseData phraseData = returnList.get(i);
            if (phraseData.newContext || phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_TOP_CONTEXT)) {
                return returnList.subList(i, returnList.size());
            }
        }
        System.out.println("@@returnList2: " + returnList);
        return returnList;
    }

    public static XPathy getXPathyContext(String context, List<ElementMatch> elements) {
        System.out.println("@@getXPathyContext: " + elements);
        if (elements.isEmpty()) return null;
        XPathy xPathy = elements.getFirst().xPathy;
        if (xPathy == null) return null;
        return switch (context.toLowerCase()) {
            case String s when s.startsWith("from") -> insideOf(xPathy);
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


    public abstract void runPhrase();


}
