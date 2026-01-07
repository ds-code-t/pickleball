package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;

import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.status.SoftRuntimeException;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.OperationsInterface;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.PlaceHolderMatch;
import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.coredefinitions.GeneralSteps;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.PLACE_HOLDER_MATCH;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.afterOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.beforeOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.inBetweenOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.insideOf;


public abstract class PhraseData extends PassedData {

    public final String text;
    public final String resolvedText;
    public final Character termination; // nullable
    public final LineData parsedLine;
    private SearchContext searchContext;


    public SearchContext getSearchContext() {


        if (contextElement != null) {
            WebElement element = contextElement.getElement();

            if (element == null)
                throw new RuntimeException("Element not found: " + contextElement.elementMatch + " at " + contextElement.elementMatch.xPathy);
            return element;
        }
        if (searchContext == null) {
            return getDriver();
        }

        return searchContext;
    }


    public List<ElementWrapper> getWrappedElements() {
        return wrappedElements;
    }

    private List<ElementWrapper> wrappedElements = new ArrayList<>();
    //    public List<ValueMatch> values;
//    public ElementMatch elementMatch;


    public enum PhraseType {
        INITIAL, CONTEXT, ACTION, ASSERTION, CONDITIONAL, ELEMENT_ONLY, NO_EXECUTION, DATA_OPERATION, BROWSER_OPERATION
    }


    //    public XPathChainResult contextMatch;
    @Override
    public String toString() {
        return resolvedText + termination;
    }

    public static final Set<String> DATA_ELEMENTS =
            Set.of("Data Table", "Data Row");

    public static final Set<String> BROWSER_ELEMENTS =
            Set.of("Alert", "Window", "BROWSER", "Browser Tab", "Address Bar");


    public boolean hasResolvedText = false;
    public boolean hasTextToResolve = false;


    public PhraseData(String inputText, Character delimiter, LineData lineData) {
        parsedLine = lineData;
        text = inputText;
        resolvedText = getRunningStep().getStepParsingMap().resolveWholeText(text);
        hasResolvedText = !text.trim().equalsIgnoreCase(resolvedText.trim());
        hasTextToResolve = hasResolvedText || text.matches(".*<.*>.*");
        termination = delimiter;
        contextTermination = termination.equals('.') || termination.equals(':') || termination.equals('?');
        MatchNode returnMatchNode = getNodeDictionary().parse(resolvedText);
        phraseNode = returnMatchNode.getChild("phrase");
        assert phraseNode != null;
        operationIndex = (Integer) phraseNode.getFromLocalState("operationIndex");

        hasNo = phraseNode.localStateBoolean("no");
//        hasNot = phraseNode.localStateBoolean("not");
//        hasNone = phraseNode.localStateBoolean("none");
//        keyName = phraseNode.getStringFromLocalState("keyName");
        setConditional(phraseNode.getStringFromLocalState("conditional"));
        body = phraseNode.getStringFromLocalState("body");
        separator = phraseNode.localStateBoolean("separator");
        setElementMatches(phraseNode.getOrderedChildren("elementMatch").stream().map(this::getElementMatch).collect(Collectors.toList()));


//        components.forEach(component -> component.parentPhrase = this);
//        elements = getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
//        values = getNextComponents(-1, "valueMatch").stream().map(m -> (ValueMatch) m).toList();
//        elementMatch = elementMatches.isEmpty() ? null : elementMatches.getFirst();
        isTopContext = categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_TOP_CONTEXT);
        isContext = isTopContext || categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);

        conjunction = phraseNode.getStringFromLocalState("conjunction");
        position = lineData.phrases.size();
        context = phraseNode.getStringFromLocalState("context");
        if (getConditional().contains("if")) {
            setAssertionType("conditional");
            setAssertion(phraseNode.getStringFromLocalState("assertion"));
        } else if (termination.equals('?')) {
            setAssertionType("conditionalTermination");
            setAssertion(phraseNode.getStringFromLocalState("assertion"));
        } else if (!context.isBlank()) {
            phraseType = PhraseType.CONTEXT;
            isFrom = context.equals("from");
            contextXPathy = getXPathyContext(context, getElementMatches());
        } else {
            setAction(phraseNode.getStringFromLocalState("action"));
            if (getAction().isBlank()) {
                setAssertionType(phraseNode.getStringFromLocalState("assertionType"));
                setAssertion(phraseNode.getStringFromLocalState("assertion"));
            }
        }


        setNewContext(phraseNode.localStateBoolean("newStartContext"));

        if (phraseType == null) {
            System.out.println("No initial PhraseType set for '" + text + "'");
        } else {
            System.out.println("PhraseType: " + phraseType + " set for '" + text + "'");
        }
    }

    public ElementMatch getElementMatch(MatchNode elementNode) {


        if (elementNode.getStringFromLocalState("type").equals(PLACE_HOLDER_MATCH)) {
            return new PlaceHolderMatch(this);
        } else {
            return new ElementMatch(this, elementNode);
        }
    }


//    public void initialization()
//    {
//        if ( !operationPhrase || elementCount>2) {
//            setElementMatches(new ArrayList<>(getElementMatches().stream().filter(elementMatch -> !elementMatch.isPlaceHolder()).toList()));
//        }
//
//        if (elementCount > 0) {
//            firstElement = elementMatches.getFirst();
//            firstElement.elementTypes.add(ElementType.FIRST_ELEMENT);
//            firstElement.elementTypes.forEach(elementType -> elementMap1.put(elementType, firstElement));
//            lastElement = elementMatches.getLast();
//            lastElement.elementTypes.add(ElementType.LAST_ELEMENT);
//            if (elementCount > 1) {
//                elementMatches.forEach(elementMatch -> elementMatch.elementTypes.add(MULTIPLE_ELEMENTS_IN_PHRASE));
//                secondElement = elementMatches.get(1);
//                secondElement.elementTypes.add(ElementType.SECOND_ELEMENT);
//                secondElement.elementTypes.forEach(elementType -> elementMap2.put(elementType, secondElement));
//            } else {
//                firstElement.elementTypes.add(ElementType.SINGLE_ELEMENT_IN_PHRASE);
//            }
//        }
//
//
//
//        if (phraseType == null) {
//            if (!elementMatches.isEmpty())
//                phraseType = PhraseType.ELEMENT_ONLY;
//        }
//
//
//
//        if (operationIndex != null) {
//            for (ElementMatch em : elementMatches) {
//                if (em.startIndex < operationIndex) {
//                    em.elementTypes.add(PRECEDING_OPERATION);
//                    elementMatchesProceedingOperation.add(em);
//                } else if (em.startIndex > operationIndex) {
//                    elementMatchesFollowingOperation.add(em);
//                    em.elementTypes.add(FOLLOWING_OPERATION);
//                }
//            }
//            elementBeforeOperation = elementMatchesProceedingOperation.isEmpty() ? null : elementMatchesProceedingOperation.getFirst();
//            elementAfterOperation = elementMatchesFollowingOperation.isEmpty() ? null : elementMatchesFollowingOperation.getFirst();
//        } else {
//            elementMatches.forEach(em -> em.elementTypes.add(NO_OPERATION));
//        }
//
//    }


    public List<PhraseData> processContextList() {


        List<PhraseData> returnList = new ArrayList<>();
        returnList.add(new Phrase("from " + STARTING_CONTEXT, ',', parsedLine));
        for (List<PhraseData> inner : parsedLine.inheritedContextPhrases) {
            returnList.addAll(inner);
        }
        returnList.addAll(contextPhrases);

        for (int i = returnList.size() - 1; i >= 0; i--) {
            PhraseData phraseData = returnList.get(i);

            if (phraseData.contextElement != null || phraseData.isNewContext() || phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_TOP_CONTEXT) || phraseData.categoryFlags.contains(ExecutionDictionary.CategoryFlags.ELEMENT_CONTEXT)) {
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


    public abstract PhraseData runPhrase();

    public abstract PhraseData clonePhrase(PhraseData previous);

    public abstract PhraseData resolvePhrase();

    public abstract PhraseData getNextResolvedPhrase();

//    private final LifecycleManager lifecycle = new LifecycleManager();


    public void syncWithDOM() {
        waitMilliseconds(1000);
        lifecycle.fire(Phase.BEFORE_DOM_LOAD_CHECK);
        waitForPhraseEntities(this);
        waitMilliseconds(100);
        lifecycle.fire(Phase.BEFORE_DOM_INTERACTION);
    }


    public List<Object> getAllPhraseValues() {
        List<Object> returnList = new ArrayList<>();
        for (ElementMatch elementMatch : getElementMatches()) {
            returnList.addAll(elementMatch.getValues());
        }
        return returnList;
    }


    public void runOperation() {


        OperationsInterface operation = actionOperation != null ? actionOperation : assertionOperation;

        operation.execute(this);

        if (result.failed()) {
            throw new RuntimeException("operation '" + operation + "' failed", result.error());
        }


        chainStartPhrase.resultPhrases.add(this);
    }

    Boolean previouslyResolvedBoolean = null;
    public boolean resolveResults() {
        if (!isOperationPhrase)
            return true;
        if (!isChainStart) {
            return chainStartPhrase.resolveResults();
        }

        if(previouslyResolvedBoolean != null)
            return previouslyResolvedBoolean;

        if(getAssertionType().isBlank())
            return true;

        previouslyResolvedBoolean = getBooleanResult();
        String assertionMessage = "Assertion phrase '" + text + "' evaluates to: " + previouslyResolvedBoolean;
        System.out.println(assertionMessage);
        if (!resultElements.isEmpty())
            assertionMessage += " , elements:" + resultElements.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n", "\n", ""));


        System.out.println("@previouslyResolvedBoolean: " + previouslyResolvedBoolean);


        switch (getAssertionType()) {
            case "ensure" -> {
                if (!previouslyResolvedBoolean) {
                    throw new RuntimeException("FAILED  " + assertionMessage);
                }
            }
            case "verify" -> {
                if (!previouslyResolvedBoolean) {
                    throw new SoftRuntimeException("FAILED  " + assertionMessage);
                }
            }
            case "conditional" -> {
                phraseConditionalMode = previouslyResolvedBoolean ? 1 : -1;
            }
        }

        return previouslyResolvedBoolean;
    }

    public boolean getBooleanResult() {
        boolean andConjunction = !conjunction.equals("or");


        for (PhraseData resultPhrase : chainStartPhrase.resultPhrases) {
            Object resultObject = resultPhrase.result.value();


            boolean isTrue = resultObject != null && (boolean) resultObject;


            if (andConjunction) {
                if (!isTrue) {
                    return false; // AND: one failure breaks
                }
            } else {
                if (isTrue) {
                    return true; // OR: one success breaks
                }
            }
        }


        return andConjunction;
    }


}
