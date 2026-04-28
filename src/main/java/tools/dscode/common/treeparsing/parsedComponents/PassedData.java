package tools.dscode.common.treeparsing.parsedComponents;

import com.xpathy.XPathy;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.mappings.ParsingMap;
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
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.DATA_TYPE;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.FOLLOWING_OPERATION;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.HTML_ELEMENT;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.PRECEDING_OPERATION;
import static tools.dscode.common.treeparsing.parsedComponents.ElementType.VALUE_TYPE;
import static tools.dscode.common.treeparsing.parsedComponents.PhraseData.PhraseType.ELEMENT_ONLY;
import static tools.dscode.coredefinitions.BrowserSteps.getCurrentDriver;


public abstract class PassedData {
    public boolean wasPhraseSkipped = false;
    ParsingMap phraseParsingMap;

    private PhraseData previousPhrase;
    private PhraseData nextPhrase;

//    public ElementMatch browserElement;
    protected List<ElementMatch> elementMatches = new ArrayList<>();
//    private List<ElementMatch> elementMatchesProceedingOperation = new ArrayList<>();
//    private List<ElementMatch> elementMatchesFollowingOperation = new ArrayList<>();
//    private final List<ElementMatch> valueTypeEntryElementMatches = new ArrayList<>();

//    private SearchContext currentSearchContext;

//    public List<PhraseData> contextPhrases = new ArrayList<>();

//    public String selectionType = "";

    public boolean hasNo;


    private String conditional = "";


    public String body;
    public int phraseConditionalMode = 1;

    public boolean isOperationPhrase;
    public boolean separator;
//    public boolean missingData;

//    public int elementCount;


//    private ElementMatch firstElement = null;
//    private ElementMatch secondElement = null;
//    private ElementMatch elementBeforeOperation = null;
//    private ElementMatch elementAfterOperation = null;


//    private ElementMatch lastElement = null;

    private PhraseData resolvedPhrase;
    protected PhraseData templatePhrase;

    public Attempt.Result result;
    public List<ElementMatch> resultElements = new ArrayList<>();
//    public List<PhraseData> resultPhrases = new ArrayList<>();

    private WebDriver driver = null;
    public List<PhraseData> branchedPhrases = new ArrayList<>();

    public ElementWrapper contextElement;

    public boolean noExecution = false;


//    public boolean contextTermination;

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
    //    public List<Component> components;
    public Set<ExecutionDictionary.CategoryFlags> categoryFlags = new HashSet<>();
    public PhraseData.PhraseType phraseType;

    public XPathy contextXPathy;
//    public XPathy contextXPathyWithIndex;

    //    public String keyName = "";
    public boolean isClone = false;

    public int operationIndex = 0;


    public PhraseData getPreviousPhrase() {
        PhraseData prevPhrase = previousPhrase == null ? ((PhraseData) this).parsedLine.inheritedPhrase : previousPhrase;
        if (prevPhrase == null || prevPhrase.getResolvedPhrase() == null)
            return prevPhrase;
        return prevPhrase.getResolvedPhrase();
    }

    public PhraseData getResolvedPhrase() {
        return resolvedPhrase;
    }

    public List<ElementMatch> getValueTypeEntryElementMatches() {
        List<ElementMatch> matches = new ArrayList<>(elementMatches.stream().filter(e -> e.elementTypes.contains(VALUE_TYPE)).toList());
        if (matches.isEmpty() && getPreviousPhrase() != null) {
            return getPreviousPhrase().getValueTypeEntryElementMatches();
        }
        return matches;
    }

    public void setResolvedPhrase(PhraseData resolvedPhrase) {
        this.resolvedPhrase = resolvedPhrase;
//        this.resolvedPhrase.phraseParsingMap = phraseParsingMap;
        this.resolvedPhrase.phraseNode = phraseNode;
        resolvedPhrase.templatePhrase = (PhraseData) this;
    }

//    public final List<ElementMatch> webElementMatches = new ArrayList<>();
//    public final List<ElementMatch> htmlElementMatches = new ArrayList<>();

    public List<ElementMatch> getClosestWebElementMatches() {
        List<ElementMatch> matches = new ArrayList<>(elementMatches.stream().filter(e -> e.elementTypes.contains(HTML_ELEMENT)).toList());
        if (matches.isEmpty() && getPreviousPhrase() != null) {
            return getPreviousPhrase().getValueTypeEntryElementMatches();
        }
        return matches;
    }

    public List<ElementMatch> getElementMatchesPrecedingOperation() {
        List<ElementMatch> matches = new ArrayList<>(elementMatches.stream().filter(e -> e.startIndex < operationIndex).toList());
        if (matches.isEmpty() && getPreviousPhrase() != null) {
            return getPreviousPhrase().getValueTypeEntryElementMatches();
        }
        matches.forEach(e -> e.elementTypes.add(PRECEDING_OPERATION));
        return matches;
    }

    public List<ElementMatch> getElementMatchesFollowingOperation() {
        List<ElementMatch> matches = new ArrayList<>(elementMatches.stream().filter(e -> e.startIndex > operationIndex).toList());
        if (matches.isEmpty() && getPreviousPhrase() != null) {
            return getPreviousPhrase().getValueTypeEntryElementMatches();
        }
        matches.forEach(e -> e.elementTypes.add(FOLLOWING_OPERATION));
        return matches;
    }

    public ElementMatch getBrowserElement() {
        return elementMatches.stream().filter(e -> e.elementTypes.contains(BROWSER)).findFirst().orElse(null);
    }
    public ElementMatch getFirstElement() {
        return elementMatches.isEmpty() ? null : elementMatches.getFirst();
    }

    public ElementMatch getSecondElement() {
        return elementMatches.size() <2 ? null : elementMatches.get(1);
    }


    public List<ElementMatch> getElementMatchesBeforeAndAfterOperation() {
        getElementMatchesPrecedingOperation();
        ElementMatch elementMatch1 = elementMatches.stream().filter(e -> e.startIndex > operationIndex).findFirst().orElse(new PlaceHolderMatch((PhraseData) this));
        ElementMatch elementMatch2 = elementMatches.stream().filter(e -> e.startIndex > operationIndex).findFirst().orElse(new PlaceHolderMatch((PhraseData) this));
        return List.of(elementMatch1, elementMatch2);
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
//        elementCount = elementMatches.size();
    }
//
//    public void setElementGroupings() {
//        elementMatchesFollowingOperation = new ArrayList<>();
//        elementMatchesProceedingOperation = new ArrayList<>();
//        for (ElementMatch em : elementMatches) {
//            if (em.elementTypes.contains(HTML_ELEMENT)) {
//                htmlElementMatches.add(em);
//            } else if (em.elementTypes.contains(BROWSER)) {
//                browserElement = em;
//            }
//            if (em.startIndex < operationIndex) {
//                em.elementTypes.add(PRECEDING_OPERATION);
//                elementMatchesProceedingOperation.add(em);
//            } else if (em.startIndex > operationIndex) {
//                elementMatchesFollowingOperation.add(em);
//                em.elementTypes.add(FOLLOWING_OPERATION);
//            }
//        }
//        if (elementMatchesProceedingOperation.isEmpty()) {
//            elementMatchesFollowingOperation.forEach(em -> {
//                if (em.elementTypes.contains(VALUE_TYPE)) {
//                    valueTypeEntryElementMatches.add(em);
//                } else if (em.elementTypes.contains(HTML_ELEMENT)) {
//                    webElementMatches.add(em);
//                }
//            });
//        }
//        if (webElementMatches.isEmpty()) {
//            elementMatchesProceedingOperation.forEach(em -> {
//                if (em.elementTypes.contains(HTML_ELEMENT)) {
//                    webElementMatches.add(em);
//                }
//            });
//        }
//        if (valueTypeEntryElementMatches.isEmpty()) {
//            elementMatchesProceedingOperation.forEach(em -> {
//                if (em.elementTypes.contains(VALUE_TYPE)) {
//                    valueTypeEntryElementMatches.add(em);
//                }
//            });
//        }
//
//        elementBeforeOperation = elementMatchesProceedingOperation.isEmpty() ? null : elementMatchesProceedingOperation.getFirst();
//        elementAfterOperation = elementMatchesFollowingOperation.isEmpty() ? null : elementMatchesFollowingOperation.getFirst();
//    }

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
        phraseType = assertionType.equalsIgnoreCase("conditional") ? PhraseData.PhraseType.CONDITIONAL : PhraseData.PhraseType.ASSERTION;
        return true;
    }


    public ElementMatch getDataElement() {
        return elementMatches.stream().filter(e -> e.elementTypes.contains(DATA_TYPE)).findFirst().orElse(null);
    }






    public boolean isNewContext() {
        return newContext;
    }

    public void setNewContext(boolean newContext) {
        this.newContext = newContext;
    }


    public WebDriver getDriver() {

        if (driver == null)
            driver = getCurrentDriver();
        return driver;
    }

    public void setDriver(WebDriver driver) {
        this.driver = driver;
    }
}
