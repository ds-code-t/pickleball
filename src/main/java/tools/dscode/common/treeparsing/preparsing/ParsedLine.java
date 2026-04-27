package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.assertions.AssertionChain;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.assertions.AssertionChain.isAssertionChainBorder;
import static tools.dscode.common.reporting.logging.LogForwarder.stepInfo;



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
        if( stepExtension.overridePhrase != null) {
            runPhraseFromLine( stepExtension.overridePhrase);
            return;
//            phrases.clear();
//            phrases.add(stepExtension.overridePhrase);
//            startPhraseIndex = 0;
//            runPhraseFromLine(stepExtension.overridePhrase);
        }
        PhraseData phrase = phrases.get(startPhraseIndex);
        runPhraseFromLine(phrase.resolvePhrase());
    }


    @Override
    public PhraseData runPhraseFromLine(PhraseData phrase) {
        System.out.println("@phrase.untilPhrase: " + phrase.untilPhrase);
//        if(phrase.parsedLine.isAssertionChainStep)
//            phrase.untilPhrase = false;
        if (!phrase.getAssertion().isBlank() && phrase.assertionChainMembership == null && phrase.assertionChain==null) {
            if(phrase.getAssertionType().isBlank())
                phrase.setConditional("if");
            phrase.assertionChain = new AssertionChain(phrase);
            PhraseData currentPhrase = phrase;
            while (true) {
                phrase.assertionChain.addAssertionPhrase(currentPhrase);
                currentPhrase = currentPhrase.getNextPhrase();
//                currentPhrase = currentPhrase.getNextPhrase();

                if (isAssertionChainBorder(currentPhrase)) {
                    phrase.setNextPhrase(currentPhrase);
                    if (currentPhrase != null) {
                        currentPhrase.setPreviousPhrase(phrase);
                    }
                    break;
                }
            }
            phrase.termination = phrase.assertionChain.phraseChain.getLast().termination;

            if(phrase.untilPhrase && phrase.isContextTermination())
            {
                if (phrase.termination.equals(':') || phrase.termination.equals('?')) {
                    phrase.parsedLine.inheritancePhrases.add(phrase);
                    return phrase;
                }
            }
        }


        StepExtension currentStep = getRunningStep();
        Entry stepEntry = currentStep == null ? null : currentStep.stepEntry;
        getCurrentScenarioState().currentPhrase = (tools.dscode.common.treeparsing.parsedComponents.Phrase) phrase;

        if (stepEntry != null) {
            phrase.phraseEntry = getRunningStep().stepEntry.logWithType("PHRASE", phrase.toString()).start();
        }
        PhraseData nextResolvedPhrase = phrase.runPhrase();

        if (phrase.phraseEntry != null)
            phrase.phraseEntry.stop();

        getCurrentScenarioState().currentPhrase = null;

//        if (phrase.repeatRootPhrase) {
//            phrase.chainStartPhrase.repeatedChain.add(phrase);
//        }

        if (phrase.assertionChainMembership != null) {;
            phrase.assertionChainMembership.setPhraseIndex(phrase);
            if(phrase.isChainedAssertion)
                return phrase;
        }

        if (nextResolvedPhrase == null) {
            stepInfo("Step completed: " + executedPhrases);
            return phrase;
        }

//        if (phrase.repeatRootPhrase) {
//            if (nextResolvedPhrase.chainStartPhrase == null || nextResolvedPhrase.chainStartPhrase != phrase.chainStartPhrase) {
//                PhraseData cloneChainStart;
//                do {
//                    cloneChainStart = phrase.cloneRepeatedChain();
//                    runPhraseFromLine(cloneChainStart);
//                } while (cloneChainStart.phraseConditionalMode == 1);
//            }
//        } else
        if (phrase.branchedPhrases.isEmpty()) {
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
