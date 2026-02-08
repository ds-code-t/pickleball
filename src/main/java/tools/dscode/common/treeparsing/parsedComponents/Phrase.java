package tools.dscode.common.treeparsing.parsedComponents;


import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.util.ArrayList;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.treeparsing.DefinitionContext.FILE_INPUT;


public final class Phrase extends PhraseData {


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

        if (nextResolvedPhrase == null || nextResolvedPhrase.isChainStart || !branchedPhrases.isEmpty() || contextTermination) {
            resolveResults();
        }

        System.out.println("@@contextTermination:  " + contextTermination);

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
        System.out.println("@@executePhrase1: "  + this);
        System.out.println("@@phraseType1: "  + phraseType);
        System.out.println("@@templatePhrase.phraseType1: "  + templatePhrase.phraseType);
        System.out.println("@@templatePhrase.getConditional()1: "  + templatePhrase.getConditional());
        System.out.println("@@templatePhrase.getAction()1: "  + templatePhrase.getAction());
        if ((phraseType == null || phraseType == PhraseType.ELEMENT_ONLY) && templatePhrase.phraseType != null) {
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
        System.out.println("@@executePhrase2: "  + this);
        System.out.println("@@phraseType2: "  + phraseType);
        System.out.println("@@templatePhrase.phraseType2: "  + templatePhrase.phraseType);
        System.out.println("@@templatePhrase.getConditional()2: "  + templatePhrase.getConditional());
        System.out.println("@@templatePhrase.getAction()2: "  + templatePhrase.getAction());


        System.out.println("@@getAssertionType()3: "  + getAssertionType());
        System.out.println("@@getAssertion()3: "  + getAssertion());


        if (phraseType == null && !getConditional().isBlank()) {
            phraseType = PhraseType.CONDITIONAL;
        }

        if (!getAssertionType().isBlank() && getAssertion().isBlank()) {
            setAssertion("true");
        }
        System.out.println("@@getAssertionType()4: "  + getAssertionType());
        System.out.println("@@getAssertion()4: "  + getAssertion());

        System.out.println("@@isOperationPhrase4: "  + isOperationPhrase);

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

        if (shouldRepeatPhrase) {
            return;
//            PhraseData repeatedPhraseClone = clonePhrase(getPreviousPhrase());
//            repeatedPhraseClone.shouldRepeatPhrase = false;
//            branchedPhrases.add(repeatedPhraseClone);
        }
        System.out.println("@@@@isOperationPhrase ? "  + isOperationPhrase);
        if (isOperationPhrase) {
            runOperation();
        } else if (phraseType.equals(PhraseType.CONTEXT)) {
            processContextPhrase();
        }
    }


    void processContextPhrase() {
        System.out.println("@@processContextPhrase(): " + this);
        System.out.println("@@getFirstElement().selectionType.isEmpty(): " + getFirstElement().selectionType.isEmpty());
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

        System.out.println("@@contextPhrases:: : " + contextPhrases);
    }



    public PhraseData cloneWithElementContext(ElementWrapper elementWrapper) {
        PhraseData clone = clonePhrase(getPreviousPhrase());
        clone.contextElement = elementWrapper;
        clone.categoryFlags.add(ExecutionDictionary.CategoryFlags.ELEMENT_CONTEXT);
        return clone;
    }




    @Override
    public PhraseData cloneInheritedPhrase() {
        PhraseData clonedPhrase =  clonePhrase(getPreviousPhrase());
        clonedPhrase.branchedPhrases.addAll(branchedPhrases);
        clonedPhrase.contextPhrases.addAll(contextPhrases);
        clonedPhrase.shouldRepeatPhrase = shouldRepeatPhrase;
        clonedPhrase.setResolvedPhrase(clonedPhrase);
        return clonedPhrase;
    }

    @Override
    public PhraseData clonePhrase(PhraseData previous) {
        Phrase clone = new Phrase(text, termination, parsedLine);
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
