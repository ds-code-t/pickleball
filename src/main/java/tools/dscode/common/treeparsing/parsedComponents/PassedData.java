package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.AssertionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.Attempt;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.PlaceHolderMatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tools.dscode.common.treeparsing.parsedComponents.ElementType.BROWSER;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.FOLLOWING_OPERATION;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.HTML_ELEMENT;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.NO_OPERATION;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.PRECEDING_OPERATION;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.VALUE_TYPE;
import static tools.dscode.common.treeparsing.parsedComponents.PhraseData.PhraseType.ELEMENT_ONLY;
import static tools.dscode.coredefinitions.GeneralSteps.getDefaultDriver;

public abstract class PassedData {

    public PhraseData chainStartPhrase;
    int chainStart;
    int chainEnd;

    boolean groupSeparator = false;
    public boolean isChainStart = false;

    public PhraseData lastOperationPhrase;

    public boolean isSeparatorPhrase() {
        return (groupSeparator || isNewContext() || getPreviousPhrase() == null || getPreviousPhrase().contextTermination || !assertionType.isBlank());
    }

    public void setOperationInheritance() {
        if (isSeparatorPhrase()) {
            groupSeparator = true;
            lastOperationPhrase = isOperationPhrase ? (PhraseData) this : null;
        } else {
            lastOperationPhrase = getPreviousPhrase().lastOperationPhrase;
            if (lastOperationPhrase == null) {
                lastOperationPhrase = isOperationPhrase ? (PhraseData) this : null;
            }
        }

        if (elementCount >0) {
            if (phraseType == ELEMENT_ONLY || (!getAssertion().isBlank() && getAssertionType().isBlank())) {
                if (lastOperationPhrase == null || lastOperationPhrase.equals(this)) {
                    if (hasTerminationConditional()) {
                        setConditional("if");
                        lastOperationPhrase = (PhraseData) this;
                        PhraseData currentPhrase = (PhraseData) this;
                        while (currentPhrase != null) {
                            if (currentPhrase.phraseType == ELEMENT_ONLY) {
                                currentPhrase.setAssertion("True");
                            }
                            if (currentPhrase.termination.equals('?'))
                                break;
                            currentPhrase = currentPhrase.getNextPhrase();
                        }
                    }
                }
            }
            if (phraseType == ELEMENT_ONLY) {
                if (lastOperationPhrase == null || lastOperationPhrase.equals(this)) {
                    PhraseData currentPhrase = getNextPhrase().getResolvedPhrase();
                    while (currentPhrase != null) {
                        if (currentPhrase.isOperationPhrase) {
                            if (!currentPhrase.getAction().isBlank()) {
                                setAction(currentPhrase.getAction());
                                operationIndex = firstElement.elementIndex + 1000;
                                setElementGroupings();
                            } else if (!currentPhrase.getAssertion().isBlank()) {
                                setAssertion(currentPhrase.getAssertion());
                                operationIndex = firstElement.elementIndex + 1000;
                                setElementGroupings();
                            }
                        }
                        currentPhrase = currentPhrase.getNextPhrase().getResolvedPhrase();
                        if (currentPhrase.isSeparatorPhrase())
                            break;
                    }
                } else {
                    if (!lastOperationPhrase.getAction().isBlank()) {
                        setAction(lastOperationPhrase.getAction());
                        operationIndex = 0;
                        setElementGroupings();
                    } else if (!lastOperationPhrase.getAssertion().isBlank()) {
                        setAssertion(lastOperationPhrase.getAssertion());
                        operationIndex = 0;
                        setElementGroupings();
                    }
                }
            }
        }

        if (!isOperationPhrase)
            return;

        if (lastOperationPhrase == null || lastOperationPhrase.equals(this)) {
            isChainStart = true;
            return;
        }

        if (phraseType != null && lastOperationPhrase.phraseType != phraseType) {
            isChainStart = true;
            return;
        }
    }

    private PhraseData previousPhrase;
    private PhraseData nextPhrase;

     public ElementMatch browserElement;
    protected List<ElementMatch> elementMatches = new ArrayList<>();
    private List<ElementMatch> elementMatchesProceedingOperation = new ArrayList<>();
    private List<ElementMatch> elementMatchesFollowingOperation = new ArrayList<>();


    private final List<ElementMatch> valueTypeEntryElementMatches = new ArrayList<>();

    public List<ElementMatch> getValueTypeEntryElementMatches() {
        if(valueTypeEntryElementMatches.isEmpty())
        {
            if(previousPhrase != null) {
                valueTypeEntryElementMatches.addAll(previousPhrase.getValueTypeEntryElementMatches());
            }
        }
        return valueTypeEntryElementMatches;
    }


    final List<ElementMatch> webElementMatches = new ArrayList<>();

    public List<ElementMatch> getWebElementMatches() {
        if(webElementMatches.isEmpty())
        {
            if(previousPhrase != null) {
                webElementMatches.addAll(previousPhrase.webElementMatches);
            }
        }
        return webElementMatches;
    }

//    private SearchContext currentSearchContext;

    public List<PhraseData> contextPhrases = new ArrayList<>();

//    public String selectionType = "";

    public boolean hasNo;


    private String conditional = "";


    public String body;
    public int phraseConditionalMode = 1;

    public boolean isOperationPhrase;
    public boolean separator;
//    public boolean missingData;

    public int elementCount;


    private ElementMatch firstElement = null;
    private ElementMatch secondElement = null;
    private ElementMatch elementBeforeOperation = null;
    private ElementMatch elementAfterOperation = null;


    private ElementMatch lastElement = null;

    private PhraseData resolvedPhrase;
    protected PhraseData templatePhrase;

    public Attempt.Result result;
    public List<ElementMatch> resultElements = new ArrayList<>();
    public List<PhraseData> resultPhrases = new ArrayList<>();

    private WebDriver driver = null;
    public List<PhraseData> branchedPhrases = new ArrayList<>();
    public List<PhraseData> repeatedPhrases = new ArrayList<>();

    public ElementWrapper contextElement;

    public boolean contextTermination;

    public ActionOperations actionOperation;
    public AssertionOperations assertionOperation;

    public int position;
    private boolean newContext = false;
    public MatchNode phraseNode;

    public String context = "";
    public boolean isFrom;
    public boolean isTopContext;
    public boolean isContext;


    private String action = "";
    private String assertion = "";
    public String conjunction = "";


    private String assertionType = "";
    //    public List<Component> components;
    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();
    public PhraseData.PhraseType phraseType;

    public XPathy contextXPathy;

    //    public String keyName = "";
    public boolean isClone = false;

    public Integer operationIndex;


    public PhraseData getPreviousPhrase() {
        if (previousPhrase == null || previousPhrase.getResolvedPhrase() == null)
            return previousPhrase;
        return previousPhrase.getResolvedPhrase();
    }

    public void setPreviousPhrase(PhraseData previousPhrase) {
        this.previousPhrase = previousPhrase;
    }

    public PhraseData getNextPhrase() {
        if (nextPhrase == null || nextPhrase.getResolvedPhrase() == null)
            return nextPhrase;
        return nextPhrase.getResolvedPhrase();
    }

    public void setNextPhrase(PhraseData nextPhrase) {
        this.nextPhrase = nextPhrase;
    }


    public List<ElementMatch> getElementMatches() {
        return elementMatches;
    }

    public void setElementMatches(List<ElementMatch> elementMatchesInput) {
        elementMatches = elementMatchesInput.size() > 2 ? new ArrayList<>(elementMatchesInput.stream().filter(elementMatch -> !elementMatch.isPlaceHolder()).toList()) : new ArrayList<>(elementMatchesInput);
        elementMatches.forEach(elementMatch -> elementMatch.parentPhrase = (PhraseData) this);
        elementMatches.forEach(element -> categoryFlags.addAll(element.categoryFlags));
        elementCount = elementMatches.size();

        if (elementCount > 0) {
            firstElement = elementMatches.getFirst();
            firstElement.elementTypes.add(ElementType.FIRST_ELEMENT);
            lastElement = elementMatches.getLast();
            lastElement.elementTypes.add(ElementType.LAST_ELEMENT);
            if (elementCount > 1) {
                elementMatches.forEach(elementMatch -> elementMatch.elementTypes.add(ElementType.MULTIPLE_ELEMENTS_IN_PHRASE));
                secondElement = elementMatches.get(1);
                secondElement.elementTypes.add(ElementType.SECOND_ELEMENT);
            } else {
                firstElement.elementTypes.add(ElementType.SINGLE_ELEMENT_IN_PHRASE);
            }
        }

        if (phraseType == null) {
            if (!elementMatches.isEmpty())
                phraseType = ELEMENT_ONLY;
        }


        if (operationIndex != null) {
            setElementGroupings();
        } else {
            elementMatches.forEach(em -> em.elementTypes.add(NO_OPERATION));
        }
    }

    public void setElementGroupings()
    {
        elementMatchesFollowingOperation = new ArrayList<>();
        elementMatchesProceedingOperation = new ArrayList<>();
        for (ElementMatch em : elementMatches) {
            if(em.elementTypes.contains(BROWSER))
            {
                browserElement = em;
            }
            if (em.startIndex < operationIndex) {
                em.elementTypes.add(PRECEDING_OPERATION);
                elementMatchesProceedingOperation.add(em);
            } else if (em.startIndex > operationIndex) {
                elementMatchesFollowingOperation.add(em);
                em.elementTypes.add(FOLLOWING_OPERATION);
            }
        }
        if(elementMatchesProceedingOperation.isEmpty())
        {
            elementMatchesFollowingOperation.forEach(em -> {
                if(  em.elementTypes.contains(VALUE_TYPE)){
                    valueTypeEntryElementMatches.add(em);
                }
               else if(em.elementTypes.contains(HTML_ELEMENT)){
                    webElementMatches.add(em);
                }
            });
        }
        if(webElementMatches.isEmpty())
        {
            elementMatchesProceedingOperation.forEach(em -> {
                if(  em.elementTypes.contains(HTML_ELEMENT)){
                    webElementMatches.add(em);
                }
            });
        }
        if(valueTypeEntryElementMatches.isEmpty())
        {
            elementMatchesProceedingOperation.forEach(em -> {
                if(  em.elementTypes.contains(VALUE_TYPE)){
                    valueTypeEntryElementMatches.add(em);
                }
            });
        }

        elementBeforeOperation = elementMatchesProceedingOperation.isEmpty() ? null : elementMatchesProceedingOperation.getFirst();
        elementAfterOperation = elementMatchesFollowingOperation.isEmpty() ? null : elementMatchesFollowingOperation.getFirst();
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
        if (action == null || action.isBlank()) return false;
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
        if (assertion == null || assertion.isBlank()) return false;
        assertionOperation = AssertionOperations.fromString(assertion);
        if (phraseType == null || phraseType == ELEMENT_ONLY)
            phraseType = PhraseData.PhraseType.ASSERTION;
        isOperationPhrase = true;
        this.assertion = assertion;
        return true;
    }


    public String getAssertionType() {
        return assertionType;
    }

    public boolean setAssertionType(String assertionType) {
        if (assertionType == null || assertionType.isBlank()) return false;
        this.assertionType = assertionType;
        isOperationPhrase = true;
        phraseType = assertionType.startsWith("conditional") ? PhraseData.PhraseType.CONDITIONAL : PhraseData.PhraseType.ASSERTION;
        return true;
    }

    public PhraseData getResolvedPhrase() {
        return resolvedPhrase;
    }

    public void setResolvedPhrase(PhraseData resolvedPhrase) {
        this.resolvedPhrase = resolvedPhrase;
        resolvedPhrase.templatePhrase = (PhraseData) this;
    }


    public ElementMatch getFirstElement() {
//        if(firstElement == null || firstElement.elementTypes.contains(ElementType.PLACE_HOLDER))
//            return null;
        return firstElement;
    }

    public ElementMatch getSecondElement() {
//        if( secondElement == null ||  secondElement.elementTypes.contains(ElementType.PLACE_HOLDER))
//            return null;
        return secondElement;
    }

    public ElementMatch getElementBeforeOperation() {
//        if( elementBeforeOperation == null ||  elementBeforeOperation.elementTypes.contains(ElementType.PLACE_HOLDER))
//            return null;
        return elementBeforeOperation;
    }

    public ElementMatch getElementAfterOperation() {
//        if(elementAfterOperation == null ||  elementAfterOperation.elementTypes.contains(ElementType.PLACE_HOLDER))
//            return null;
        return elementAfterOperation;
    }

    public List<ElementMatch> getElementMatchesBeforeAndAfterOperation() {
        if (elementBeforeOperation != null && elementAfterOperation != null)
            return List.of(elementBeforeOperation, elementAfterOperation);
        ElementMatch elementMatch1 = elementBeforeOperation == null ? new PlaceHolderMatch((PhraseData) this) : elementBeforeOperation;
        ElementMatch elementMatch2 = elementAfterOperation == null ? new PlaceHolderMatch((PhraseData) this) : elementAfterOperation;
        return List.of(elementMatch1, elementMatch2);
    }


    public List<ElementMatch> getElementMatchesProceedingOperation() {
        return elementMatchesProceedingOperation;
    }

    public void setElementMatchesProceedingOperation(List<ElementMatch> elementMatchesProceedingOperation) {
        this.elementMatchesProceedingOperation = elementMatchesProceedingOperation;
    }

    public List<ElementMatch> getElementMatchesFollowingOperation() {
        return elementMatchesFollowingOperation;
    }

    public void setElementMatchesFollowingOperation(List<ElementMatch> elementMatchesFollowingOperation) {
        this.elementMatchesFollowingOperation = elementMatchesFollowingOperation;
    }


    public boolean isNewContext() {
        return newContext;
    }

    public void setNewContext(boolean newContext) {
        this.newContext = newContext;
    }


    static int setConjunctionChain(PhraseData phraseData) {
        phraseData.chainStartPhrase = phraseData;
        phraseData.chainStart = phraseData.position;

        PhraseData nextResolvedPhrase = phraseData.getNextPhrase() == null ? null : phraseData.getNextPhrase().resolvePhrase();

        if (nextResolvedPhrase == null || nextResolvedPhrase.isSeparatorPhrase()) {
            phraseData.chainEnd = phraseData.position;
            return phraseData.chainEnd;
        }

        nextResolvedPhrase.chainStart = phraseData.chainStart;

        phraseData.chainEnd = setConjunctionChain(nextResolvedPhrase);
        String newConjunction = nextResolvedPhrase.conjunction.isBlank() ? phraseData.conjunction : nextResolvedPhrase.conjunction;
        phraseData.conjunction = newConjunction;
        nextResolvedPhrase.conjunction = newConjunction;
        return phraseData.chainEnd;
    }


    public boolean hasTerminationConditional() {
        PhraseData nextPhrase = (PhraseData) this;
        while (nextPhrase != null) {
            if (nextPhrase.isSeparatorPhrase())
                return false;
            if (nextPhrase.termination.equals('?'))
                return true;
            nextPhrase = nextPhrase.getNextPhrase();
        }
        return false;
    }


    public WebDriver getDriver() {

        if(driver == null)
            driver = getDefaultDriver();
        return driver;
    }

    public void setDriver(WebDriver driver) {
        this.driver = driver;
    }
}
