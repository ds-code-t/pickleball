package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.htmlunit.HtmlUnitDriver;
import tools.dscode.common.GlobalConstants;
import tools.dscode.common.mappings.QuoteParser;
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
 * A parsed {@code between} step has to filter through the production find path.
 * Building an {@link ElementPhrase} by hand is not this test.
 */
class BetweenLiveFindTest {
    private static final String HTML = """
            <!DOCTYPE html>
            <html><body>
            <button id="a" data-x="0" data-y="0" data-w="20" data-h="20">A</button>
            <button id="b" data-x="200" data-y="100" data-w="20" data-h="20">B</button>
            <button id="out" data-x="0" data-y="190" data-w="10" data-h="10">Save</button>
            <button id="in" data-x="80" data-y="30" data-w="30" data-h="30">Save</button>
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
    void liveStepFindKeepsTheSaveButtonBetweenTheTwoAnchors() throws Exception {
        System.setProperty("pkb_composite", "on");
        driver = open();
        ElementMatch save = step(driver, "between the \"A\" Button and the \"B\" Button", "click the \"Save\" Button.");
        assertEquals(
                "(role=button AND text=\"Save\") AND between(role=button AND text=\"A\", role=button AND text=\"B\")",
                BetweenFind.pipeline(save, save.parentPhrase.getPreviousPhrase())
        );
        List<ElementWrapper> found = ElementWrapper.getWrappedElements(save);
        assertEquals(List.of("in"), ids(found));
    }

    @Test
    void aMissingSecondAnchorIsAnError() throws Exception {
        System.setProperty("pkb_composite", "on");
        driver = open();
        ElementMatch save = step(driver, "between the \"A\" Button", "click the \"Save\" Button.");
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ElementWrapper.getWrappedElements(save)
        );
        assertTrue(failure.getMessage().contains("between requires two anchors"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix:"), failure.getMessage());
    }

    @Test
    void compositeOffFailsInsteadOfIgnoringBetween() throws Exception {
        driver = open();
        ElementMatch save = step(driver, "between the \"A\" Button and the \"B\" Button", "click the \"Save\" Button.");
        System.setProperty("pkb_composite", "off");
        CompositeRuntime.resetForTests();
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ElementWrapper.getWrappedElements(save)
        );
        assertTrue(failure.getMessage().contains("pkb_composite=off"), failure.getMessage());
        assertTrue(failure.getMessage().contains("between("), failure.getMessage());
    }

    private HtmlUnitDriver open() throws Exception {
        Path dir = Files.createTempDirectory("between-live");
        Path page = dir.resolve("between.html");
        Files.writeString(page, HTML);
        HtmlUnitDriver html = new HtmlUnitDriver(true);
        html.get(page.toUri().toString());
        return html;
    }

    private static ElementMatch step(HtmlUnitDriver driver, String context, String text) {
        LineData line = new BareLine(text);
        Phrase top = new Phrase("the Page", ',', line, null, false);
        top.isTopContext = true;
        top.phraseType = PhraseData.PhraseType.CONTEXT;
        top.contextXPathy = null;
        top.categoryFlags.clear();
        Phrase between = new Phrase(masked(context), ',', line, top, false);
        Phrase phrase = new Phrase(masked(text), '.', line, between, false);
        phrase.setNewContext(false);
        phrase.setDriver(driver);
        return phrase.getElementMatches().getFirst();
    }

    /** The same quote masking {@code LineData} applies before a phrase is parsed. */
    private static String masked(String text) {
        QuoteParser quotes = new QuoteParser(text);
        return quotes.restoreFromWithOuterBookend(quotes.masked(), GlobalConstants.BOOK_END);
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
