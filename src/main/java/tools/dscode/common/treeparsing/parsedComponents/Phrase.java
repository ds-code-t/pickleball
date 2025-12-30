package tools.dscode.common.treeparsing.parsedComponents;


import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.preparsing.LineData;


public final class Phrase extends PhraseData {


    public Phrase(String inputText, Character delimiter, LineData parsedLine) {
        super(inputText, delimiter, parsedLine);
    }


    boolean shouldRun() {

        phraseConditionalMode = getPreviousPhrase() == null ? 0 : getPreviousPhrase().phraseConditionalMode;

        if (getConditional().startsWith("else")) {
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
        } else {
            System.out.println("Skipping Phrase: " + this + (isClone ? " (clone)" : ""));
            return;
        }

        if (contextElement != null) {
            contextPhrases.add(this);
            return;
        }

//        parsedLine.startPhraseIndex = position;

        getElementMatches().forEach(e -> {
            if (e.elementTypes.contains(ElementType.HTML_TYPE)) e.contextWrapper = new ContextWrapper(e);
        });

        if (getPreviousPhrase() != null && !getPreviousPhrase().contextTermination) {
            contextPhrases.addAll(getPreviousPhrase().contextPhrases);
        }

//        if (hasDOMInteraction) {
//            syncWithDOM();
//        }


//        if (phraseType.equals(PhraseType.CONDITIONAL)) {
//            runOperation();
//        } else if (phraseType.equals(PhraseType.ASSERTION)) {
//            runOperation();
////            executeAssertions(this);
//        } else if (phraseType.equals(PhraseType.ACTION)) {
//            runOperation();
//            executeAction(webDriver, this);


        if (isOperationPhrase) {
            runOperation();
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

        if (getFirstElement().selectionType.isEmpty()) {
            contextPhrases.add(this);
        } else {
//            syncWithDOM();
            if (getFirstElement().getElementWrappers().isEmpty()) {
                if (!getFirstElement().selectionType.equals("any")) {
                    throw new RuntimeException("Failed to find WebElements for " + getFirstElement());
                }
                System.out.println("No elements match for " + getFirstElement() + ", skipping subsequent phrases");
            }
            for (ElementWrapper elementWrapper : getWrappedElements()) {
                branchedPhrases.add(cloneWithElementContext(elementWrapper));
            }
            contextPhrases.add(this);
        }
    }


    public PhraseData cloneWithElementContext(ElementWrapper elementWrapper) {

        PhraseData clone = clonePhrase(getPreviousPhrase());
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
        clone.setPreviousPhrase(previous);
        if (getNextPhrase() != null) {
            clone.setNextPhrase(getNextPhrase().clonePhrase(clone));
            clone.getNextPhrase().setPreviousPhrase(clone);
        }
        return clone;
    }

    public PhraseData resolvePhrase() {
        resolvedPhrase = new Phrase(text, termination, parsedLine);
        resolvedPhrase.position = position;
        resolvedPhrase.setPreviousPhrase(getPreviousPhrase());

//        List<ElementMatch> nextElementMatches = new ArrayList<>();
//        PhraseData nextPhrase = getNextPhrase();
//        while(nextPhrase != null)
//        {
//            nextPhrase = nextPhrase.getNextPhrase();
//        }


        resolvedPhrase.setNextPhrase(getNextPhrase());
        return resolvedPhrase;
    }


    public PhraseData getNextResolvedPhrase() {
        PhraseData nextResolvedPhrase = getNextPhrase().resolvePhrase();
        nextResolvedPhrase.setPreviousPhrase(this);
        this.setNextPhrase(nextResolvedPhrase);
        nextResolvedPhrase.setOperationInheritance();
        if(nextResolvedPhrase.phraseType == PhraseType.CONTEXT)
            return nextResolvedPhrase;


        if (nextResolvedPhrase.isChainStart) {
            resolveResults();
            setConjunctionChain(nextResolvedPhrase);
        } else {
            nextResolvedPhrase.chainStartPhrase = chainStartPhrase;
            nextResolvedPhrase.chainStart = chainStart;
            nextResolvedPhrase.chainEnd = chainEnd;
            nextResolvedPhrase.conjunction = conjunction;
        }

        return nextResolvedPhrase;

    }


//        if (lastOperationPhrase == null) {
//            if (nextResolvedPhrase.isOperationPhrase) {
//                nextResolvedPhrase.addToPhraseGroup(nextResolvedPhrase);
//            }
//        } else {
//            nextResolvedPhrase.lastPhraseToInheritOperationFrom = lastOperationPhrase;
//            if (nextResolvedPhrase.phraseType == PhraseType.ASSERTION) {
//                if (nextResolvedPhrase.getAssertionType().isBlank()) {
//                    nextResolvedPhrase.setAssertionType(lastOperationPhrase.getAssertionType());
//                }
//            } else if (nextResolvedPhrase.phraseType == null) {
//                if (phraseType == PhraseType.ACTION) {
//                    nextResolvedPhrase.setAction(lastOperationPhrase.getAction());
//                } else if (phraseType == PhraseType.ASSERTION) {
//                    nextResolvedPhrase.setAssertionType(lastOperationPhrase.getAssertionType());
//                    nextResolvedPhrase.setAssertion(lastOperationPhrase.getAssertion());
//                }
//            }
//
//            if (nextResolvedPhrase.isOperationPhrase) {
//                if (lastOperationPhrase.phraseType == nextResolvedPhrase.phraseType) {
//                    lastOperationPhrase.addToPhraseGroup(nextResolvedPhrase);
//                }
//            }
//
//        }
//
//
//        return nextResolvedPhrase;


//    public void processPhraseResult() {
//        String conjunctionString = conjunction.isBlank() ? "and" : conjunction;
//        resultPhrases.
//        if (phraseType == PhraseType.ASSERTION) {
//            if (result.failed() && conjunctionString.equals("and")) {
//                throw result.getRuntimeError(getAssertionType().equals("verify"));
//            }
//        }
//    }


}
