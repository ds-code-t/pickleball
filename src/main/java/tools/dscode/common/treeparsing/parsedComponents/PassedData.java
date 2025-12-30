package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.MatchNode;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.ActionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.AssertionOperations;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.Attempt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tools.dscode.common.treeparsing.parsedComponents.ElementType.FOLLOWING_OPERATION;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.NO_OPERATION;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.PRECEDING_OPERATION;


public abstract class PassedData {

    PhraseData chainStartPhrase;
    int chainStart;
    int chainEnd;

    boolean groupSeparator = false;
    boolean isChainStart = false;

    public PhraseData lastOperationPhrase;

    public boolean isSeparatorPhrase() {
        if (isNewContext() || getPreviousPhrase() == null || getPreviousPhrase().contextTermination || !assertionType.isBlank())
            return true;

        return lastOperationPhrase == null || lastOperationPhrase.equals(this);
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




        if (elementCount == 1) {
//            if (phraseType == null) {
            if (phraseType == null || (!getAssertion().isBlank() && getAssertionType().isBlank())) {
                if (lastOperationPhrase == null || lastOperationPhrase.equals(this)) {
                    if (hasTerminationConditional()) {
                        setConditional("if");
                        lastOperationPhrase = (PhraseData) this;
                        PhraseData currentPhrase = (PhraseData) this;
                        while (currentPhrase != null) {
                            if (currentPhrase.phraseType == null) {
                                currentPhrase.setAssertion("True");
                            }
                            if (currentPhrase.termination.equals('?'))
                                break;
                            currentPhrase = currentPhrase.getNextPhrase();
                        }
                    }
                }
            }
            if (phraseType == null) {
                if (lastOperationPhrase == null || lastOperationPhrase.equals(this)) {
                    PhraseData currentPhrase = getNextPhrase().getResolvedPhrase();
                    while (currentPhrase != null) {
                        if (currentPhrase.isOperationPhrase) {
                            if (!currentPhrase.getAction().isBlank()) {
                                setAction(currentPhrase.getAction());
                                operationIndex = firstElement.elementIndex + 1000;
                            } else if (!currentPhrase.getAssertion().isBlank()) {
                                setAssertion(currentPhrase.getAssertion());
                                operationIndex = firstElement.elementIndex + 1000;
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
                    } else if (!lastOperationPhrase.getAssertion().isBlank()) {
                        setAssertion(lastOperationPhrase.getAssertion());
                        operationIndex = 0;
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


//    public boolean isGroupStart() {
//
//        if (groupSeparator) {
//            lastOperationPhrase = isOperationPhrase ? (PhraseData) this : null;
//            return true;
//        }
//
//
//        PhraseData prevPhrase = getPreviousPhrase();
//        if (prevPhrase != null) {
//
//
//        }
//        while (prevPhrase != null) {
//            if (prevPhrase.groupSeparator || prevPhrase.lastOperationPhrase) {
//
//            }
//
//            prevPhrase = prevPhrase.getPreviousPhrase();
//        }
//
//        if (lastOperationPhrase == null)
//            return true;
//
//        if (phraseType != null && prevOperationPhrase.phraseType != phraseType)
//            return true;
//
//        if (!isOperationPhrase)
//            return false;
//
//        String conditionalOrAssertionType = (getAssertionType() + getConditional()).trim().toLowerCase();
//
//        return !conditionalOrAssertionType.isBlank() && !conditionalOrAssertionType.contains("termination");
//    }


//    public PhraseData phraseGroupStart;
//
//
//    public String phraseGroupConjunction = "";
//
//    private List<PhraseData> phraseGroup = new ArrayList<>();


//    public void processPhraseGroup() {
//
//    }


//    public List<ElementMatch> collectNextElements() {
//        List<ElementMatch> returnList = new ArrayList<>();
//        PhraseData nextPhrase = getNextPhrase();
//        while (nextPhrase != null) {
//            if (nextPhrase.isGroupStart())
//                return returnList;
//
//            if (nextPhrase.elementCount == 1) {
//
//            }
//
//            nextPhrase = nextPhrase.getNextPhrase();
//        }
//        return false;
//    }


//
//    public void addToPhraseGroup(PhraseData phraseData) {
//        PhraseData currentPhrase = (PhraseData) this;
//        PhraseData lastOperationPhrase = null;
//
//        if (phraseGroup.isEmpty()) {
//            while (currentPhrase != null) {
//                if (currentPhrase.isOperationPhrase) {
//
//                    if (lastOperationPhrase != null) {
//                        if (currentPhrase.isGroupStart())
//                            break;
//
//                    }
//
//                    if (!currentPhrase.conjunction.isBlank())
//                        phraseGroupConjunction = currentPhrase.conjunction;
//
//                    lastOperationPhrase = currentPhrase;
//                }
//
//
//                currentPhrase = currentPhrase.getNextPhrase();
//            }
//            if (conjunction.isBlank())
//                conjunction = "and";
//        }
//
//
//        phraseData.phraseGroupConjunction = phraseGroupConjunction;
//        phraseData.phraseGroupStart = (PhraseData) this;
//        phraseGroup.add(phraseData);
//    }


    private PhraseData previousPhrase;
    private PhraseData nextPhrase;

    private List<ElementMatch> elementMatches = new ArrayList<>();
    private List<ElementMatch> elementMatchesProceedingOperation = new ArrayList<>();
    private List<ElementMatch> elementMatchesFollowingOperation = new ArrayList<>();

//    private SearchContext currentSearchContext;

    public List<PhraseData> contextPhrases = new ArrayList<>();

    public String selectionType = "";


    private String conditional = "";


    public String body;
    public int phraseConditionalMode = 0;

    public boolean isOperationPhrase;
    public boolean separator;
//    public boolean missingData;

    public int elementCount;


    private ElementMatch firstElement = null;
    private ElementMatch secondElement = null;
    private ElementMatch elementBeforeOperation = null;
    private ElementMatch elementAfterOperation = null;


    private ElementMatch lastElement = null;

    public PhraseData resolvedPhrase;

    public Attempt.Result result;
    public List<PhraseData> resultPhrases = new ArrayList<>();

    public WebDriver webDriver = null;
    public List<PhraseData> branchedPhrases = new ArrayList<>();

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

    Integer operationIndex;


    public PhraseData getPreviousPhrase() {
        if (previousPhrase == null || previousPhrase.resolvedPhrase == null)
            return previousPhrase;
        return previousPhrase.resolvedPhrase;
    }

    public void setPreviousPhrase(PhraseData previousPhrase) {
        this.previousPhrase = previousPhrase;
    }

    public PhraseData getNextPhrase() {
        if (nextPhrase == null || nextPhrase.resolvedPhrase == null)
            return nextPhrase;
        return nextPhrase.resolvedPhrase;
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
        selectionType = elementMatches.isEmpty() ? "" : elementMatches.getFirst().selectionType;
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
                phraseType = PhraseData.PhraseType.ELEMENT_ONLY;
        }


        if (operationIndex != null) {
            for (ElementMatch em : elementMatches) {
                if (em.startIndex < operationIndex) {
                    em.elementTypes.add(PRECEDING_OPERATION);
                    elementMatchesProceedingOperation.add(em);
                } else if (em.startIndex > operationIndex) {
                    elementMatchesFollowingOperation.add(em);
                    em.elementTypes.add(FOLLOWING_OPERATION);
                }
            }
            elementBeforeOperation = elementMatchesProceedingOperation.isEmpty() ? null : elementMatchesProceedingOperation.getFirst();
            elementAfterOperation = elementMatchesFollowingOperation.isEmpty() ? null : elementMatchesFollowingOperation.getFirst();
        } else {
            elementMatches.forEach(em -> em.elementTypes.add(NO_OPERATION));
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
        if (phraseType == null)
            phraseType = PhraseData.PhraseType.ASSERTION;
        isOperationPhrase = true;
        this.assertion = assertion;
        return true;
    }


    public String getAssertionType() {
        return assertionType;
    }

    public void setAssertionType(String assertionType) {
        this.assertionType = assertionType;
        isOperationPhrase = true;
        phraseType = assertionType.startsWith("conditional") ? PhraseData.PhraseType.CONDITIONAL : PhraseData.PhraseType.ASSERTION;
    }

    public PhraseData getResolvedPhrase() {
        return resolvedPhrase;
    }

    public void setResolvedPhrase(PhraseData resolvedPhrase) {
        this.resolvedPhrase = resolvedPhrase;
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

        PhraseData nextResolvedPhrase = phraseData.getNextPhrase().resolvePhrase();

        if (nextResolvedPhrase.isSeparatorPhrase()) {
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


}
