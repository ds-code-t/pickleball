package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.Collections;
import java.util.List;

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


    public ElementMatch getReplacementElement() {
        if(replacementElement != null) return replacementElement;
        PhraseData currentPhrase = null;
        ElementMatch returnElementMatch = null;

        if (elementMatcher.matches(ElementType.KEY_VALUE)) {
            currentPhrase = parentPhrase.getNextPhrase();
            while (currentPhrase != null) {
                returnElementMatch = currentPhrase.getElementMatches().stream().filter(e -> elementMatcher.matches(e.elementTypes)).findFirst().orElse(null);
                if (returnElementMatch != null) {
                    replacementElement = returnElementMatch;
                    return replacementElement;
                }
                if (currentPhrase.isOperationPhrase && currentPhrase.actionOperation != ActionOperations.SAVE)
                    break;
                currentPhrase = currentPhrase.getNextPhrase();
            }
            return replacementElement;
        } else {
            currentPhrase = parentPhrase.getPreviousPhrase();
            while (currentPhrase != null) {
                returnElementMatch = currentPhrase.getElementMatches().stream().filter(e -> elementMatcher.matches(e.elementTypes)).findFirst().orElse(null);
                if (returnElementMatch != null) {
                    replacementElement = returnElementMatch;
                    return replacementElement;
                }
                currentPhrase = currentPhrase.getPreviousPhrase();
            }
            return replacementElement;
        }

    }

}
