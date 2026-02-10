package tools.dscode.common.treeparsing.preparsing;

import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
        PhraseData nextResolvedPhrase = phrase.runPhrase();

        if (phrase.repeatRootPhrase) {
            phrase.chainStartPhrase.repeatedChain.add(phrase);
        }

        if (nextResolvedPhrase == null) {
            System.out.println("Step completed: " + executedPhrases);
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
