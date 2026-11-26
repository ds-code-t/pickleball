package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.match.PatternMatcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsStepCatalogTest {
    @TempDir
    Path project;

    @Test
    void regexDefineStepIsAJsHostAndMatchesTheCount() throws Exception {
        Path script = project.resolve("src/test/resources/glue/js/count.js");
        Files.createDirectories(script.getParent());
        Files.writeString(script, """
                defineStep(/I count (\\d+)/, function (n) {
                  saveValue("jsStepCount", n);
                });
                """, StandardCharsets.UTF_8);
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        StoreStepCatalog.Definition row = StoreStepCatalog.list(project).stream()
                .filter(item -> "js".equals(item.host()))
                .findFirst()
                .orElseThrow();
        assertEquals("js", row.language());
        assertEquals("js", row.host());
        List<String> matched = PatternMatcher.match(row.pattern(), "I count 2", "[]");
        assertNotNull(matched, row.pattern());
        assertEquals("2", matched.getFirst());
    }
}
