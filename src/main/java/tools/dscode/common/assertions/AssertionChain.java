package tools.dscode.common.assertions;

import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.parsedComponents.phraseoperations.Attempt;

import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.reporting.logging.LogForwarder.closestEntryToPhrase;
import static tools.dscode.common.reporting.logging.LogForwarder.phraseInfo;
import static tools.dscode.common.treeparsing.parsedComponents.Phrase.copyPhraseWithModifications;
import static tools.dscode.common.treeparsing.parsedComponents.PhraseData.PhraseType.ACTION;
import static tools.dscode.common.treeparsing.parsedComponents.PhraseData.PhraseType.CONTEXT;
import static tools.dscode.common.treeparsing.parsedComponents.PhraseData.PhraseType.ELEMENT_ONLY;

public class AssertionChain {

    public String toString() {
        return "AssertionChain: " + phraseChain.size() + " , " + phraseChain;
    }

    PhraseData parentPhrase;

    public AssertionChain(PhraseData phrase) {
        if(phrase.getAssertionType().isBlank())
            phrase.setConditional("if");
        parentPhrase = phrase;
    }

    public boolean conjunctionAnd = true;

    public int phraseIndex = 0;

    public void setPhraseIndex(PhraseData phrase) {
        phraseIndex = cloneExecutionChain.indexOf(phrase) + 1;
    }

    public List<PhraseData> phraseChain = new ArrayList<>();
    public List<PhraseData> cloneExecutionChain;

    public void addAssertionPhrase(PhraseData phrase) {
//        phrase.untilPhrase = false;
        phrase = cloneAssertionPhrase(phrase);
//        if(phrase.isContextTermination()) {
//            parentPhrase.termination = phrase.termination;
//            phrase.termination = ',';
//        }
//        phrase.setConditional(parentPhrase.getConditional());
        phrase.assertionChainMembership = this;
        if(phrase.phraseType != CONTEXT)
            phrase.isChainedAssertion = true;
        if(!phrase.conjunction.isBlank())
            conjunctionAnd = phrase.conjunction.equals("and");
        phraseChain.add(phrase);
    }

    public Boolean chainStatus;
    public RuntimeException exception = null;

    public void executeAssertionChain() {

        cloneExecutionChain = phraseChain.stream().map(AssertionChain::cloneAssertionPhrase).toList();

//        String assertionType =  cloneExecutionChain.getFirst().getAssertionType().isBlank() ? "if" :  cloneExecutionChain.getFirst().getAssertionType();




        PhraseData lastPhrase = null;
        for(PhraseData phraseData: cloneExecutionChain)
        {
            if(lastPhrase!=null)
            {
                phraseData.setPreviousPhrase(lastPhrase);
                lastPhrase.setNextPhrase(phraseData);
            }
            lastPhrase = phraseData;
//            phraseData.setAssertionType(assertionType);
        }

        runChainPhrases();
        phraseInfo("Assertion chain " + this + " evaluates to: " + chainStatus);

        parentPhrase.result = new Attempt.Result(chainStatus, exception);

    }
    public void runChainPhrases() {
        PhraseData currentPhrase;

        while(phraseIndex< cloneExecutionChain.size())
        {
            currentPhrase = cloneExecutionChain.get(phraseIndex);
            if(currentPhrase.isContextTermination()) {
                currentPhrase.termination = ',';
            }
//            currentPhrase = cloneAssertionPhrase(currentPhrase);
            currentPhrase.parsedLine.runPhraseFromLine(currentPhrase);

            if(currentPhrase.result.failed())
            {
                chainStatus  = null;
                exception = new RuntimeException("Assertion phrase: '" + currentPhrase + "' caused Exception" , currentPhrase.result.error());
                throw exception;
            }
            boolean result = (boolean) currentPhrase.result.value();
            closestEntryToPhrase().info("Phrase '" + currentPhrase.resolvedText + "' evaluated to: " + result);
            if(result && !conjunctionAnd) {
                chainStatus = true;
                return;
            }
            if(!result && conjunctionAnd) {
                chainStatus = false;
                return;
            }
            chainStatus = result;
        }
    }



    public static boolean isAssertionChainBorder(PhraseData currentPhrase) {
        if (currentPhrase == null)
            return true;
        if (currentPhrase.getPreviousPhrase().isContextTermination() ||  !currentPhrase.getAssertionType().isBlank() || currentPhrase.phraseType == ACTION)
            return true;
        if (currentPhrase.phraseType == CONTEXT) {
            return isAssertionChainBorder(currentPhrase.getNextPhrase());
        }
        return currentPhrase.phraseType != ELEMENT_ONLY && currentPhrase.getAssertion().isBlank();
    }

    public static PhraseData cloneAssertionPhrase(PhraseData phrase)  {
        PhraseData clonePhrase = copyPhraseWithModifications((Phrase) phrase);
        clonePhrase.untilPhrase = false;
        return clonePhrase;
    }

}
