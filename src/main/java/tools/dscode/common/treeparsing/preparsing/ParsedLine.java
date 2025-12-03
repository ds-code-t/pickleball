package tools.dscode.common.treeparsing.preparsing;

import io.cucumber.core.runner.StepData;
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
        StepData currentStep = getRunningStep();
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

    public void runPhrases()
    {
        for (PhraseData phrase : phrases) {
            phrase.runPhrase();
        }
    }
}
