package io.cucumber.core.runner;

import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.List;

public class StepLogic {

    public static List<StepExtension> stepCloner( PhraseData inheritancePhrase , StepExtension stepExtension) {
        if (inheritancePhrase  == null || inheritancePhrase.branchedPhrases.isEmpty())
        {
            return new ArrayList<>(List.of((StepExtension) stepExtension.clone(inheritancePhrase)));
        }

        List<StepExtension> returnList = new ArrayList<>();

        for(PhraseData branchPhrase: inheritancePhrase.branchedPhrases)
        {
            StepExtension branchStep = (StepExtension) stepExtension.clone(branchPhrase);
            returnList.add(branchStep);
        }
        return returnList;
    }




}
