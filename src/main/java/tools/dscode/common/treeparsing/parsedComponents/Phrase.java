package tools.dscode.common.treeparsing.parsedComponents;


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

import static tools.dscode.common.domoperations.LeanWaits.waitForPhraseEntities;
import static tools.dscode.common.domoperations.ParsedActions.executeAction;
import static tools.dscode.common.domoperations.ParsedAssertions.executeAssertions;
import static tools.dscode.common.domoperations.SeleniumUtils.waitMilliseconds;
import static tools.dscode.coredefinitions.GeneralSteps.getBrowser;

public final class Phrase extends PhraseData {


    public Phrase(String inputText, Character delimiter, LineData parsedLine) {
        super(inputText, delimiter, parsedLine);

        webDriver = getBrowser();
    }

    private final LifecycleManager lifecycle = new LifecycleManager();


    @Override
    public void runPhrase() {
        System.out.println("\n==Running phrase: " + this + " , isClone? " + isClone + "");
        System.out.println("@@contextElement? " +  contextElement);
        if(contextElement!=null)
        {
            contextPhrases.add(this);
            return;
        }
        System.out.println("@@previousPhrase: " + previousPhrase + " , isClone: " + isClone);
        if(previousPhrase != null) {
            System.out.println("@@previousPhrase.contextElement: " + previousPhrase.contextElement);
            System.out.println("@@previousPhrase.contextPhrases: " + previousPhrase.contextPhrases);
            System.out.println("@@previousPhrase.contextTermination: " + previousPhrase.contextTermination);
            System.out.println("@@previousPhrase.phraseType: " + previousPhrase.phraseType);
            System.out.println("@@previousPhrase.action: " + previousPhrase.action);
            System.out.println("@@previousPhrase.context: " + previousPhrase.context);
        }

        System.out.println("@@phraseType: " + phraseType);
        System.out.println("@@action: " + action);
        System.out.println("@@context: " + context);


        parsedLine.startPhraseIndex = position;

        elements.forEach(e -> e.contextWrapper = new ContextWrapper(e));

        if (previousPhrase != null && !previousPhrase.contextTermination) {
            System.out.println("previousPhrase.contextPhrases: " + previousPhrase.contextPhrases);
            contextPhrases.addAll(previousPhrase.contextPhrases);
            System.out.println("contextPhrases: " + contextPhrases);
            if(!contextPhrases.isEmpty())
            {
                PhraseData firstPhrase = contextPhrases.getFirst();
                System.out.println("@@firstPhrase: " + firstPhrase);
                System.out.println("@@firstPhrase.contextElement " + firstPhrase.contextElement);
            }
        }

        System.out.println("\n\n==============\n@@@### before DOM interaction ###@@");
        System.out.println("\n@@Current Running phrase: " + this + " , isClone: " + isClone +  " , contextElement: " + contextElement + "");
        if(nextPhrase != null) {
            System.out.println("@@nextPhrase: " + nextPhrase + " , isClone: " + nextPhrase.isClone);
        }
        else
        {
            System.out.println("@@no nextPhrase!!");
        }
        System.out.println("@@previousPhrase: " + previousPhrase );
        contextPhrases.forEach(p -> System.out.println("@@contextPhrases-> " + p+ " , isClone: " + p.isClone  + " , contextElement: " + p.contextElement));
        if(previousPhrase != null) {
            System.out.println("@@previousPhrase.isClone: " + previousPhrase.isClone);
            previousPhrase.contextPhrases.forEach(p -> System.out.println("@@previousPhrase-->.contextPhrases: " + p + " , isClone: " + p.isClone + " , contextElement: " + p.contextElement));
        }
        System.out.println("@@hasDOMInteraction: " + hasDOMInteraction);
        System.out.println("\n\n-------------------------\n");
        if (hasDOMInteraction) {
            syncWithDOM();
        }


        if (phraseType.equals(PhraseType.ASSERTION)) {
            executeAssertions(webDriver, this);
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
        System.out.println("@@processContextPhrase(): " + this);
        System.out.println("@@elementMatch: " + (elementMatch == null ? "null" : elementMatch.name));
        System.out.println("@@elementMatch.selectionType: " + elementMatch.selectionType);
        System.out.println("@@econtextElement: " + contextElement);
        if (elementMatch.selectionType.isEmpty()) {
            contextPhrases.add(this);
        } else {
            System.out.println("@@else-elementMatch: " + elementMatch);
            syncWithDOM();
            if (elementMatch.wrappedElements.isEmpty()) {
                if (!elementMatch.selectionType.equals("any")) {
                    throw new RuntimeException("Failed to find WebElements for " + elementMatch);
                }
//                skipNextPhrase = true;
                System.out.println("No elements match for " + elementMatch + ", skipping subsequent phrases");
            }
            System.out.println("@@wrappedElements.size(): " + wrappedElements.size() + "");
            for (ElementWrapper elementWrapper : wrappedElements) {
                System.out.println("@@Cloning for elementWrapper-cat: " + elementWrapper.elementMatch.category);
                cloneWithElementContext(elementWrapper);

//                clones.add(clone);
//                contextPhrases.add(clone);
            }
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
        System.out.println("@@cloneWithElementContext");
        PhraseData clone = clonePhrase(previousPhrase);
        clone.contextElement = elementWrapper;
        clone.categoryFlags.add(ExecutionDictionary.CategoryFlags.ELEMENT_CONTEXT);
        return clone;
    }


    @Override
    public PhraseData clonePhrase(PhraseData previous) {
        System.out.println("@@clonePhrase " + this + " , isClone too? " + isClone);
        System.out.println("@@nextPhrase? " + nextPhrase + " , @@nextPhrase.nextPhrase ? " + (nextPhrase != null ? nextPhrase.nextPhrase : "null"));
        Phrase clone = new Phrase(text, termination, parsedLine);
        clone.isClone = true;
        clone.position = position;
        clones.add(clone);
        clone.previousPhrase = previous;
        if(nextPhrase != null)
        {
            System.out.println("@@cloning-NextPhrase: " + nextPhrase);
            clone.nextPhrase = nextPhrase.clonePhrase(clone);
            clone.nextPhrase.previousPhrase = clone;
        }
        return clone;
    }

}
