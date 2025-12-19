package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;

import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.coredefinitions.GeneralSteps;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.afterOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.beforeOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.inBetweenOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.insideOf;
import static tools.dscode.common.util.StringUtilities.normalizeSingular;
import static tools.dscode.coredefinitions.GeneralSteps.getDriver;


public abstract class PhraseData {

        public Map<ElementType, ElementMatch> elementMap1 = new HashMap<>();
        public Map<ElementType, ElementMatch> elementMap2 = new HashMap<>();


    public record MatchPair(ElementMatch first, ElementMatch second) {}

    public MatchPair getDistinctElementMatches(ElementType firstType, ElementType secondType) {
        ElementMatch first = findMatch(firstType, null);
        ElementMatch second = findMatch(secondType, first);
        return new MatchPair(first, second);
    }

    private ElementMatch findMatch(ElementType type, ElementMatch disallow) {
        PhraseData phrase = this;
        while (phrase != null) {
            // try map1 then map2, but allow “fallback” behavior while skipping disallowed
            ElementMatch m1 = phrase.elementMap1.get(type);
            if (m1 != null && (disallow == null || !m1.equals(disallow))) return m1;

            ElementMatch m2 = phrase.elementMap2.get(type);
            if (m2 != null && (disallow == null || !m2.equals(disallow))) return m2;

            phrase = phrase.previousPhrase;
        }
        return null;
    }


      public ElementMatch getElementMatch(ElementType elementType) {
            PhraseData phrase = this;
            while (phrase != null) {
                ElementMatch elementMatch = phrase.elementMap1.getOrDefault(elementType, phrase.elementMap2.get(elementType));
                if (elementMatch != null) return elementMatch;
                phrase = phrase.previousPhrase;
            }
            return null;
        }

    public ElementMatch getElementMatch1(ElementType elementType) {
        PhraseData phrase = this;
        while (phrase != null) {
            ElementMatch elementMatch = phrase.elementMap1.get(elementType);
            if (elementMatch != null) return elementMatch;
            phrase = phrase.previousPhrase;
        }
        return null;
    }

    public ElementMatch getElementMatch2(ElementType elementType) {
        PhraseData phrase = this;
        while (phrase != null) {
            ElementMatch elementMatch = phrase.elementMap2.get(elementType);
            if (elementMatch != null) return elementMatch;
            phrase = phrase.previousPhrase;
        }
        return null;
    }


    public WebDriver webDriver = null;
    public List<PhraseData> branchedPhrases = new ArrayList<>();

    public ElementWrapper contextElement;
    private SearchContext searchContext;

    public SearchContext getSearchContext() {


        if (contextElement != null) {
            WebElement element = contextElement.getElement();

            if (element == null)
                throw new RuntimeException("Element not found: " + contextElement.elementMatch + " at " + contextElement.elementMatch.xPathy);
            return element;
        }
        if (searchContext == null) {
            if (webDriver == null)
                webDriver = GeneralSteps.getDriver();
            return webDriver;
        }

        return searchContext;
    }

    //    List<XPathData> contextXPathDataList = new ArrayList<>();

    public final String text;
    public final String resolvedText;
    public final Character termination; // nullable
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
    //    public boolean hasDOMInteraction;
    //    public List<ElementMatch> elements;
    public List<ElementMatch> elementMatches = new ArrayList<>();
    public int elementCount;
    public ElementMatch firstElement = null;
    public ElementMatch secondElement = null;
    public ElementMatch lastElement = null;
    public ElementMatch passedElement = null;

    public List<ElementWrapper> getWrappedElements() {
        return wrappedElements;
    }

    private List<ElementWrapper> wrappedElements = new ArrayList<>();
    //    public List<ValueMatch> values;
//    public ElementMatch elementMatch;
    public String conjunction;
    public String action;
    public String assertion;
    public String assertionType;
    //    public List<Component> components;
    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();
    public PhraseType phraseType;

    public XPathy contextXPathy;

    public String keyName;
    public boolean isClone = false;

//    public SearchContext getCurrentSearchContext() {
//        if (currentSearchContext == null) {
//            currentSearchContext = wrapContext(getBrowser("BROWSER"));
//        }
//        return currentSearchContext;
//    }
//
//    public void setCurrentSearchContext(SearchContext searchContext) {
//        if (searchContext instanceof SearchContext)
//            this.currentSearchContext = (SearchContext) searchContext;
//        else
//            this.currentSearchContext = wrapContext(searchContext);
//    }

    private SearchContext currentSearchContext;

    public enum PhraseType {
        INITIAL, CONTEXT, ACTION, ASSERTION, CONDITIONAL, NO_EXECUTION, DATA_OPERATION, BROWSER_OPERATION
    }

    public List<PhraseData> contextPhrases = new ArrayList<>();
//    public List<XPathy> contextXpathyList;
//    List<PhraseData> usedContextPhrases;

    public String selectionType;
    public String conditional;
    public String body;
    //    public boolean phraseConditionalState = true;
    public int phraseConditionalMode = 0;

    //    public XPathChainResult contextMatch;
    @Override
    public String toString() {
        return resolvedText + termination;
    }

    public static final Set<String> DATA_ELEMENTS =
            Set.of("Data Table", "Data Row");

    public static final Set<String> BROWSER_ELEMENTS =
            Set.of("Alert", "Window", "BROWSER", "Browser Tab", "Address Bar");


    public PhraseData(String inputText, Character delimiter, LineData lineData) {
        parsedLine = lineData;
        text = inputText;
        resolvedText = getRunningStep().getStepParsingMap().resolveWholeText(text);
        termination = delimiter;
        contextTermination = termination.equals('.') || termination.equals(':') || termination.equals('?');
        MatchNode returnMatchNode = getNodeDictionary().parse(resolvedText);
        phraseNode = returnMatchNode.getChild("phrase");
        assert phraseNode != null;
        hasNot = phraseNode.localStateBoolean("not");
        keyName = phraseNode.getStringFromLocalState("keyName");
        conditional = phraseNode.getStringFromLocalState("conditional");
        body = phraseNode.getStringFromLocalState("body");

        elementMatches = phraseNode.getOrderedChildren("elementMatch").stream().map(ElementMatch::new).collect(Collectors.toList());
        elementCount = elementMatches.size();
        elementMatches.forEach(elementMatch -> elementMatch.parentPhrase = this);
        if (elementCount > 0) {
            firstElement = elementMatches.getFirst();
            firstElement.elementTypes.forEach(elementType -> elementMap1.put(elementType, firstElement));
            lastElement = elementMatches.getLast();
            if (elementCount > 1) {
                secondElement = elementMatches.get(1);
                secondElement.elementTypes.forEach(elementType -> elementMap2.put(elementType, secondElement));
            }
        }
//        components.forEach(component -> component.parentPhrase = this);
//        elements = getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
//        values = getNextComponents(-1, "valueMatch").stream().map(m -> (ValueMatch) m).toList();
//        elements.forEach(element -> categoryFlags.addAll(element.categoryFlags));
//        elementMatch = elementMatches.isEmpty() ? null : elementMatches.getFirst();
        selectionType = elementMatches.isEmpty() ? "" : elementMatches.getFirst().selectionType;
        isTopContext = categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_TOP_CONTEXT);
        isContext = isTopContext || categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);

        conjunction = phraseNode.getStringFromLocalState("conjunction");
        position = lineData.phrases.size();
        context = phraseNode.getStringFromLocalState("context");
        if (conditional.contains("if") || termination.equals('?')) {
            phraseType = PhraseType.CONDITIONAL;
            assertion = phraseNode.getStringFromLocalState("assertion").replaceAll("s$", "").replaceAll("e?s\\s+", " ");
        } else if (!context.isBlank()) {
            phraseType = PhraseType.CONTEXT;
            isFrom = context.equals("from");
            contextXPathy = getXPathyContext(context, elementMatches);
        } else {
            action = phraseNode.getStringFromLocalState("action");

            if (!action.isBlank()) {
                phraseType = PhraseType.ACTION;
            } else {
                assertionType = phraseNode.getStringFromLocalState("assertionType");
                if (!assertionType.isBlank()) {
                    assertion = phraseNode.getStringFromLocalState("assertion").replaceAll("s$", "").replaceAll("e?s\\s+", " ");
                    phraseType = PhraseType.ASSERTION;
                }
            }
        }

        if (phraseType == null && previousPhrase != null) {
            if (!elementMatches.isEmpty()) {
                phraseType = previousPhrase.phraseType;
                if (action.isBlank())
                    action = previousPhrase.action;
                if (assertionType.isBlank())
                    assertionType = previousPhrase.assertionType;
                if (conditional.isBlank())
                    conditional = previousPhrase.conditional;
            }
            phraseType = PhraseType.NO_EXECUTION;
        }


//        hasDOMInteraction = !elements.isEmpty() && !phraseType.equals(PhraseType.CONTEXT);

        newContext = phraseNode.localStateBoolean("newStartContext");
//        if (position > 0) {
//            previousPhrase = lineData.phrases.get(position - 1);
//            previousPhrase.nextPhrase = this;
//        }


    }


    public List<PhraseData> processContextList() {


        List<PhraseData> returnList = new ArrayList<>();
        returnList.add(new Phrase("from " + STARTING_CONTEXT, ',', parsedLine));
        for (List<PhraseData> inner : parsedLine.inheritedContextPhrases) {
            returnList.addAll(inner);
        }
        returnList.addAll(contextPhrases);

        for (int i = returnList.size() - 1; i >= 0; i--) {
            PhraseData phraseData = returnList.get(i);

            if (phraseData.contextElement != null || phraseData.newContext || phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_TOP_CONTEXT) || phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.ELEMENT_CONTEXT)) {
                return returnList.subList(i, returnList.size());
            }
        }

        return returnList;
    }

    public static XPathy getXPathyContext(String context, List<ElementMatch> elements) {

        if (elements.isEmpty()) return null;
        XPathy xPathy = elements.getFirst().xPathy;
        if (xPathy == null) return null;
        return switch (context.toLowerCase()) {
            case String s when s.startsWith("for") -> insideOf(xPathy);
            case String s when s.startsWith("from") -> insideOf(xPathy);
            case String s when s.startsWith("in") -> insideOf(xPathy);
            case String s when s.startsWith("after") -> afterOf(xPathy);
            case String s when s.startsWith("before") -> beforeOf(xPathy);
            case String s when s.startsWith("between") -> inBetweenOf(xPathy, elements.get(1).xPathy);
            default -> null;
        };
    }

//    public List<Component> getNextComponents(int position, String... nodeNames) {
//        List<String> names = Arrays.asList(nodeNames);
//        return components.stream().filter(c -> c.position > position && (names.isEmpty() || names.contains(c.name))).toList();
//    }
//
//    public List<Component> getPreviousComponents(int position, String... nodeNames) {
//        List<String> names = Arrays.asList(nodeNames);
//        return components.stream()
//                .filter(c -> c.position < position && (names.isEmpty() || names.contains(c.name)))
//                .collect(Collectors.collectingAndThen(
//                        Collectors.toCollection(ArrayList::new),
//                        list -> {
//                            Collections.reverse(list);
//                            return list;
//                        }
//                ));
//    }


    public abstract void runPhrase();

    public abstract PhraseData clonePhrase(PhraseData previous);

    public abstract PhraseData resolvePhrase();

    public abstract PhraseData getNextResolvedPhrase();

    private final LifecycleManager lifecycle = new LifecycleManager();


    public void syncWithDOM() {
        if (this.webDriver == null)
            this.webDriver = GeneralSteps.getDriver();
        waitMilliseconds(1000);
        lifecycle.fire(Phase.BEFORE_DOM_LOAD_CHECK);
        waitForPhraseEntities(this);
        waitMilliseconds(100);
        lifecycle.fire(Phase.BEFORE_DOM_INTERACTION);
    }


    public List<Object> getAllPhraseValues() {
        List<Object> returnList = new ArrayList<>();
        for (ElementMatch elementMatch : elementMatches) {
            returnList.addAll(elementMatch.getValues());
        }
        return returnList;
    }


    int inherited = 0;

}
