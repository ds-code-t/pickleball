package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepBase;
import io.cucumber.core.runner.StepData;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.Collection;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;

public final class ParsedLine extends LineData {

    public ParsedLine(String input) {
        this(input, List.of(',', ';', ':', '.', '!', '?'));
    }

    public ParsedLine(String input, Collection<Character> delimiters) {
        super(input, delimiters);
        StepBase currentStep = getRunningStep();
        currentStep.lineData = this;
        while (true) {
            currentStep = currentStep.parentStep;
            if (currentStep == null)
                break;
            if (currentStep.lineData != null)
                inheritedLineData = currentStep.lineData;
        }
        if (inheritedLineData != null) {
            inheritedContextPhrases.addAll(inheritedLineData.inheritedContextPhrases);
        }
    }



    public void runPhrases() {
        PhraseData phrase = phrases.get(startPhraseIndex);
        runPhraseFromLine(phrase);
    }

    public void runPhraseFromLine(PhraseData phrase) {
        System.out.println("@@runPhraseFromLine " + phrase);
        phrase.runPhrase();
        if (phrase.clones.isEmpty()) {
            if (phrase.nextPhrase != null) {
                runPhraseFromLine(phrase.nextPhrase);
            }
        } else {
            for (PhraseData clone : phrase.clones) {
                runPhraseFromLine(clone);
            }
        }
    }
}
