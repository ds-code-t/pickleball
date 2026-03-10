package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;
import static tools.dscode.common.treeparsing.parsedComponents.Phrase.updateChainAndInheritances;

public final class ParsedLine extends LineData {

    public ParsedLine() {
        this("", new ArrayList<>());
    }

    public ParsedLine(String input) {
        this(input, List.of(',', ';', ':', '.', '!', '?'));
    }

    public ParsedLine(String input, Collection<Character> delimiters) {
        super(input, delimiters);
    }

    @Override
    public void runPhrases() {
        PhraseData phrase = phrases.get(startPhraseIndex);
        runPhraseFromLine(updateChainAndInheritances(phrase.resolvePhrase()));
    }


    @Override
    public PhraseData runPhraseFromLine(PhraseData phrase) {
        StepExtension currentStep  = getRunningStep();
        Entry stepEntry  = currentStep == null  ? null :  currentStep.stepEntry;
        getCurrentScenarioState().currentPhrase = (tools.dscode.common.treeparsing.parsedComponents.Phrase) phrase;

        if(stepEntry != null) {
            phrase.phraseEntry = getRunningStep().stepEntry.logWithType("PHRASE", phrase.toString()).start();
        }

        PhraseData nextResolvedPhrase = phrase.runPhrase();

        if( phrase.phraseEntry != null)
            phrase.phraseEntry.stop();

        getCurrentScenarioState().currentPhrase = null;

        if (phrase.repeatRootPhrase) {
            phrase.chainStartPhrase.repeatedChain.add(phrase);
        }

        if (nextResolvedPhrase == null) {
            stepInfo("Step completed: " + executedPhrases);
            return phrase;
        }

        if (phrase.repeatRootPhrase) {
            if(nextResolvedPhrase.chainStartPhrase == null || nextResolvedPhrase.chainStartPhrase != phrase.chainStartPhrase) {
                PhraseData cloneChainStart;
                do
                {
                    cloneChainStart = phrase.cloneRepeatedChain();
                    runPhraseFromLine(cloneChainStart);
                } while(cloneChainStart.phraseConditionalMode == 1);
            }
        }
        else if (phrase.branchedPhrases.isEmpty()) {
            runPhraseFromLine(nextResolvedPhrase);
        } else {
            for (PhraseData clone : phrase.branchedPhrases) {
                runPhraseFromLine(clone);
            }
        }
        return phrase;
    }

//    public static PhraseData runRepeatChain(PhraseData phrase) {
//        PhraseData cloneChainStart = phrase.cloneRepeatedChain();
//        return (phrase.parsedLine.runPhraseFromLine(cloneChainStart).phraseConditionalMode < 1);
//    }


}
