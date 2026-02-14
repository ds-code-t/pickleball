package tools.dscode.common.treeparsing.parsedComponents;


import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.util.ArrayList;

import static tools.dscode.common.domoperations.ExecutionDictionary.STARTING_CONTEXT;
import static tools.dscode.common.treeparsing.DefinitionContext.FILE_INPUT;


public final class Phrase extends PhraseData {


    public Phrase(LineData parsedLine) {
        super("From " + STARTING_CONTEXT, ',', parsedLine);
        isTopContext = true;
    }

    public Phrase(String inputText, Character delimiter, LineData parsedLine) {
        super(inputText, delimiter, parsedLine);
        if (!isOperationPhrase) {
            elementMatches = new ArrayList<>(elementMatches.stream().filter(e -> !e.isPlaceHolder()).toList());
        }
    }


    boolean shouldRun() {
        if (getConditional().startsWith("else")) {
            if (getPreviousPhrase() == null) {
                if (parsedLine.previousSiblingConditionalState > -1) {
                    phraseConditionalMode = 0;
                    previouslyResolvedBoolean = false;
                }
            } else if (getPreviousPhrase().phraseConditionalMode > -1)
                phraseConditionalMode = 0;
            else
                phraseConditionalMode = 1;
        } else {
            phraseConditionalMode = getPreviousPhrase() == null ? 1 : getPreviousPhrase().phraseConditionalMode;
        }
        return phraseConditionalMode > 0;
    }


    @Override
    public PhraseData runPhrase() {
        executePhrase();
        PhraseData nextResolvedPhrase = getNextResolvedPhrase();
        if (!repeatRootPhrase && (nextResolvedPhrase == null || nextResolvedPhrase.isChainStart || !branchedPhrases.isEmpty() || contextTermination)) {
            resolveResults();
        }


        if (contextTermination) {
            if (termination.equals(':') || termination.equals('?')) {
                parsedLine.lineConditionalMode = phraseConditionalMode;
                parsedLine.inheritancePhrase = this;
            }
        }
//            if (phraseType.equals(PhraseType.CONDITIONAL)) {
//                parsedLine.lineConditionalMode = phraseConditionalMode;
//            } else if (termination.equals(':')) {
//                parsedLine.inheritedContextPhrases.add(contextPhrases);
//                parsedLine.lineConditionalMode = phraseConditionalMode;
//            }
////            else {
////                parsedLine.inheritedContextPhrases.removeLast();
////            }
//        }
//
//        if(contextTermination  && !termination.equals('.'))
//        {
//            parsedLine.passedPhrase = this;
//        }

        return nextResolvedPhrase;
    }


    public void executePhrase() {
        if ((phraseType == null || phraseType == PhraseType.ELEMENT_ONLY) && (templatePhrase != null && templatePhrase.phraseType != null)) {
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


        if (phraseType == null && !getConditional().isBlank()) {
            phraseType = PhraseType.CONDITIONAL;
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
//            contextPhrases.add(this);
            return;
        }


        getElementMatches().forEach(e -> {
            if (e.elementTypes.contains(ElementType.HTML_TYPE)) e.contextWrapper = new ContextWrapper(e);
        });

//        if (getPreviousPhrase() != null && !getPreviousPhrase().contextTermination) {
//            contextPhrases.addAll(getPreviousPhrase().contextPhrases);
//        }
        if (chainStartPhrase != null && chainStartPhrase.repeatRootPhrase) {
            repeatRootPhrase = true;
            return;
        }


        if (isOperationPhrase) {
            runOperation();
        } else if (phraseType.equals(PhraseType.CONTEXT)) {
            processContextPhrase();
        }
    }


    void processContextPhrase() {
        if (!getFirstElement().selectionType.isEmpty()) {

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
        }
    }


    public PhraseData cloneWithElementContext(ElementWrapper elementWrapper) {
//        char c = termination == ':' ? ',' : termination;
        PhraseData clone = clonePhrase(getPreviousPhrase());
//        PhraseData clone = clonePhrase(getPreviousPhrase(), c);
        clone.contextElement = elementWrapper;
        clone.categoryFlags.add(ExecutionDictionary.CategoryFlags.ELEMENT_CONTEXT);
        return clone;
    }


    @Override
    public PhraseData cloneInheritedPhrase() {
        PhraseData clonedPhrase = clonePhrase(getPreviousPhrase());
        clonedPhrase.branchedPhrases.addAll(branchedPhrases);
//        clonedPhrase.repeatRootPhrase = repeatRootPhrase;
        clonedPhrase.setResolvedPhrase(clonedPhrase);
        return clonedPhrase;
    }

    @Override
    public PhraseData clonePhrase(PhraseData previous) {
        return clonePhrase(previous, null);
    }

    @Override
    public PhraseData cloneRepeatedChain() {

        PhraseData chainStartClone = null;
        PhraseData lastClone = chainStartPhrase.getPreviousPhrase();
       for (int p = 0; p< chainStartPhrase.repeatedChain.size(); p++) {
           PhraseData currentPhrase = chainStartPhrase.repeatedChain.get(p);
           PhraseData currentClone = currentPhrase.clonePhrase(lastClone, null);
           if(p ==0)
           {
               chainStartClone = currentClone;
               currentClone.isChainStart = true;
               branchedPhrases.add(currentPhrase);
           }
           else {
               lastClone.setNextPhrase(currentClone);
           }
           currentClone.chainStartPhrase = chainStartClone;
           currentClone.shouldRepeatPhrase = true;
           currentClone.repeatRootPhrase = false;
           chainStartClone.repeatedChain.add(currentClone);
           lastClone = currentClone;
       }
        return chainStartClone;
    }

    @Override
    public PhraseData clonePhrase(PhraseData previous, Character newTermination) {
        Phrase clone = new Phrase(text, (newTermination == null ? termination : newTermination), parsedLine);
        clone.phraseConditionalMode = phraseConditionalMode;
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
        PhraseData resolvedPhrase = new Phrase(text, termination, parsedLine);
        if (resolvedPhrase.getAction().endsWith("attach") && !resolvedPhrase.getElementMatches().stream().anyMatch(e -> e.elementTypes.contains(ElementType.HTML_TYPE))) {
            resolvedPhrase = new Phrase(resolvedPhrase.resolvedText.replaceFirst("\\battach(?:es|ed)?\\b", "attach " + FILE_INPUT + " "), termination, parsedLine);
        }
        setResolvedPhrase(resolvedPhrase);
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
        if (getNextPhrase() == null) return null;
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
//            if (previousPhrase != null)
//                previousPhrase.resolveResults();
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
