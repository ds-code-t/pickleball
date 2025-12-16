package tools.dscode.common.treeparsing.parsedComponents;


import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.preparsing.LineData;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities;
import static tools.dscode.common.domoperations.ParsedActions.executeAction;
import static tools.dscode.common.domoperations.ParsedAssertions.executeAssertions;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;

public final class Phrase extends PhraseData {


    public Phrase(String inputText, Character delimiter, LineData parsedLine) {
        super(inputText, delimiter, parsedLine);
    }

    private final LifecycleManager lifecycle = new LifecycleManager();


    boolean shouldRun() {
        System.out.println("@@shouldRun: " + this);
        phraseConditionalMode = previousPhrase == null ? 0 : previousPhrase.phraseConditionalMode;
        System.out.println("@@phraseConditionalMode1: " + phraseConditionalMode);
        if (conditional.startsWith("else")) {
            phraseConditionalMode = phraseConditionalMode * -1;
        }
        System.out.println("@@phraseConditionalMode2: " + phraseConditionalMode);
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
        System.out.println("@@PhrasE::: " + this  + " phraseType::" + phraseType + "");
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

        elements.forEach(e -> e.contextWrapper = new ContextWrapper(e));

        if (previousPhrase != null && !previousPhrase.contextTermination) {
            contextPhrases.addAll(previousPhrase.contextPhrases);
        }

        if (hasDOMInteraction) {
            syncWithDOM();
        }

        if (phraseType.equals(PhraseType.CONDITIONAL)) {
            executeAssertions(this);
        } else if (phraseType.equals(PhraseType.ASSERTION)) {
            executeAssertions(this);
        } else if (phraseType.equals(PhraseType.ACTION)) {
            executeAction(webDriver, this);
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
        if (elementMatch.selectionType.isEmpty()) {
            contextPhrases.add(this);
        } else {
            syncWithDOM();
            if (elementMatch.wrappedElements.isEmpty()) {
                if (!elementMatch.selectionType.equals("any")) {
                    throw new RuntimeException("Failed to find WebElements for " + elementMatch);
                }
                System.out.println("No elements match for " + elementMatch + ", skipping subsequent phrases");
            }
            for (ElementWrapper elementWrapper : wrappedElements) {
                branchedPhrases.add(cloneWithElementContext(elementWrapper));
            }
            contextPhrases.add(this);
        }
    }


    public void syncWithDOM() {
        if (this.webDriver == null)
            this.webDriver = getBrowser();
        waitMilliseconds(1000);
        lifecycle.fire(Phase.BEFORE_DOM_LOAD_CHECK);
        waitForPhraseEntities(this);
        waitMilliseconds(100);
        lifecycle.fire(Phase.BEFORE_DOM_INTERACTION);
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
        System.out.println("@@resolvePhrase1: " + this.text +  " r: " + this.resolvedText);
        Phrase resolvedPhrase = new Phrase(text, termination, parsedLine);
        System.out.println("@@resolvePhrase2: " + resolvedPhrase.text +  " r: " + resolvedPhrase.resolvedText);
        resolvedPhrase.position = position;
        resolvedPhrase.previousPhrase = previousPhrase;
        resolvedPhrase.nextPhrase = nextPhrase;
        return resolvedPhrase;
    }

    public PhraseData getNextResolvedPhrase() {
        System.out.println("@@getNextResolvedPhrase: " + this  + " next: " + nextPhrase);
        PhraseData nextResolvedPhrase = nextPhrase.resolvePhrase();
        nextResolvedPhrase.previousPhrase = this;
        this.nextPhrase = nextResolvedPhrase;
        return nextResolvedPhrase;
    }


}
