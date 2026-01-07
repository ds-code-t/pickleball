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

    public void runPhraseFromLine(PhraseData phrase) {
        PhraseData nextResolvedPhrase = phrase.runPhrase();
        if (nextResolvedPhrase == null) {
//            phrase.resolveResults();
            System.out.println("Step completed: " + executedPhrases);
            return;
        }
        if (phrase.branchedPhrases.isEmpty()) {
            runPhraseFromLine(nextResolvedPhrase);
        } else {
//            phrase.resolveResults();
            for (PhraseData clone : phrase.branchedPhrases) {
                runPhraseFromLine(clone);
            }
        }
    }
}
