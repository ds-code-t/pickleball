package tools.dscode.common.treeparsing.preparsing;

import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public final class ParsedLine extends LineData {

    public ParsedLine() {
        this("", new ArrayList<>());
    }

    public ParsedLine(String input) {
        this(input, List.of(',', ';', ':', '.', '!', '?'));
    }

    public ParsedLine(String input, Collection<Character> delimiters) {
        super(input, delimiters);
//        StepBase currentStep = getRunningStep();
//        currentStep.lineData = this;
//        if (currentStep.inheritedLineData != null) {
//            inheritedContextPhrases.addAll(currentStep.inheritedLineData.inheritedContextPhrases);
//        }
    }

    @Override
    public void runPhrases() {
        PhraseData phrase = phrases.get(startPhraseIndex);
        runPhraseFromLine(phrase.resolvePhrase());
    }

    public void runPhraseFromLine(PhraseData phrase) {
        phrase.runPhrase();
        if (phrase.nextPhrase == null) {
            System.out.println("Step completed: " + executedPhrases);
            return;
        }
        if (phrase.branchedPhrases.isEmpty()) {
            runPhraseFromLine(phrase.getNextResolvedPhrase());
        } else {
            for (PhraseData clone : phrase.branchedPhrases) {
                runPhraseFromLine(clone);
            }
        }
    }
}
