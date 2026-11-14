package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.seleniumextensions.ElementWrapper;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.Phrase;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.LineData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A parsed with-look step has to filter through the production find path.
 * Building an {@link ElementPhrase} by hand is not this test.
 */
class LookLiveFindTest {
    private static final String HTML = """
            <!DOCTYPE html>
            <html><body>
            <button id="go" style="color: red">Go</button>
            <button id="stop" style="color: blue">Stop</button>
            <button id="dim" style="color: #f00; opacity: 0.2">Dim</button>
            <button id="hot" style="color: rgb(255, 0, 0); opacity: 0.8">Hot</button>
            <span id="plain">Plain</span>
            </body></html>
            """;

    private HtmlUnitDriver driver;

    @AfterEach
    void tearDown() {
        if (driver != null) driver.quit();
        System.clearProperty("pkb_composite");
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
        CompositeLog.resetForTests();
    }

    @Test
    void liveStepFindAppliesLookAndKeepsAttributeGrammar() throws Exception {
        System.setProperty("pkb_composite", "on");
        driver = open();
        ElementMatch blue = step(driver, "click the Button with look color equaling blue.");
        assertTrue(blue.lookClause.contains("color equaling blue"), blue.lookClause);
        assertEquals("(role=button AND look=color=blue)", LookFind.pipeline(blue));
        assertEquals(List.of("stop"), ids(ElementWrapper.getWrappedElements(blue)));

        ElementMatch red = step(driver, "click every Button with look color equaling red.");
        assertEquals("every", red.selectionType, red.fullText);
        assertEquals(List.of("go", "dim", "hot"), ids(ElementWrapper.getWrappedElements(red)));

        ElementMatch tagged = step(driver, "click every Button with id attribute and with look color equaling red.");
        assertTrue(tagged.attributes.toString().contains("attrName='id'"), tagged.attributes.toString());
        assertTrue(tagged.lookClause.contains("color equaling red"), tagged.lookClause);
        assertEquals(List.of("go", "dim", "hot"), ids(ElementWrapper.getWrappedElements(tagged)));

        ElementMatch missing = step(driver, "click every Button with id noattribute and with look color equaling red.");
        assertTrue(missing.attributes.toString().contains("attrName='id'"), missing.attributes.toString());
        assertTrue(missing.attributes.toString().toLowerCase().contains("not") || missing.attributes.toString().toLowerCase().contains("no"),
                missing.attributes.toString());
        assertEquals(List.of(), ids(ElementWrapper.getWrappedElements(missing)));
    }

    @Test
    void compositeOffFailsInsteadOfIgnoringLook() throws Exception {
        driver = open();
        ElementMatch blue = step(driver, "click the Button with look color equaling blue.");
        System.setProperty("pkb_composite", "off");
        CompositeRuntime.resetForTests();
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ElementWrapper.getWrappedElements(blue)
        );
        assertTrue(failure.getMessage().contains("pkb_composite=off"), failure.getMessage());
        assertTrue(failure.getMessage().contains("look="), failure.getMessage());
    }

    private HtmlUnitDriver open() throws Exception {
        Path dir = Files.createTempDirectory("look-live");
        Path page = dir.resolve("look.html");
        Files.writeString(page, HTML);
        HtmlUnitDriver html = new HtmlUnitDriver(true);
        html.get(page.toUri().toString());
        return html;
    }

    private static ElementMatch step(HtmlUnitDriver driver, String text) {
        LineData line = new BareLine(text);
        Phrase top = new Phrase("the Page", ',', line, null, false);
        top.isTopContext = true;
        top.phraseType = PhraseData.PhraseType.CONTEXT;
        top.contextXPathy = null;
        top.categoryFlags.clear();
        Phrase phrase = new Phrase(text, '.', line, top, false);
        phrase.setNewContext(false);
        phrase.setDriver(driver);
        ElementMatch match = phrase.getElementMatches().getFirst();
        assertTrue(match.lookClause.contains("with look"), match.lookClause);
        return match;
    }

    private static List<String> ids(List<ElementWrapper> wrappers) {
        return wrappers.stream().map(wrapper -> wrapper.getAttribute("id")).toList();
    }

    private static final class BareLine extends LineData {
        private BareLine(String input) {
            super(input);
        }

        @Override
        public PhraseData runPhraseFromLine(PhraseData phrase) {
            return phrase;
        }

        @Override
        public void runPhrases() {
        }
    }
}
