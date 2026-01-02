package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.Collections;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;

public class PlaceHolderMatch extends ElementMatch {
    public PlaceHolderMatch(PhraseData phraseData) {
        super(phraseData);
    }

    @Override
    public String toString() {
        return "PLACEHOLDER ElementType" + (elementMatcher == null ? "" : elementMatcher);
    }

//    List<ValueWrapper> placeHolderValues;

    ElementMatch replacementElement = null;

    @Override
    public List<ValueWrapper> getValues() {
        getReplacementElement();
        if(replacementElement == null) return null;
        return replacementElement.getValues();
    }

    public List<ElementWrapper> getElementWrappers() {
        getReplacementElement();
        if(replacementElement == null) return null;
        return replacementElement.getElementWrappers();
    }


    public void getReplacementElement() {
        if(replacementElement != null) return;
        PhraseData currentPhrase = null;

        if (elementMatcher.matches(ElementType.KEY_VALUE)) {
            currentPhrase = parentPhrase.getNextPhrase();
            while (currentPhrase != null) {
                replacementElement = currentPhrase.getElementMatches().stream().filter(e -> elementMatcher.matches(e.elementTypes)).findFirst().orElse(null);
                if (replacementElement != null) {
                    return;
                }
                if (currentPhrase.isOperationPhrase && currentPhrase.actionOperation != ActionOperations.SAVE)
                    break;
                currentPhrase = currentPhrase.getNextPhrase();
            }
        } else {
            currentPhrase = getPreviousOrInheritedPhrase(parentPhrase);
            while (currentPhrase != null) {
                replacementElement = currentPhrase.getElementMatches().stream().filter(e -> elementMatcher.matches(e.elementTypes)).findFirst().orElse(null);
                if (replacementElement != null) {
                    return;
                }
                currentPhrase = getPreviousOrInheritedPhrase(currentPhrase);
            }
        }

    }

    public PhraseData getPreviousOrInheritedPhrase(PhraseData phraseData) {
        if(phraseData.getPreviousPhrase() !=null)
            return phraseData.getPreviousPhrase();
        StepExtension stepExtension = getRunningStep();
        if(stepExtension.parentStep != null && stepExtension.parentStep.isDynamicStep &&  !stepExtension.parentStep.lineData.executedPhrases.isEmpty()){
            return stepExtension.parentStep.lineData.executedPhrases.getLast();
        }

        for (int i = phraseData.parsedLine.inheritedContextPhrases.size() - 1; i >= 0; i--) {
            List<PhraseData> inner = phraseData.parsedLine.inheritedContextPhrases.get(i);
            if (!inner.isEmpty()) {
                return inner.getLast();
            }
        }
        return null;
    }

}
