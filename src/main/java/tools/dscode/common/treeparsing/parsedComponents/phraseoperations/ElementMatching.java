package tools.dscode.common.treeparsing.parsedComponents.phraseoperations;

import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ElementMatching {

    public static List<ElementMatch> processElementMatches(PhraseData phraseData,  List<ElementMatch> inputElementMatches, ElementMatcher... elementMatchers) {
        List<ElementMatch> elementMatches = new ArrayList<>(inputElementMatches);
        removeExcessPlaceHolders(elementMatches, elementMatchers.length);
        for (int i = 0; i < elementMatchers.length; i++) {
            ElementMatcher elementMatcher = elementMatchers[i];
            ElementMatch elementMatch = i < elementMatches.size() ? elementMatches.get(i) : null;
            if (elementMatch == null) {
                elementMatches.add(createPlaceHolderElementMatch(phraseData, elementMatcher));
            } else if (elementMatch.isPlaceHolder()) {
                elementMatch.elementMatcher = elementMatcher;
            } else if (!elementMatcher.matches(elementMatch.elementTypes)) {
                elementMatches.add(i, createPlaceHolderElementMatch(phraseData ,elementMatcher));
            }
        }

        elementMatches.removeIf(e -> e.isPlaceHolder() && e.elementMatcher == null);

        if (elementMatches.size() != elementMatchers.length) {
            throw new RuntimeException("Operation expected " + elementMatchers.length + " elements, but found " + elementMatches.size() + ". " + elementMatches);
        }

        return elementMatches;
    }


    public static void removeExcessPlaceHolders(List<ElementMatch> elementMatches, int correctSize) {
        while (elementMatches.size() > correctSize) {
            ElementMatch placeholder = elementMatches.stream().filter(e -> e.isPlaceHolder() && e.elementMatcher == null).findAny().orElse(null);
            if (placeholder == null)
                break;
            elementMatches.remove(placeholder);
        }
    }


    public static ElementMatch createPlaceHolderElementMatch(PhraseData phraseData, ElementMatcher elementMatcher) {
        PlaceHolderMatch placeHolder = new PlaceHolderMatch(phraseData);
        placeHolder.elementMatcher = elementMatcher;
        return placeHolder;
    }




}
