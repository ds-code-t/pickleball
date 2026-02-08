package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepData;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
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
        System.out.println("@@runPhrases1 " + this);
        PhraseData phrase = phrases.get(startPhraseIndex);
        System.out.println("@@phrase= " + phrase);
        runPhraseFromLine(updateChainAndInheritances(phrase.resolvePhrase()));
    }


    @Override
    public void runPhraseFromLine(PhraseData phrase) {
        System.out.println("@@runPhraseFromLine - " + phrase);
        PhraseData nextResolvedPhrase = phrase.runPhrase();
        System.out.println("@@nextResolvedPhrase - " + nextResolvedPhrase);

        if (nextResolvedPhrase == null) {
            System.out.println("Step completed: " + executedPhrases);
            return;
        }
        if (phrase.shouldRepeatPhrase) {
//            while (true) {
//                PhraseData repeatedPhraseClone = phrase.cloneRepeatedPhrase();
//                runPhraseFromLine(repeatedPhraseClone);
//                if (repeatedPhraseClone.phraseConditionalMode < 1) {
//                    break;
//                }
//            }
        } else if (phrase.branchedPhrases.isEmpty()) {
            runPhraseFromLine(nextResolvedPhrase);
        } else {
//            phrase.resolveResults();
            for (PhraseData clone : phrase.branchedPhrases) {
                System.out.println("@@running-branche: " + clone);
                runPhraseFromLine(clone);
            }
        }
    }
}
