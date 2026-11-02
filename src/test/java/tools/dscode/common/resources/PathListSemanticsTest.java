package tools.dscode.common.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.mappings.FileAndDataParsing;
import tools.dscode.common.mappings.ParsingMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathListSemanticsTest {
    @TempDir
    Path temp;

    @Test
    void commaSplitsAndDropsBlanksWithoutChangingASingleValue() {
        assertEquals(List.of(), PathLists.split(null));
        assertEquals(List.of(), PathLists.split("  "));
        assertEquals(List.of("data"), PathLists.split("data"));
        assertEquals(List.of("data"), PathLists.split(" data "));
        assertEquals(List.of("data"), PathLists.split("data,"));
        assertEquals(List.of("data", "more"), PathLists.split("data, more"));
        assertEquals(List.of("a", "b c"), PathLists.split("a, , b c,"));
    }

    @Test
    void dataPathUsesTheFirstRootThatHasTheDocument() throws Exception {
        Path missing = temp.resolve("missing");
        Path present = temp.resolve("present");
        Files.createDirectories(missing);
        Files.createDirectories(present);
        Files.writeString(present.resolve("users.yaml"), "name: ada\n");
        String roots = "wd-file:" + missing.toAbsolutePath() + ", , wd-file:" + present.toAbsolutePath() + ",";
        JsonNode found = FileAndDataParsing.buildJsonFromDataRoots(roots, "users");
        assertEquals("ada", found.path("name").asText());
        JsonNode single = FileAndDataParsing.buildJsonFromDataRoots(
                "wd-file:" + present.toAbsolutePath(),
                "users"
        );
        assertEquals("ada", single.path("name").asText());
        assertNull(FileAndDataParsing.buildJsonFromDataRoots(
                "wd-file:" + missing.toAbsolutePath() + ",wd-file:" + missing.toAbsolutePath(),
                "users"
        ));
    }

    @Test
    void configPathsDeepMergeAndALaterNonObjectReplacesTheTree() throws Exception {
        Path first = temp.resolve("configs-a");
        Path second = temp.resolve("configs-b");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("app.yaml"), """
                name: one
                nested:
                  a: 1
                  b: 1
                """);
        Files.writeString(second.resolve("app.yaml"), """
                nested:
                  b: 2
                  c: 3
                extra: kept
                """);
        JsonNode merged = ParsingMap.loadMergedConfigs(List.of(
                "wd-file:" + first.toAbsolutePath(),
                "wd-file:" + second.toAbsolutePath()
        ));
        assertEquals("one", merged.path("app").path("name").asText());
        assertEquals(1, merged.path("app").path("nested").path("a").asInt());
        assertEquals(2, merged.path("app").path("nested").path("b").asInt());
        assertEquals(3, merged.path("app").path("nested").path("c").asInt());
        assertEquals("kept", merged.path("app").path("extra").asText());

        JsonNode replaced = ParsingMap.mergeConfigTrees(merged, TextNode.valueOf("scalar"));
        assertTrue(replaced.isTextual());
        assertEquals("scalar", replaced.asText());
    }
}
