package tools.dscode.common.treeparsing.parsedComponents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xpathy.XPathy;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;

import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.exceptions.SoftRuntimeException;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.OperationsInterface;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.PlaceHolderMatch;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.mappings.StepMapping.copytoNewParsingMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.phraseError;
import static tools.dscode.common.reporting.logging.LogForwarder.phraseInfo;
import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.PLACE_HOLDER_MATCH;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.afterOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.beforeOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.inBetweenOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.insideOf;
import static tools.dscode.common.util.debug.DebugUtils.onMatch;


public abstract class PhraseData extends PassedData {
    public Entry phraseEntry;
    //    boolean isStartingContext;
    public final String text;
    public final String resolvedText;
    public final Character termination; // nullable
    public final LineData parsedLine;
    private SearchContext searchContext;
    //    public PhraseData repeatedPhraseMaster = null;
    public boolean shouldRepeatPhrase = false;
    public boolean repeatRootPhrase = false;
    public List<PhraseData> repeatedChain = new ArrayList<>();
    //    public boolean evaluateResults = true;
//    boolean invertConditional = false;
    List<Object> repetitionContext = new ArrayList<>();


    public String getPreviousTerminator() {
        return getPreviousPhrase() == null ? "" : getPreviousPhrase().termination.toString();
    }


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
        return (resolvedText == null ? text : resolvedText).replaceAll(BOOK_END, "") + termination;
    }


    public boolean hasResolvedText = false;
    public boolean hasTextToResolve = false;


    public ParsingMap getPhraseParsingMap() {
        if (phraseParsingMap == null) {
            PhraseData previousPhrase = getPreviousPhrase();
            if (isNewContext() || previousPhrase == null || previousPhrase.termination == '.' || previousPhrase.termination == '?') {
                phraseParsingMap = getRunningStep().getStepParsingMap();
            } else {
                phraseParsingMap = previousPhrase.getPhraseParsingMap();
            }
        }
        return phraseParsingMap;
    }

    public String resolveText(String inputText) {
        return getPhraseParsingMap().resolveWholeText(inputText);
    }


    public void setPhraseParsingMap(ParsingMap newParsingMap) {
        this.phraseParsingMap = newParsingMap;
    }

    public void setPhraseParsingMap(JsonNode data) {
        ObjectNode objectNode;
        if(data instanceof ObjectNode) {
            objectNode = (ObjectNode) data;
        }
        else if(data instanceof ArrayNode)
        {
            objectNode = MAPPER.createObjectNode();
            objectNode.put(ROW_KEY, data);
        }
        else
        {
            throw new RuntimeException("Unexpected data type: " + data.getClass().getName());
        }

        phraseParsingMap = copytoNewParsingMap(getPhraseParsingMap());
        phraseParsingMap.removeMaps(MapConfigurations.MapType.PHRASE_MAP);
        NodeMap phraseNodeMap = new NodeMap(MapConfigurations.MapType.PHRASE_MAP, objectNode);
        phraseParsingMap.addMapsToStart(phraseNodeMap);
        ElementMatch dataElement = getDataElement();
        String categoryName = dataElement == null ? null : dataElement.category.replaceFirst("(?i:s)$", "");
        phraseNodeMap.setDataSource(categoryName);
    }


    public PhraseData(String inputText, Character delimiter, LineData lineData) {
        this(inputText, delimiter, lineData, null);
    }

    public final boolean defaultContextPhrase;

    public PhraseData(String inputText, Character delimiter, LineData lineData, PhraseData previousPhrase) {
        defaultContextPhrase = inputText.equals(STARTING_CONTEXT);
        if (defaultContextPhrase)
            inputText = "From " + inputText;
        setPreviousPhrase(previousPhrase);
        parsedLine = lineData;
        text = inputText;
        resolvedText = resolveText(text);
        hasResolvedText = !text.trim().equalsIgnoreCase(resolvedText.trim());
        hasTextToResolve = hasResolvedText || text.matches(".*<.*>.*");
        termination = delimiter;
        contextTermination = termination.equals('.') || termination.equals('?') || termination.equals(':');
        MatchNode returnMatchNode = getNodeDictionary().parse(resolvedText);
        phraseNode = returnMatchNode.getChild("phrase");
        assert phraseNode != null;

        String conditional = phraseNode.getStringFromLocalState("conditional");
        if (conditional.equalsIgnoreCase("until")) {
            conditional = "if";
            repeatRootPhrase = true;
//            evaluateResults = false;
//            invertConditional = true;
        }
        setConditional(conditional);

        operationIndex = (Integer) phraseNode.getFromLocalState("operationIndex");


        hasNo = phraseNode.localStateBoolean("no");


        body = phraseNode.getStringFromLocalState("body");
        separator = phraseNode.localStateBoolean("separator");
        setElementMatches(phraseNode.getOrderedChildren("elementMatch").stream().map(this::getElementMatch).collect(Collectors.toList()));

//        components.forEach(component -> component.parentPhrase = this);
//        elements = getNextComponents(-1, "elementMatch").stream().map(m -> (ElementMatch) m).toList();
//        values = getNextComponents(-1, "valueMatch").stream().map(m -> (ValueMatch) m).toList();
//        elementMatch = elementMatches.isEmpty() ? null : elementMatches.getFirst();
        isTopContext = categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_TOP_CONTEXT);
        isPageContext = isTopContext || categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);


        conjunction = phraseNode.getStringFromLocalState("conjunction");

        position = lineData.phrases.size();
        context = phraseNode.getStringFromLocalState("context");
        if (getConditional().contains("if")) {
            setAssertionType("conditional");
            setAssertion(phraseNode.getStringFromLocalState("assertion"));
        }
//        else if (termination.equals('?')) {
//            setAssertionType("conditionalTermination");
//            setAssertion(phraseNode.getStringFromLocalState("assertion"));
//        }
        else if (!context.isBlank()) {
            phraseType = PhraseType.CONTEXT;
            isFrom = context.equals("from");
            getXPathyContext(this, getElementMatches());
        } else {
            setAction(phraseNode.getStringFromLocalState("action"));
            if (getAction().isBlank()) {
                setAssertionType(phraseNode.getStringFromLocalState("assertionType"));
                setAssertion(phraseNode.getStringFromLocalState("assertion"));
            }
        }

        onMatch("##parsedata-newStartContext: ", (matchString) -> {
            System.out.println(matchString + "  , for : " + text + " \n " + phraseNode.localStateBoolean("newStartContext"));
        });

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


    public List<PhraseData> getPhraseContextList() {
        List<PhraseData> contextList = getContextListFromInheritedPhrases();
        if (contextList.isEmpty() || (!contextList.getFirst().isTopContext && contextList.getFirst().contextElement == null))
            contextList.addFirst(new Phrase(parsedLine));
        return contextList;
    }

    private List<PhraseData> getContextListFromInheritedPhrases() {
        List<PhraseData> contextList = new ArrayList<>();
        PhraseData currentPhrase = this;
        int counter = 0;
        while (currentPhrase != null) {
            counter++;
            if (counter > 1) {
                if (currentPhrase.termination != ',' && currentPhrase.termination != ':') {
                    return contextList;
                }

                if (currentPhrase.phraseType == PhraseType.CONTEXT) {
                    contextList.addFirst(currentPhrase);
                }
            }

            if (currentPhrase.isTopContext || currentPhrase.isNewContext() || currentPhrase.contextElement != null) {
                return contextList;
            }

            currentPhrase = currentPhrase.getPreviousPhrase();
        }

        return contextList;
    }


    public static void getXPathyContext(PhraseData phraseData, List<ElementMatch> elements) {
        if (elements.isEmpty()) phraseData.contextXPathy = null;
        XPathy secondXPathy = elements.size() == 1 ? null : elements.get(1).xPathy;
        String context = phraseData.context.toLowerCase();

        XPathy xPathy = elements.getFirst().xPathy;
        if (xPathy == null) {
            phraseData.contextXPathy = null;
            return;
        }

        phraseData.contextXPathy = resolveContextXPathy(
                context,
                xPathy,
                secondXPathy
        );

    }

    private static XPathy resolveContextXPathy(String context, XPathy first, XPathy second) {
        if (context.startsWith("for") || context.startsWith("from") || context.startsWith("in")) {
            return insideOf(first);
        }
        if (context.startsWith("after")) {
            return afterOf(first);
        }
        if (context.startsWith("before")) {
            return beforeOf(first);
        }
        if (context.startsWith("between")) {
            if (second == null) second = first;
            return inBetweenOf(first, second);
        }
        return null;
    }


    public abstract PhraseData runPhrase();

    public abstract PhraseData cloneInheritedPhrase();

    public abstract PhraseData cloneRepeatedChain();

    public abstract PhraseData clonePhrase(PhraseData previous, Character newTermination);

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
        if (operation instanceof ActionOperations) {
            waitMilliseconds(300);
        }
        operation.execute(this);
        if (result.failed()) {
            throw new RuntimeException("operation '" + operation + "' failed", result.error());
        }

//        if(blurAfterOperation && !termination.equals(';')){
//            blur(getDefaultDriver());
//        }
        chainStartPhrase.resultPhrases.add(this);
    }

    public void runUntilOperation() {
        OperationsInterface operation = actionOperation != null ? actionOperation : assertionOperation;
        if (operation instanceof ActionOperations) {
            waitMilliseconds(300);
        }
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

        if (previouslyResolvedBoolean != null)
            return previouslyResolvedBoolean;


        if (getAssertionType().isBlank())
            return true;

        previouslyResolvedBoolean = getBooleanResult();

        String assertionMessage = "Assertion phrase '" + resolvedText + "' evaluates to: " + previouslyResolvedBoolean;
        phraseInfo(assertionMessage);
        if (!resultElements.isEmpty())
            assertionMessage += " , elements:" + resultElements.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n", "\n", ""));

        switch (getAssertionType().replace("Termination", "")) {
            case "ensure" -> {
                if (!previouslyResolvedBoolean) {
                    phraseError("Failed hard assertion in Phrase '" + resolvedText + "'");
                    throw new RuntimeException("FAILED  " + assertionMessage);
                }
            }
            case "verify" -> {
                if (!previouslyResolvedBoolean) {
                    phraseError("Failed soft assertion in Phrase '" + resolvedText + "'");
                    throw new SoftRuntimeException("FAILED  " + assertionMessage);
                }
            }
            case "conditional" -> {
                phraseConditionalMode = previouslyResolvedBoolean ? 1 : -1;
                if (shouldRepeatPhrase) {
                    if (phraseConditionalMode <= 0) {
                        phraseConditionalMode = 1;
                    } else {
                        phraseConditionalMode = 0;
                    }
                }
                for (PhraseData resultPhrase : chainStartPhrase.resultPhrases) {
                    resultPhrase.phraseConditionalMode = phraseConditionalMode;
                }
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
