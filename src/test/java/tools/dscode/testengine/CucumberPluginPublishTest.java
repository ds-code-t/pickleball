package tools.dscode.testengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CucumberPluginPublishTest {

    @Test
    void normalRunWithoutPluginsLeavesCucumberPluginUnset() {
        assertNull(PickleballRunner.cucumberPluginForRun(null, null));
        assertNull(PickleballRunner.cucumberPluginForRun(null, ""));
        assertNull(PickleballRunner.cucumberPluginForRun(null, "   "));
        assertNull(PickleballRunner.cucumberPluginForRun("", null));
    }

    @Test
    void normalRunDoesNotPublishASetPluginList() {
        assertNull(PickleballRunner.cucumberPluginForRun(null, "pretty"));
        assertNull(PickleballRunner.cucumberPluginForRun("mvn-test", "pretty"));
    }

    @Test
    void workbenchRunPublishesANonBlankPluginList() {
        assertEquals("summary", PickleballRunner.cucumberPluginForRun("workbench-discover", "summary"));
        assertEquals("summary", PickleballRunner.cucumberPluginForRun("workbench-confirm", "summary"));
        assertNull(PickleballRunner.cucumberPluginForRun("workbench-discover", null));
        assertNull(PickleballRunner.cucumberPluginForRun("workbench-discover", " "));
    }
}
