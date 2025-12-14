package tools.dscode.common.treeparsing.parsedComponents;


import io.cucumber.core.runner.StepExtension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WrapsElement;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.seleniumextensions.ContextWrapper;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.common.treeparsing.preparsing.ParsedLine;

import java.util.ArrayList;
import java.util.List;

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
        conditionalRanMode = previousPhrase == null ? 0 : previousPhrase.conditionalRanMode;
        if (conditional.startsWith("else")) {
            return conditionalRanMode * -1 > 0;
        }
        return conditionalRanMode >= 0;
    }

    @Override
    public void runPhrase() {
        System.out.println("Run Phrase: " + this + (isClone ? " (clone)" : ""));


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

        if (phraseType.equals(PhraseType.ASSERTION) || phraseType.equals(PhraseType.CONDITIONAL)) {
            executeAssertions(this);
        } else if (phraseType.equals(PhraseType.ACTION)) {
            executeAction(webDriver, this);
        } else if (phraseType.equals(PhraseType.CONTEXT)) {
            processContextPhrase();
        }
        if (contextTermination) {
            if (termination.equals(':')) {
                parsedLine.inheritedContextPhrases.add(contextPhrases);
            } else {
                parsedLine.inheritedContextPhrases.remove(parsedLine.inheritedContextPhrases.size() - 1);
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


}
