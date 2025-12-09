package tools.dscode.common.treeparsing.parsedComponents;


import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import tools.dscode.common.annotations.LifecycleManager;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.seleniumextensions.ContextWrapper;
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
    }

    private final LifecycleManager lifecycle = new LifecycleManager();


    @Override
    public void runPhrase() {




        elements.forEach(e -> e.contextWrapper = new ContextWrapper(e));

        if (previousPhrase != null && !previousPhrase.contextTermination) {
            contextPhrases.addAll(previousPhrase.contextPhrases);
        }

        WebDriver driver = null;
        if (hasDOMInteraction) {
            waitMilliseconds(1000);
            lifecycle.fire(Phase.BEFORE_DOM_LOAD_CHECK);
            driver = getBrowser( "BROWSER");
            waitForPhraseEntities(driver, this);
            waitMilliseconds(100);
            lifecycle.fire(Phase.BEFORE_DOM_INTERACTION);
        }



        if (phraseType.equals(PhraseType.ASSERTION)) {
            executeAssertions(driver, this);
        } else if (phraseType.equals(PhraseType.ACTION)) {
            executeAction(driver, this);
        } else if (phraseType.equals(PhraseType.CONTEXT)) {
             contextPhrases.add(this);
//            if (previousPhrase == null || previousPhrase.contextTermination) {
//                contextPhrases.add(this);
//            } else {
//                contextPhrases.addAll(previousPhrase.contextPhrases);
//            }
        }

        if (contextTermination) {
            if (termination.equals(':')) {
                parsedLine.inheritedContextPhrases.add(contextPhrases);
            } else {
                parsedLine.inheritedContextPhrases.remove(parsedLine.inheritedContextPhrases.size() - 1);
            }
        }

    }

}
