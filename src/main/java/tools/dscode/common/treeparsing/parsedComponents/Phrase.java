package tools.dscode.common.treeparsing.parsedComponents;


import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.preparsing.LineData;

import static tools.dscode.common.domoperations.ParsedActions.executeAction;
import static tools.dscode.coredefinitions.GeneralSteps.getDriver;

public final class Phrase extends PhraseData {


    public Phrase(String inputText, Character delimiter, LineData parsedLine) {
        super(inputText, delimiter, parsedLine);
    }



    boolean shouldRun() {

        phraseConditionalMode = previousPhrase == null ? 0 : previousPhrase.phraseConditionalMode;

        if (conditional.startsWith("else")) {
            phraseConditionalMode = phraseConditionalMode * -1;
        }

        return phraseConditionalMode >= 0;




//        phraseConditionalMode = previousPhrase == null ? 0 : previousPhrase.phraseConditionalMode;
//        if (conditional.startsWith("else")) {
//            parsedLine.lineConditionalMode *= -1;
//            phraseConditionalMode = parsedLine.lineConditionalMode * -1;
//        }
//        phraseConditionalMode = phraseConditionalMode * -1;
//        return phraseConditionalMode >= 0;

    }

    @Override
    public void runPhrase() {
        parsedLine.executedPhrases.add(this);

        if (shouldRun()) {
            System.out.println("Running Phrase: " + this + (isClone ? " (clone)" : ""));
        }
        else {
            System.out.println("Skipping Phrase: " + this + (isClone ? " (clone)" : ""));
            return;
        }

        if (contextElement != null) {
            contextPhrases.add(this);
            return;
        }

//        parsedLine.startPhraseIndex = position;

        elementMatches.forEach(e -> { if(e.elementTypes.contains(ElementType.HTML_TYPE)) e.contextWrapper = new ContextWrapper(e);});

        if (previousPhrase != null && !previousPhrase.contextTermination) {
            contextPhrases.addAll(previousPhrase.contextPhrases);
        }

//        if (hasDOMInteraction) {
//            syncWithDOM();
//        }

        if (phraseType.equals(PhraseType.CONDITIONAL)) {
            runOperation();
        } else if (phraseType.equals(PhraseType.ASSERTION)) {
            runOperation();
//            executeAssertions(this);
        } else if (phraseType.equals(PhraseType.ACTION)) {
            runOperation();
//            executeAction(webDriver, this);
        } else if (phraseType.equals(PhraseType.CONTEXT)) {
            processContextPhrase();
        }
        if (contextTermination) {
            if (phraseType.equals(PhraseType.CONDITIONAL)) {
                parsedLine.lineConditionalMode = phraseConditionalMode;
            } else if (termination.equals(':')) {
                parsedLine.inheritedContextPhrases.add(contextPhrases);
                parsedLine.lineConditionalMode = phraseConditionalMode;
            } else {
                parsedLine.inheritedContextPhrases.removeLast();
            }
        }
    }


    void processContextPhrase() {

        if (firstElement.selectionType.isEmpty()) {
            contextPhrases.add(this);
        } else {
//            syncWithDOM();
            if (firstElement.getElementWrappers().isEmpty()) {
                if (!firstElement.selectionType.equals("any")) {
                    throw new RuntimeException("Failed to find WebElements for " + firstElement);
                }
                System.out.println("No elements match for " + firstElement + ", skipping subsequent phrases");
            }
            for (ElementWrapper elementWrapper : getWrappedElements()) {
                branchedPhrases.add(cloneWithElementContext(elementWrapper));
            }
            contextPhrases.add(this);
        }
    }





    public PhraseData cloneWithElementContext(ElementWrapper elementWrapper) {

        PhraseData clone = clonePhrase(previousPhrase);
        clone.contextElement = elementWrapper;
        clone.categoryFlags.add(ExecutionDictionary.CategoryFlags.ELEMENT_CONTEXT);
        return clone;
    }


    @Override
    public PhraseData clonePhrase(PhraseData previous) {
        Phrase clone = new Phrase(text, termination, parsedLine);
        clone.result = null;
        clone.isClone = true;
        clone.position = position;
//        clones.add(clone);
        clone.previousPhrase = previous;
        if (nextPhrase != null) {
            clone.nextPhrase = nextPhrase.clonePhrase(clone);
            clone.nextPhrase.previousPhrase = clone;
        }
        return clone;
    }

    public PhraseData resolvePhrase() {

        Phrase resolvedPhrase = new Phrase(text, termination, parsedLine);

        resolvedPhrase.position = position;
        resolvedPhrase.previousPhrase = previousPhrase;
        resolvedPhrase.nextPhrase = nextPhrase;
        return resolvedPhrase;
    }

    public PhraseData getNextResolvedPhrase() {

        PhraseData nextResolvedPhrase = nextPhrase.resolvePhrase();
        nextResolvedPhrase.previousPhrase = this;
        this.nextPhrase = nextResolvedPhrase;
        return nextResolvedPhrase;
    }





}
