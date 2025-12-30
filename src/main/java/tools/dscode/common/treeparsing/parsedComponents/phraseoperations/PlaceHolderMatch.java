package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.assertions.ValueWrapper;
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

    List<ValueWrapper> placeHolderValues;

    @Override
    public List<ValueWrapper> getValues() {
        if(placeHolderValues != null)
            return placeHolderValues;
        ElementMatch elementMatch = getReplacementElement();
        if (elementMatch == null)
            return null;
        return elementMatch.getValues();
    }


    public ElementMatch getReplacementElement() {
        PhraseData currentPhrase = null;
        ElementMatch returnElementMatch = null;

        if (elementMatcher.matches(ElementType.KEY_VALUE)) {
            currentPhrase = parentPhrase.getNextPhrase();
            while (currentPhrase != null) {
                returnElementMatch = currentPhrase.getElementMatches().stream().filter(e -> elementMatcher.matches(e.elementTypes)).findFirst().orElse(null);
                if (returnElementMatch != null)
                    return returnElementMatch;
                if (currentPhrase.isOperationPhrase && currentPhrase.actionOperation != ActionOperations.SAVE)
                    break;
                currentPhrase = currentPhrase.getNextPhrase();
            }
                placeHolderValues = Collections.singletonList(createValueWrapper("saved value"));
                return this;
        }
        else
        {
            currentPhrase = parentPhrase.getPreviousPhrase();
            while (currentPhrase != null) {
                returnElementMatch = currentPhrase.getElementMatches().stream().filter(e -> elementMatcher.matches(e.elementTypes)).findFirst().orElse(null);
                if (returnElementMatch != null)
                    return returnElementMatch;
                currentPhrase = currentPhrase.getPreviousPhrase();
            }
            return returnElementMatch;
        }

    }

}
