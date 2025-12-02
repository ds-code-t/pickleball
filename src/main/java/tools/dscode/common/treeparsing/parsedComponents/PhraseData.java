package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.afterOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.beforeOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.inBetweenOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.insideOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyUtils.refine;

public abstract class PhraseData {
//    List<XPathData> contextXPathDataList = new ArrayList<>();

    public final String originalText;
    public String text;
    public Character termination; // nullable

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
    public List<ElementMatch> elements;
    public String conjunction;
    public String action;
    public String assertion;
    public String assertionType;
    public List<Component> components;
    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();
    public PhraseType phraseType;

    public XPathy contextXPathy;

    public enum PhraseType {
        INITIAL, CONTEXT, ACTION, ASSERTION
    }

    public List<PhraseData> contextPhrases = new ArrayList<>();
    public List<XPathy> contextXpathyList;
    List<PhraseData> usedContextPhrases;

    public List<XPathy> getContextXpathyList() {
        if (contextXpathyList == null) {
            contextXpathyList = new ArrayList<>();

            usedContextPhrases = new ArrayList<>();
            for (int i = contextPhrases.size() - 1; i >= 0; i--) {
                PhraseData phraseData = contextPhrases.get(i);
                usedContextPhrases.addFirst(phraseData);
                if (phraseData.isFrom && phraseData.isTopContext)
                    break;
            }

            PhraseData lastPhraseData = null;
            for (int i = 0; i < usedContextPhrases.size(); i++) {
                PhraseData phraseData = usedContextPhrases.get(i);
                if (phraseData.contextXPathy != null) {
                    if (lastPhraseData == null) {
                        contextXpathyList.add(phraseData.contextXPathy);
//                    } else if (lastPhraseData.isFrom || lastPhraseData.isContext || phraseData.isFrom || phraseData.isContext) {
                    } else if (lastPhraseData.isContext || phraseData.isContext) {
                        contextXpathyList.add(phraseData.contextXPathy);
                    } else if (!contextXpathyList.isEmpty()) {
                        XPathy mergedXpathy = refine(contextXpathyList.getLast(), phraseData.contextXPathy);
                        contextXpathyList.set(contextXpathyList.size() - 1, mergedXpathy);
                    }
                }
                lastPhraseData = phraseData;
            }
        }
        return contextXpathyList;
    }


    public PhraseData(String inputText, Character delimiter, LineData lineData) {
        parsedLine = lineData;
        originalText = inputText;
        termination = delimiter;
        MatchNode returnMatchNode = getNodeDictionary().parse(inputText);
        phraseNode = returnMatchNode.getChild("phrase");
        assert phraseNode != null;

        components = phraseNode.getOrderedChildren("elementMatch", "valueMatch").stream().map(m -> {
            if (m.name().equals("valueMatch"))
                return new ValueMatch(m);
            ElementMatch newElementMatch = new ElementMatch(m);
            return newElementMatch;
        }).toList();
        elements = getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
        elements.forEach(element -> categoryFlags.addAll(element.categoryFlags));
        isTopContext = categoryFlags.contains(ExecutionDictionary.CategoryFlags.TOP_CONTEXT);
        isContext = isTopContext || categoryFlags.contains(ExecutionDictionary.CategoryFlags.CONTEXT);

        conjunction = phraseNode.getStringFromLocalState("conjunction");
        position = lineData.phrases.size();
        context = phraseNode.getStringFromLocalState("context");
        if (!context.isBlank()) {
            phraseType = PhraseType.CONTEXT;
            if (context.equals("in")) {
                if (isContext)
                    context = "from";
                else
                    isIn = true;
            }
            isFrom = context.equals("from");
            contextXPathy = getXPathyContext(context, elements);
        } else {
            action = phraseNode.getStringFromLocalState("action");
            System.out.println("@@actionSet to: " + action);
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
        newContext = phraseNode.localStateBoolean("newStartContext");
        if (position > 0) {
            previousPhrase = lineData.phrases.get(position - 1);
            previousPhrase.nextPhrase = this;
        }
//        newContext = phraseNode.localStateBoolean("newStartContext") || (previousPhrase != null && previousPhrase.termination.equals('.'));

        if (newContext) {

        } else if (previousPhrase == null) {
            contextPhrases.addAll(lineData.contextPhrases);
        } else {
            if (previousPhrase.termination.equals('.') && !previousPhrase.contextPhrases.isEmpty())
                contextPhrases.addAll(previousPhrase.contextPhrases.subList(0, previousPhrase.contextPhrases.size() - 1));
            else
                contextPhrases.addAll(previousPhrase.contextPhrases);
        }
        if (phraseType == PhraseType.CONTEXT)
            contextPhrases.add(this);
    }


    public static XPathy getXPathyContext(String context, List<ElementMatch> elements) {
        if (elements.isEmpty()) return null;
        XPathy xPathy = elements.getFirst().xPathy;
        if (xPathy == null) return null;
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


    public abstract void runPhrase();


}
