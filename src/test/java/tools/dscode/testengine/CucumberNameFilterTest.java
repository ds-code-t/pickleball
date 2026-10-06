package tools.dscode.testengine;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CucumberNameFilterTest {

    @Test
    void partialScenarioNameMatchesTheWholeTitleOnTheJunitEngine() {
        Pattern partial = Pattern.compile(PickleballRunner.cucumberNameFilterMatchingFind("Partial name"));
        assertTrue(partial.matcher("Partial name example rows").matches());
        assertTrue(partial.matcher("Partial name plain scenario").matches());
        assertFalse(partial.matcher("Example #1.2").matches());
    }

    @Test
    void anchoredNameStillMatchesOnlyTheWholeTitle() {
        Pattern exact = Pattern.compile(PickleballRunner.cucumberNameFilterMatchingFind("^Exact title$"));
        assertTrue(exact.matcher("Exact title").matches());
        assertFalse(exact.matcher("Exact title extra").matches());
        assertFalse(exact.matcher("Not Exact title").matches());
    }
}
