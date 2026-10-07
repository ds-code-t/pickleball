package tools.dscode.common.treeparsing.parsedComponents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xpathy.XPathy;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebElement;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.assertions.AssertionChain;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.exceptions.SoftRuntimeException;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.AssertionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.OperationsInterface;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.PlaceHolderMatch;
import tools.dscode.common.treeparsing.preparsing.LineData;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.GlobalConstants.META_TEXT_SEPARATOR;
import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.common.mappings.StepMapping.copytoNewParsingMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logError;
import static tools.dscode.common.reporting.logging.LogForwarder.logToDefaultLevel;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.treeparsing.DefinitionContext.getNodeDictionary;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.PLACE_HOLDER_MATCH;
import static tools.dscode.common.treeparsing.parsedComponents.PhraseData.PhraseType.ELEMENT_ONLY;
import static tools.dscode.common.treeparsing.preparsing.LineData.wrapLooseConditionalExpression;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.afterOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.beforeOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.inBetweenOf;
import static tools.dscode.common.treeparsing.xpathcomponents.XPathyAssembly.insideOf;

public abstract class PhraseData extends PassedData {
    //    protected Optional<ElementMatch> repetitionElementOptional;
    public boolean isChainedAssertion = false;
    public Entry phraseEntry;
    //    boolean isStartingContext;
    public String originalText;
    public String metaTextPrefix;
    public final String text;
    public String resolvedText;
    public Character termination; // nullable
    public final LineData parsedLine;
    private SearchContext searchContext;
    //    public PhraseData repeatedPhraseMaster = null;
//    public boolean shouldRepeatPhrase = false;
//    public boolean repeatRootPhrase = false;
//    public List<PhraseData> repeatedChain = new ArrayList<>();
    public boolean untilPhrase = false;

//    public boolean runAssertionChain = false;


    public AssertionChain assertionChain;
    public AssertionChain assertionChainMembership;
    //    public boolean evaluateResults = true;
//    boolean invertConditional = false;
    List<Object> repetitionContext = new ArrayList<>();


    public String getPreviousTerminator() {
        return getPreviousPhrase() == null ? "" : getPreviousPhrase().termination.toString();
    }
    protected boolean skipSync = false;

    public boolean skipPageSync() {
        return skipSync || (getPreviousPhrase() != null && getPreviousPhrase().termination == ';');
    }

    public boolean nextSemicolon() {
        return termination == ';';
    }

    public SearchContext getSearchContext() {
        if (contextElement != null) {
            if (contextElement.element == null)
                throw new RuntimeException("Element not found: " + contextElement.elementMatch + " at " + contextElement.elementMatch.xPathy);
            return contextElement;
        }
        if (searchContext == null) {
            return getDriver();
        }

        return searchContext;
    }


    public List<ElementWrapper> getWrappedElements() {
        return wrappedElements;
    }
    List<ElementWrapper> wrappedElements = new ArrayList<>();

    public enum PhraseType {
        INITIAL, CONTEXT, ACTION, ASSERTION, CONDITIONAL, ELEMENT_ONLY, NO_EXECUTION, DATA_OPERATION, BROWSER_OPERATION
    }


    @Override
    public String toString() {
        return getText().trim();
//        return getText() + " -> " + getResolvedText();
    }

    public String getResolvedText() {
        return (resolvedText == null ? text : resolvedText).replaceAll(BOOK_END, "") + termination;
    }
    public String getText() {
        return text.replaceAll(BOOK_END, "") + termination;
    }


    public boolean hasResolvedText = false;
    public boolean hasTextToResolve = false;
    /**
     * Structural conditional phrases are parsed without evaluations.
     * References are resolved once, when the phrase is about to execute.
     */
    public boolean deferEvaluations;
    public boolean evaluationsResolved;
    public boolean suppressResolve;

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

    /**
     * Construction resolves text before phrases are linked, so a continuation
     * caches the step map and never sees a Data Table the previous phrase installs.
     * Drop only that premature step-map cache. A map installed for this phrase
     * (a data-row clone) is left alone.
     */
    void inheritParsingMapFromPrevious() {
        if (phraseParsingMap == null || isNewContext()) {
            return;
        }
        PhraseData previous = getPreviousPhrase();
        if (previous == null) {
            return;
        }
        Character end = previous.termination;
        if (end != null && (end == '.' || end == '?')) {
            return;
        }
        var step = getRunningStep();
        if (step != null && phraseParsingMap == step.getStepParsingMap()) {
            phraseParsingMap = null;
        }
    }

    public String resolveText(String inputText) {
        return getPhraseParsingMap().resolveWholeText(inputText);
    }


    public void setPhraseParsingMap(ParsingMap newParsingMap) {
        this.phraseParsingMap = newParsingMap;
    }
    public void setPhraseParsingMap(JsonNode data) {
        ObjectNode objectNode;
        if (data instanceof ObjectNode) {
            objectNode = (ObjectNode) data;
        } else if (data instanceof ArrayNode) {
            objectNode = MAPPER.createObjectNode();
            objectNode.put(ROW_KEY, data);
        } else {
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
        this(inputText, delimiter, lineData, null, true);
    }

    public final boolean defaultContextPhrase;

    public boolean isContextTermination() {
        return (termination.equals('.') || termination.equals('?') || termination.equals(':'));
    }

    public PhraseData(String inputText, Character delimiter, LineData lineData, PhraseData previousPhrase) {
        this(inputText, delimiter, lineData, previousPhrase, true);
    }

    public PhraseData(
            String inputText,
            Character delimiter,
            LineData lineData,
            PhraseData previousPhrase,
            boolean resolveEvaluations
    ) {
        originalText = inputText;
        var m = Pattern.compile(META_TEXT_SEPARATOR + "\\s*(.*?)\\s*" + META_TEXT_SEPARATOR).matcher(inputText);
        boolean found = m.find();
        metaTextPrefix = found ? m.group(1) : "";
        inputText = found ? m.replaceFirst("") : inputText;
        inputText = wrapLooseConditionalExpression(inputText);
        defaultContextPhrase = inputText.equals(STARTING_CONTEXT);
        if (defaultContextPhrase)
            inputText = "From " + inputText;
        setPreviousPhrase(previousPhrase);
        parsedLine = lineData;
        text = inputText;
        deferEvaluations = !resolveEvaluations;
        if (resolveEvaluations) {
            resolvedText = resolveText(text);
            evaluationsResolved = true;
        } else {
            // Structural pass only. Do not resolve mappings, expressions, or $ functions.
            resolvedText = text;
            evaluationsResolved = false;
        }
        hasResolvedText = !text.trim().equalsIgnoreCase(resolvedText.trim());
        hasTextToResolve = hasResolvedText || text.matches(".*<.*>.*");
        termination = delimiter;
        parseFromText(resolvedText);
        position = lineData.phrases.size();
    }

    /**
     * One-shot execution resolve. A skipped branch never calls this.
     * A taken phrase calls it once, then executes the resolved structure.
     */
    public void resolveForExecution() {
        if (evaluationsResolved || suppressResolve) {
            return;
        }
        resolvedText = resolveText(text);
        evaluationsResolved = true;
        hasResolvedText = !text.trim().equalsIgnoreCase(resolvedText.trim());
        hasTextToResolve = hasResolvedText || text.matches(".*<.*>.*");
        resetParsedFields();
        parseFromText(resolvedText);
    }

    /**
     * False when this deferred phrase is a later branch or the body of a
     * condition that was not taken. The condition itself is resolved only
     * when no earlier branch was taken.
     */
    public boolean shouldResolveBranchReferences() {
        if (!deferEvaluations || evaluationsResolved) {
            return true;
        }
        if (suppressResolve) {
            return false;
        }
        return !skipReferenceResolution();
    }

    public boolean skipReferenceResolution() {
        String cond = getConditional() == null ? "" : getConditional().trim().toLowerCase();
        if (cond.startsWith("else")) {
            if (position == 0 || getPreviousPhrase() == null) {
                return parsedLine.previousSiblingConditionalState > -1;
            }
            return getPreviousPhrase().phraseConditionalMode > -1;
        }
        PhraseData previous = getPreviousPhrase();
        if (previous != null
                && previous.phraseConditionalMode <= 0
                && !cond.equals("if")
                && !cond.equals("until")) {
            return true;
        }
        return false;
    }

    private void resetParsedFields() {
        untilPhrase = false;
        booleanValues = List.of();
        conditional = "";
        assertion = "";
        assertionType = "";
        action = "";
        actionOperation = null;
        assertionOperation = null;
        isOperationPhrase = false;
        phraseType = null;
        hasNo = false;
        body = "";
        separator = false;
        conjunction = "";
        context = "";
        isFrom = false;
        isTopContext = false;
        isPageContext = false;
        operationIndex = 0;
        setNewContext(false);
        setElementMatches(new ArrayList<>());
    }

    private void parseFromText(String source) {
        MatchNode returnMatchNode = getNodeDictionary().parse(source);
        phraseNode = returnMatchNode.getChild("phrase");
        assert phraseNode != null;
        booleanValues = phraseNode.getOrderedChildren("booleanValue").stream().map(ValueWrapper::createValueWrapper).toList();
        String conditional = phraseNode.getStringFromLocalState("conditional");
        if (conditional.equalsIgnoreCase("until")) {
            conditional = "if";
            untilPhrase = true;
        }
        setConditional(conditional);
        Integer opIndex = (Integer) phraseNode.getFromLocalState("operationIndex");
        operationIndex = opIndex == null ? 0 : opIndex;

        hasNo = phraseNode.localStateBoolean("no");
        body = phraseNode.getStringFromLocalState("body");
        separator = phraseNode.localStateBoolean("separator");
        setElementMatches(phraseNode.getOrderedChildren("elementMatch").stream().map(this::getElementMatch).collect(Collectors.toList()));
        isTopContext = categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_TOP_CONTEXT);
        isPageContext = isTopContext || categoryFlags.contains(ExecutionDictionary.CategoryFlags.PAGE_CONTEXT);


        conjunction = phraseNode.getStringFromLocalState("conjunction");
        context = phraseNode.getStringFromLocalState("context");
        if (getConditional().contains("if")) {
            setAssertionType("conditional");
            setAssertion(phraseNode.getStringFromLocalState("assertion"));
        }
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

        setNewContext(phraseNode.localStateBoolean("newStartContext"));

        if (phraseType == null) {
            logTrace("No initial PhraseType set for '" + text + "'");
        } else {
            logTrace("PhraseType: " + phraseType + " set for '" + text + "'");
        }

        if (phraseType == null && !elementMatches.isEmpty()) {
            phraseType = ELEMENT_ONLY;
        }
        afterParse();
    }

    protected void afterParse() {
    }

    public ElementMatch getElementMatch(MatchNode elementNode) {
        if (elementNode.getStringFromLocalState("type").equals(PLACE_HOLDER_MATCH)) {
            return new PlaceHolderMatch(this, elementNode);
        }
        return ElementMatchFactory.create(this, elementNode);
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
                    if (currentPhrase.isTopContext || currentPhrase.contextElement != null) {
                        return contextList;
                    }
                }
            } else {
                if (currentPhrase.isTopContext || currentPhrase.contextElement != null) {
                    return contextList;
                }
            }
            if (currentPhrase.isNewContext()) {
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

//    public abstract PhraseData cloneRepeatedChain();

    public abstract PhraseData clonePhrase(PhraseData previous, Character newTermination);

    public abstract PhraseData clonePhrase(PhraseData previous);

    public abstract PhraseData resolvePhrase();

    public abstract PhraseData getNextResolvedPhrase();

//    private final LifecycleManager lifecycle = new LifecycleManager();

    public void syncWithDOM() {
        waitMilliseconds(400);
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
        if (operation == null && phraseType == PhraseType.CONDITIONAL) {
            // A single element has no assertion keyword after resolve. TRUE
            // uses ValueWrapper.isTruthy once booleanValues or elements exist.
            setAssertion("true");
            if (getElementMatches().isEmpty() && (booleanValues == null || booleanValues.isEmpty())) {
                booleanValues = List.of(ValueWrapper.createValueWrapper(resolvedConditionalBody()));
            }
            operation = assertionOperation;
        }
        if (operation instanceof AssertionOperations && assertionChain != null) {
            assertionChain.executeAssertionChain();
            if(parsedLine.isBlockConditionalStep && assertionChain.chainStatus)
            {
                setNextPhrase(null);
                return;
            }
        } else {
            operation.execute(this);
        }
        if (result.failed()) {
            throw new RuntimeException("operation '" + operation + "' failed", result.error());
        }

        if (assertionOperation != null) {
            logToDefaultLevel(assertionOperation.name() + " assertion evaluated to: " + result.value());
        }

    }

    /**
     * Body of a resolved conditional, without a leading {@code if} or {@code else if}.
     */
    private String resolvedConditionalBody() {
        String source = body == null ? "" : body.trim();
        if (source.isEmpty()) {
            source = resolvedText == null ? "" : resolvedText.trim();
        }
        return source.replaceFirst("(?i)^(?:else\\s+if|if)\\b\\s*", "").trim();
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
    }

    Boolean previouslyResolvedBoolean = null;
    public boolean resolveResults() {
        if (wasPhraseSkipped || !isOperationPhrase || getAssertion().isBlank())
            return true;

        previouslyResolvedBoolean = (result == null || result.value() == null) ? null : (boolean) result.value();
        String assertionMessage = "Assertion evaluates to: " + previouslyResolvedBoolean;
        if (assertionChain == null) {
            phraseConditionalMode = previouslyResolvedBoolean ? 1 : -1;
            return previouslyResolvedBoolean;
        }
//        phraseInfo(assertionMessage);
        if (!resultElements.isEmpty())
            assertionMessage += " , elements:" + resultElements.stream()
                    .map(Object::toString)
                    .collect(Collectors.joining("\n", "\n", ""));
        switch (getAssertionType()) {
            case "ensure" -> {
                if (!previouslyResolvedBoolean) {
                    logError("Failed hard assertion in Phrase '" + resolvedText + "'");
                    throw new RuntimeException("FAILED  " + assertionMessage);
                }
            }
            case "verify" -> {
                if (!previouslyResolvedBoolean) {
                    logError("Failed soft assertion in Phrase '" + resolvedText + "'");
                    throw new SoftRuntimeException("FAILED  " + assertionMessage);
                }
            }
            case "conditional" -> {
                phraseConditionalMode = previouslyResolvedBoolean ? 1 : -1;
            }
        }
        return previouslyResolvedBoolean;
    }
    public int getRepetition() {
        ElementMatch repetitionElement = getSpecialElementByFlag(ElementMatch.SpecialUse.TIMES);
        if (repetitionElement == null) return 1;
        return repetitionElement.getValue().asInteger();
    }

    public ValueWrapper getMargin() {
        ElementMatch marginElement = getSpecialElementByFlag(ElementMatch.SpecialUse.MARGIN);
        if (marginElement == null) return null;
        return marginElement.getValue();
    }
    public void resetElementWrapper() {
        wrappedElements.clear();
        elementMatches.forEach(e -> e.wrappedElements = null);
    }

}
