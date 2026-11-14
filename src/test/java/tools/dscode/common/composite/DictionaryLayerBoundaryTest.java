package tools.dscode.common.composite;

import org.junit.jupiter.api.Test;
import tools.dscode.common.domoperations.ExecutionDictionary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DictionaryLayerBoundaryTest {
    @Test
    void dictionaryFilesDoNotImportProtocolDrivers() throws Exception {
        List<String> names = List.of(
                "SelectorTranslator.java", "CompositeSelector.java", "EngineQuery.java",
                "ElementPhrase.java", "VerbPlan.java", "CombinedBy.java", "XPathDefinitions.java"
        );
        Path composite = Path.of("src/main/java/tools/dscode/common/composite");
        for (String name : names) {
            String source = Files.readString(composite.resolve(name));
            assertClean(name, source);
        }
        String dictionary = Files.readString(Path.of("src/main/java/tools/dscode/common/domoperations/ExecutionDictionary.java"));
        assertClean("ExecutionDictionary.java", dictionary);
        assertTrue(ElementPhrase.class.getName().contains("ElementPhrase"));
        assertTrue(VerbPlan.class.getName().contains("VerbPlan"));
        assertTrue(ExecutionDictionary.class.getName().contains("ExecutionDictionary"));
    }

    private static void assertClean(String name, String source) {
        for (String banned : List.of(
                "org.openqa.selenium.devtools", "org.openqa.selenium.remote", "org.openqa.selenium.chrome",
                "org.openqa.selenium.edge", "org.openqa.selenium.firefox", "org.openqa.selenium.bidi",
                "tools.dscode.browser", "dev.domengine.cdp", "executeCdpCommand", "executeScript", "JavascriptExecutor")) {
            assertFalse(source.contains(banned), name + " contains " + banned);
        }
    }
}
