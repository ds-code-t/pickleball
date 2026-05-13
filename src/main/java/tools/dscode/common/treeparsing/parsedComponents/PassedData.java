package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.AssertionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.Attempt;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.PlaceHolderMatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tools.dscode.common.treeparsing.parsedComponents.ElementType.BROWSER;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.DATA_TYPE;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.HTML_ELEMENT;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.VALUE_TYPE;
import static tools.dscode.common.treeparsing.parsedComponents.PhraseData.PhraseType.CONTEXT;
import static tools.dscode.common.treeparsing.parsedComponents.PhraseData.PhraseType.ELEMENT_ONLY;
import static tools.dscode.coredefinitions.BrowserSteps.getCurrentDriver;

public abstract class PassedData {
    public PhraseData operationInheritancePhrase;
    public List<ValueWrapper> booleanValues;
    public boolean wasPhraseSkipped = false;
    ParsingMap phraseParsingMap;

    private PhraseData previousPhrase;
    private PhraseData nextPhrase;

    protected List<ElementMatch> elementMatches = new ArrayList<>();
    public boolean hasNo;
    private String conditional = "";
    public String body;
    public int phraseConditionalMode = 1;
    public boolean isOperationPhrase;
    public boolean separator;
    private PhraseData resolvedPhrase;
    protected PhraseData templatePhrase;
    public Attempt.Result result;
    public List<ElementMatch> resultElements = new ArrayList<>();
    private WebDriver driver = null;
    public List<PhraseData> branchedPhrases = new ArrayList<>();
    public ElementWrapper contextElement;
    public boolean noExecution = false;
    public ActionOperations actionOperation;
    public AssertionOperations assertionOperation;
    public int position;
    private boolean newContext = false;
    public MatchNode phraseNode;
    public String context = "";
    public boolean isFrom;
    public boolean isTopContext;
    public boolean isPageContext;

    private String action = "";
    private String assertion = "";
    public String conjunction = "";

    private String assertionType = "";
    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();
    public PhraseData.PhraseType phraseType;

    public XPathy contextXPathy;
    public boolean isClone = false;

    public int operationIndex = 0;

    public PhraseData getPreviousPhrase() {
        PhraseData prevPhrase = previousPhrase == null ? ((PhraseData) this).parsedLine.inheritedPhrase : previousPhrase;
        if (prevPhrase == null || prevPhrase.getResolvedPhrase() == null) {
            return prevPhrase;
        }
        return prevPhrase.getResolvedPhrase();
    }

    public PhraseData getPreviousInheritedOrPreviousPhrase() {
        if (operationInheritancePhrase == null || operationIndex > 0) {
            return getPreviousPhraseWithinBoundary();
        }
        return operationInheritancePhrase;
    }

    public PhraseData getNextInheritedOrNextPhrase() {
        if (operationInheritancePhrase == null || operationIndex == 0) {
            return getNextPhraseWithinBoundary();
        }
        return operationInheritancePhrase;
    }

    public PhraseData getResolvedPhrase() {
        return resolvedPhrase;
    }

    public List<ElementMatch> getValueTypeEntryElementMatches() {
        return getAllElementMatchesPreviousFallback(VALUE_TYPE);
    }

    public void setResolvedPhrase(PhraseData resolvedPhrase) {
        this.resolvedPhrase = resolvedPhrase;
        this.resolvedPhrase.phraseNode = phraseNode;
        resolvedPhrase.templatePhrase = (PhraseData) this;
    }

    private List<ElementMatch> getLocalElementMatches(ElementType... elementTypes) {
        List<ElementType> types = Arrays.asList(elementTypes);
        return new ArrayList<>(elementMatches.stream()
                .filter(e -> e.elementTypes.containsAll(types))
                .toList());
    }

    private List<ElementMatch> getLocalElementMatchesPrecedingOperation(ElementType... elementTypes) {
        List<ElementType> types = Arrays.asList(elementTypes);
        return new ArrayList<>(elementMatches.stream()
                .filter(e -> e.startIndex < operationIndex && e.elementTypes.containsAll(types))
                .toList());
    }

    private List<ElementMatch> getLocalElementMatchesFollowingOperation(ElementType... elementTypes) {
        List<ElementType> types = Arrays.asList(elementTypes);
        return new ArrayList<>(elementMatches.stream()
                .filter(e -> e.startIndex > operationIndex && e.elementTypes.containsAll(types))
                .toList());
    }



    public List<ElementMatch> getClosestWebElementMatches() {
        return getAllElementMatchesPreviousFallback(HTML_ELEMENT);
    }

    public List<ElementMatch> getElementMatchesPrecedingOperation(ElementType... elementTypes) {
        List<ElementMatch> matches = getLocalElementMatchesPrecedingOperation(elementTypes);

        PhraseData previous;
        if (matches.isEmpty() && (previous = getPreviousInheritedOrPreviousPhrase()) != null) {
            return previous.getElementMatchesPrecedingOperation(elementTypes);
        }

        return matches;
    }

    public List<ElementMatch> getElementMatchesFollowingOperation(ElementType... elementTypes) {
        List<ElementMatch> matches = getLocalElementMatchesFollowingOperation(elementTypes);
        PhraseData next;
        if (matches.isEmpty() && (next = getNextInheritedOrNextPhrase()) != null) {
            return next.getElementMatchesFollowingOperation(elementTypes);
        }

        return matches;
    }

    public List<ElementMatch> getAllElementMatchesPreviousFallback(ElementType... elementTypes) {
        List<ElementMatch> matches = getLocalElementMatches(elementTypes);

        PhraseData previous;
        if (matches.isEmpty() && (previous = getPreviousInheritedOrPreviousPhrase()) != null) {
            return previous.getAllElementMatchesPreviousFallback(elementTypes);
        }

        return matches;
    }

    public List<ElementMatch> getAllElementMatchesNextFallback(ElementType... elementTypes) {
        List<ElementMatch> matches = getLocalElementMatches(elementTypes);

        PhraseData next;
        if (matches.isEmpty() && (next = getNextInheritedOrNextPhrase()) != null) {
            return next.getAllElementMatchesNextFallback(elementTypes);
        }

        return matches;
    }

    public ElementMatch getElementMatchPreviousFallback(ElementType... elementTypes) {
        return getAllElementMatchesPreviousFallback(elementTypes).stream()
                .findFirst()
                .orElse(new PlaceHolderMatch((PhraseData) this));
    }

    public ElementMatch getElementMatchNextFallback(ElementType... elementTypes) {
        return getAllElementMatchesNextFallback(elementTypes).stream()
                .findFirst()
                .orElse(new PlaceHolderMatch((PhraseData) this));
    }

    public ElementMatch getBrowserElement() {
        return elementMatches.stream()
                .filter(e -> e.elementTypes.contains(BROWSER))
                .findFirst().orElse(null);
    }

    public ElementMatch getDataElement() {
        return elementMatches.stream()
                .filter(e -> e.elementTypes.contains(DATA_TYPE))
                .findFirst().orElse(null);
    }

    public ElementMatch getFirstElement() {
        return elementMatches.isEmpty() ? null : elementMatches.getFirst();
    }

    public ElementMatch getSecondElement() {
        return elementMatches.size() < 2 ? null : elementMatches.get(1);
    }

    public List<ElementMatch> getElementMatchesBeforeAndAfterOperation() {
        ElementMatch elementMatch1 = getElementMatchesPrecedingOperation().stream()
                .findFirst()
                .orElse(new PlaceHolderMatch((PhraseData) this));

        ElementMatch elementMatch2 = getElementMatchesFollowingOperation().stream()
                .findFirst()
                .orElse(new PlaceHolderMatch((PhraseData) this));

        return List.of(elementMatch1, elementMatch2);
    }

    public ElementMatch getElementMatchBeforeOperation(ElementType... elementTypes) {
        return getElementMatchesPrecedingOperation(elementTypes).stream()
                .findFirst()
                .orElse(new PlaceHolderMatch((PhraseData) this));
    }

    public ElementMatch getElementMatchAfterOperation(ElementType... elementTypes) {
        return getElementMatchesFollowingOperation(elementTypes).stream()
                .findFirst()
                .orElse(new PlaceHolderMatch((PhraseData) this));
    }

    public void setPreviousPhrase(PhraseData previousPhrase) {
        this.previousPhrase = previousPhrase;
    }

    public PhraseData getNextPhrase() {
        if (nextPhrase == null || nextPhrase.getResolvedPhrase() == null) {
            return nextPhrase;
        }
        return nextPhrase.getResolvedPhrase();
    }

    public void setNextPhrase(PhraseData nextPhrase) {
        this.nextPhrase = nextPhrase;
    }

    public List<ElementMatch> getElementMatches() {
        return elementMatches;
    }

    public void setElementMatches(List<ElementMatch> elementMatchesInput) {
        elementMatches = elementMatchesInput.size() > 2
                ? new ArrayList<>(elementMatchesInput.stream()
                .filter(elementMatch -> !elementMatch.isPlaceHolder())
                .toList())
                : new ArrayList<>(elementMatchesInput);

        categoryFlags.clear();

        for (ElementMatch elementMatch : elementMatches) {
            elementMatch.parentPhrase = (PhraseData) this;
            categoryFlags.addAll(elementMatch.categoryFlags);
        }
    }

    public String getConditional() {
        return conditional;
    }

    public void setConditional(String conditional) {
        if (conditional.contains("if")) {
            setAssertionType("conditional");
        }
        this.conditional = conditional;
    }

    public String getAction() {
        return action;
    }

    public boolean setAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }

        actionOperation = ActionOperations.fromString(action);
        phraseType = PhraseData.PhraseType.ACTION;
        isOperationPhrase = true;
        this.action = action;
        return true;
    }

    public String getAssertion() {
        return assertion;
    }

    public boolean setAssertion(String assertion) {
        if (assertion == null || assertion.isBlank()) {
            return false;
        }

        assertionOperation = AssertionOperations.fromString(assertion);
        if (phraseType == null || phraseType == ELEMENT_ONLY) {
            phraseType = PhraseData.PhraseType.ASSERTION;
        }

        isOperationPhrase = true;
        this.assertion = assertion;
        return true;
    }

    public String getOperation() {
        return action.isBlank() ? assertion : action;
    }

    public boolean setInheritedOperationFromPhrase(PhraseData sourcePhraseData) {
        if (!sourcePhraseData.getAction().isBlank()) {
            setAction(sourcePhraseData.getAction());
            hasNo = sourcePhraseData.hasNo ^ hasNo;
            return true;
        }

        if (!sourcePhraseData.getAssertion().isBlank()) {
            setAssertion(sourcePhraseData.getAssertion());
            hasNo = sourcePhraseData.hasNo ^ hasNo;
            return true;
        }

        return false;
    }

    public static boolean isNewBoundary(PhraseData phrase1, PhraseData phrase2) {
        if (phrase1 == null || phrase2 == null) {
            return true;
        }

        return phrase1.isContextTermination()
                || phrase2.isNewContext()
                || !phrase2.getAssertionType().isBlank();
    }

    public boolean isStartBoundary() {
        PhraseData phrase1 = getPreviousPhrase();
        PhraseData phrase2 = (PhraseData) this;
        return isNewBoundary(phrase1, phrase2);
    }

    public boolean isEndBoundary() {
        PhraseData phrase1 = (PhraseData) this;
        PhraseData phrase2 = getNextPhrase();
        return isNewBoundary(phrase1, phrase2);
    }

    public PhraseData getNextOperationPhrase() {
        PhraseData currentPhrase = (PhraseData) this;

        while ((currentPhrase = currentPhrase.getNextPhraseWithinBoundary()) != null) {
            if (currentPhrase.operationInheritancePhrase == null && !currentPhrase.getOperation().isBlank()) {
                return currentPhrase;
            }
        }

        return null;
    }

    public PhraseData getLastOperationPhrase() {
        PhraseData currentPhrase = (PhraseData) this;

        while ( (currentPhrase = currentPhrase.getPreviousPhraseWithinBoundary()) != null) {
            if (currentPhrase.operationInheritancePhrase == null && !currentPhrase.getOperation().isBlank()) {
                return currentPhrase;
            }
        }

        return null;
    }

    public void setOperationInheritanceIfNeeded() {
        if (phraseType != CONTEXT
                && operationInheritancePhrase == null
                && getOperation().isBlank()
                && !elementMatches.isEmpty()) {

            if ((operationInheritancePhrase = getLastOperationPhrase()) != null) {
                operationIndex = 0;
                setInheritedOperationFromPhrase(operationInheritancePhrase);
            } else if ((operationInheritancePhrase = getNextOperationPhrase()) != null) {
                operationIndex = 10000;
                setInheritedOperationFromPhrase(operationInheritancePhrase);
            } else {
                setAssertion("true");
            }
        }
    }

    public String getAssertionType() {
        return assertionType;
    }

    public boolean setAssertionType(String assertionType) {
        if (assertionType == null || assertionType.isBlank()) {
            return false;
        }

        this.assertionType = assertionType;
        isOperationPhrase = true;
        phraseType = assertionType.equalsIgnoreCase("conditional")
                ? PhraseData.PhraseType.CONDITIONAL
                : PhraseData.PhraseType.ASSERTION;

        return true;
    }

    public boolean isNewContext() {
        return newContext;
    }

    public void setNewContext(boolean newContext) {
        this.newContext = newContext;
    }

    public WebDriver getDriver() {
        if (driver == null) {
            driver = getCurrentDriver();
        }

        return driver;
    }

    public void setDriver(WebDriver driver) {
        this.driver = driver;
    }


    public PhraseData getPreviousPhraseWithinBoundary() {
//        PhraseData previous = getPreviousPhrase();
        if (isNewBoundary(previousPhrase, (PhraseData) this)) {
            return null;
        }
        return previousPhrase;
    }

    public PhraseData getNextPhraseWithinBoundary() {
        if (isNewBoundary((PhraseData) this, nextPhrase)) {
            return null;
        }
        return nextPhrase;
    }
}