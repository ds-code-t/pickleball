package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.List;

public class PlaceHolderMatch extends ElementMatch {
    public PlaceHolderMatch(PhraseData phraseData) {
        super(phraseData);
    }

    @Override
    public String toString() {
        return "PLACEHOLDER ElementType" + (elementMatcher == null ? "" : elementMatcher);
    }

    @Override
    public List<ValueWrapper> getValues() {
        ElementMatch elementMatch = getReplacementElement();
        if (elementMatch == null)
            return null;
        return elementMatch.getValues();
    }

    public ElementMatch getReplacementElement() {
        PhraseData currentPhrase = null;
        ElementMatch returnElementMatch = null;

        if (elementMatcher.matches(ElementType.KEY_VALUE)) {
            currentPhrase = parentPhrase.nextPhrase;
            while (currentPhrase != null) {
                returnElementMatch = currentPhrase.elementMatches.stream().filter(e -> elementMatcher.matches(e.elementTypes)).findFirst().orElse(null);
                if (returnElementMatch != null)
                    return returnElementMatch;
                currentPhrase = currentPhrase.nextPhrase;
            }
            return returnElementMatch;
        }
        else
        {
            currentPhrase = parentPhrase.previousPhrase;
            while (currentPhrase != null) {
                returnElementMatch = currentPhrase.elementMatches.stream().filter(e -> elementMatcher.matches(e.elementTypes)).findFirst().orElse(null);
                if (returnElementMatch != null)
                    return returnElementMatch;
                currentPhrase = currentPhrase.previousPhrase;
            }
            return returnElementMatch;
        }


    }

}
