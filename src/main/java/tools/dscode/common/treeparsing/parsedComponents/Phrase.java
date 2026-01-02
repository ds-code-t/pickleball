package tools.dscode.common.treeparsing.parsedComponents;


import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.util.ArrayList;


public final class Phrase extends PhraseData {


    public Phrase(String inputText, Character delimiter, LineData parsedLine) {
        super(inputText, delimiter, parsedLine);
        if(!isOperationPhrase) {
            elementMatches = new ArrayList<>(elementMatches.stream().filter(e -> !e.isPlaceHolder()).toList());
        }
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




        if( phraseType == null && templatePhrase.phraseType !=null) {
                if (!templatePhrase.getAction().isBlank()) {
                    setAction(templatePhrase.getAction());
                } else {
                    if (!templatePhrase.getConditional().isBlank() && getConditional().isBlank()) {
                        setConditional(templatePhrase.getConditional());
                    }

                    if (!templatePhrase.getAssertionType().isBlank() && getAssertionType().isBlank()) {
                        setAssertionType(templatePhrase.getAssertionType());
                    }

                    if (!templatePhrase.getAssertion().isBlank() && getAssertion().isBlank()) {
                        setAssertion(templatePhrase.getAssertion());
                    }
                }
        }

        if (!getAssertionType().isBlank() && getAssertion().isBlank()) {
            setAssertion("true");
        }


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


        getElementMatches().forEach(e -> {
            if (e.elementTypes.contains(ElementType.HTML_TYPE)) e.contextWrapper = new ContextWrapper(e);
        });

        if (getPreviousPhrase() != null && !getPreviousPhrase().contextTermination) {
            contextPhrases.addAll(getPreviousPhrase().contextPhrases);
        }




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
        setResolvedPhrase(new Phrase(text, termination, parsedLine));
        getResolvedPhrase().position = position;
        getResolvedPhrase().setPreviousPhrase(getPreviousPhrase());

//        List<ElementMatch> nextElementMatches = new ArrayList<>();
//        PhraseData nextPhrase = getNextPhrase();
//        while(nextPhrase != null)
//        {
//            nextPhrase = nextPhrase.getNextPhrase();
//        }


        getResolvedPhrase().setNextPhrase(getNextPhrase());
        return getResolvedPhrase();
    }


    public PhraseData getNextResolvedPhrase() {
        PhraseData nextResolvedPhrase = getNextPhrase().resolvePhrase();
        nextResolvedPhrase.setPreviousPhrase(this);
        this.setNextPhrase(nextResolvedPhrase);

        return updateChainAndInheritances(nextResolvedPhrase);
    }

    public static PhraseData updateChainAndInheritances(PhraseData nextResolvedPhrase) {
        nextResolvedPhrase.setOperationInheritance();
        if (nextResolvedPhrase.phraseType == PhraseType.CONTEXT)
            return nextResolvedPhrase;

        PhraseData previousPhrase = nextResolvedPhrase.getPreviousPhrase();

        if (nextResolvedPhrase.isChainStart) {
            if (previousPhrase != null)
                previousPhrase.resolveResults();
            setConjunctionChain(nextResolvedPhrase);
        } else {
            if (previousPhrase != null) {
                nextResolvedPhrase.chainStartPhrase = previousPhrase.chainStartPhrase;
                nextResolvedPhrase.chainStart = previousPhrase.chainStart;
                nextResolvedPhrase.chainEnd = previousPhrase.chainEnd;
                nextResolvedPhrase.conjunction = previousPhrase.conjunction;
            }
        }

        return nextResolvedPhrase;
    }




}
