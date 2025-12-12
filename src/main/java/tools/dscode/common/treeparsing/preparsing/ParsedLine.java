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
        if (currentStep.inheritedLineData != null) {
            inheritedContextPhrases.addAll(currentStep.inheritedLineData.inheritedContextPhrases);
        }
    }


    public void runPhrases() {
        PhraseData phrase = phrases.get(startPhraseIndex);

        runPhraseFromLine(phrase);
    }

    public void runPhraseFromLine(PhraseData phrase) {


        System.out.println("\n@@inheritedContextPhrases1" + inheritedContextPhrases);
        phrase.runPhrase();
        System.out.println("@@inheritedContextPhrases2" + inheritedContextPhrases);
        if(phrase.nextPhrase == null)
        {
            System.out.println("Line complete");
            return;
        }
        branchPhrases = null;
        if (phrase.clones.isEmpty()) {
            runPhraseFromLine(phrase.nextPhrase);
        } else {
            for (PhraseData clone : phrase.clones) {
                runPhraseFromLine(clone);
            }
        }
    }
}
