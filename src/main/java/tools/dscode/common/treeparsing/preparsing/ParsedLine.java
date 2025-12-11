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
        System.out.println("@@starting Phrase: " + phrase);
        runPhraseFromLine(phrase);
    }

    public void runPhraseFromLine(PhraseData phrase) {
        System.out.println("@@runPhraseFromLine " + phrase + " , isclone? " + phrase.isClone );
        System.out.println("@@-clones " + phrase.clones.size());
        System.out.println("@@-phrase.contextElement " + phrase.contextElement);
        phrase.runPhrase();
//        if (phrase.contextElement == null) {
//            phrase.runPhrase();
//        } else {
//            phrase.contextPhrases.add(phrase);
//            if(phrase.nextPhrase != null)
//            {
//                phrase.nextPhrase.previousPhrase = phrase;
//            }
//        }
        if (phrase.clones.isEmpty()) {
            System.out.println("@@sTrying nextPhrase:  " + phrase.nextPhrase + " , " + phrase.skipNextPhrase);
            if (phrase.nextPhrase != null && !phrase.skipNextPhrase) {
                System.out.println("@@running nextPhrase: " + phrase.nextPhrase);
                System.out.println("@@phrase.nextPhrase.previousPhrase: " + phrase.nextPhrase.previousPhrase);
                if (phrase.nextPhrase.previousPhrase != null) {
                    System.out.println("@@phrase.nextPhrase.previousPhrase.contextPhrases.contextPhrases: " + phrase.nextPhrase.previousPhrase.contextPhrases);
                }
                runPhraseFromLine(phrase.nextPhrase);
            }
        } else {
            System.out.println("@@CLONES: " + phrase.clones.size());
            for (PhraseData clone : phrase.clones) {
                System.out.println("@@running clone: " + clone);
                System.out.println("@@clone.contextElement: " + clone.contextElement);
                runPhraseFromLine(clone);
            }
        }
    }
}
