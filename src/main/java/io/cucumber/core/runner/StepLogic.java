package io.cucumber.core.runner;

import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StepLogic {

    public static List<StepExtension> stepCloner(PhraseData inheritancePhrase , StepExtension stepExtension, DefinitionFlag... definitionFlags) {
        if (inheritancePhrase  == null || inheritancePhrase.branchedPhrases.isEmpty())
        {
            StepExtension clonedStep = (StepExtension) stepExtension.clone(inheritancePhrase);
            clonedStep.addDefinitionFlag(definitionFlags);
            return new ArrayList<>(List.of(clonedStep));
        }
        List<StepExtension> returnList = new ArrayList<>();

        for(PhraseData branchPhrase: inheritancePhrase.branchedPhrases)
        {
            StepExtension branchStep = (StepExtension) stepExtension.clone(branchPhrase);
            branchStep.addDefinitionFlag(definitionFlags);
            returnList.add(branchStep);
        }
        return returnList;
    }




}
