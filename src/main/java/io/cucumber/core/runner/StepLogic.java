package io.cucumber.core.runner;

import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.List;

public class StepLogic {

    public static List<StepExtension> stepCloner(StepExtension stepExtension) {
        PhraseData inheritedPhrase = stepExtension.lineData.inheritedPhrase;
        if (inheritedPhrase == null || inheritedPhrase.branchedPhrases.isEmpty())
        {
            return new ArrayList<>(List.of((StepExtension) stepExtension.clone()));
        }


        List<StepExtension> returnList = new ArrayList<>();

        for(PhraseData branchPhrase: inheritedPhrase.branchedPhrases)
        {
            StepExtension branchStep = (StepExtension) stepExtension.clone();
            branchStep.lineData.inheritancePhrase = branchPhrase;
            returnList.add(branchStep);
        }

        return returnList;
    }

}
