package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.domoperations.ExecutionDictionary;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryEditTest {
    @TempDir
    Path project;

    @Test
    void projectCategoryLoadsAfterBuiltinsOnTheNextDictionary() throws Exception {
        Capture put = run("dalton", "category", "put", "--id=Badge",
                "--text=name: Badge\nbase: //article\n", "--json", "--project=" + project);
        assertEquals(0, put.code, put.err);
        ExecutionDictionary fresh = new ExecutionDictionary() {};
        assertTrue(fresh.getFinalCategoryXPathy("Badge") == null);
        ProjectElementCategories.load(fresh, project);
        assertTrue(fresh.getFinalCategoryXPathy("Badge").getXpath().contains("article"),
                String.valueOf(fresh.getFinalCategoryXPathy("Badge")));

        String previous = System.getProperty("pkb_dalton_project");
        try {
            System.setProperty("pkb_dalton_project", project.toString());
            ExecutionDictionary again = new ExecutionDictionary() {};
            ProjectElementCategories.ensureLoaded(again);
            assertNotNull(again.getFinalCategoryXPathy("Badge"));
            ProjectElementCategories.ensureLoaded(again);
            assertEquals(1, count(again.getFinalCategoryXPathy("Badge").getXpath(), "article"));
        } finally {
            if (previous == null) System.clearProperty("pkb_dalton_project");
            else System.setProperty("pkb_dalton_project", previous);
        }
    }

    @Test
    void builtinsAreImmutableReservedNamesAreBlockedAndLoadingStaysSpecial() throws Exception {
        Capture replace = run("dalton", "category", "put", "--id=Button",
                "--text=name: Button\nbase: //replaced\n", "--json", "--project=" + project);
        assertEquals(1, replace.code, replace.err);
        assertTrue(replace.err.contains("immutable"), replace.err);
        assertFalse(Files.exists(project.resolve("dalton-entities/ElementCategory/Button.yaml")));

        Capture overlay = run("dalton", "category", "put", "--id=Button",
                "--text=name: Button\noverlay: true\nbase: //button[@data-test='close']\n",
                "--json", "--project=" + project);
        assertEquals(0, overlay.code, overlay.err);

        ExecutionDictionary dict = new ExecutionDictionary() {};
        dict.category("Button").addBase("//builtin");
        Files.writeString(project.resolve("dalton-entities/ElementCategory/Button.yaml"),
                "name: Button\noverlay: false\nbase: //should-not-apply\n");
        ProjectElementCategories.load(dict, project);
        assertTrue(dict.getFinalCategoryXPathy("Button").getXpath().contains("builtin"),
                dict.getFinalCategoryXPathy("Button").getXpath());

        Files.writeString(project.resolve("dalton-entities/ElementCategory/Button.yaml"),
                "name: Button\noverlay: true\nbase: //button[@data-test='close']\n");
        ProjectElementCategories.load(dict, project);
        assertTrue(dict.getFinalCategoryXPathy("Button").getXpath().contains("data-test"),
                dict.getFinalCategoryXPathy("Button").getXpath());

        Capture remove = run("dalton", "category", "remove", "--id=Button", "--confirm", "--json", "--project=" + project);
        assertEquals(1, remove.code, remove.err);
        assertTrue(remove.err.contains("cannot be removed"), remove.err);
        assertTrue(Files.exists(project.resolve("dalton-entities/ElementCategory/Button.yaml")));

        Capture reserved = run("dalton", "category", "put", "--id=Step Count",
                "--text=name: Step Count\nbase: //x\n", "--json", "--project=" + project);
        assertEquals(1, reserved.code, reserved.err);
        assertTrue(reserved.err.contains("reserved"), reserved.err);

        Capture flag = run("dalton", "category", "put", "--id=ALL_CONTEXTS",
                "--text=name: ALL_CONTEXTS\nbase: //x\n", "--json", "--project=" + project);
        assertEquals(1, flag.code, flag.err);
        assertTrue(flag.err.contains("ALL_CONTEXTS"), flag.err);

        Capture drop = run("dalton", "category", "put", "--id=Loading", "--text="
                + "name: Loading\noverlay: true\nbase: //x\nflags:\n  - PAGE_CONTEXT\n",
                "--json", "--project=" + project);
        assertEquals(1, drop.code, drop.err);
        assertTrue(drop.err.contains("ALL_CONTEXTS"), drop.err);

        Capture loading = run("dalton", "category", "put", "--id=Loading",
                "--text=name: Loading\noverlay: true\nbase: //*[@data-project-loading='true']\n",
                "--json", "--project=" + project);
        assertEquals(0, loading.code, loading.err);
        ExecutionDictionary loaded = new ExecutionDictionary() {};
        ProjectElementCategories.load(loaded, project);
        assertTrue(loaded.getResolvedCategoryFlags("Loading").contains(ExecutionDictionary.CategoryFlags.ALL_CONTEXTS));
        assertTrue(loaded.getFinalCategoryXPathy("Loading").getXpath().contains("data-project-loading"));

        Capture loadingRemove = run("dalton", "category", "remove", "--id=Loading", "--confirm", "--json", "--project=" + project);
        assertEquals(1, loadingRemove.code, loadingRemove.err);
    }

    @Test
    void daltonOnStoresTheCategoryInTheDatabaseUntilTheNextLoad() throws Exception {
        Files.writeString(project.resolve("dalton.properties"), "enabled=true\n");
        Capture put = run("dalton", "category", "put", "--id=Badge",
                "--text=name: Badge\nbase: //from-db\n", "--json", "--project=" + project);
        assertEquals(0, put.code, put.err);
        assertFalse(Files.exists(project.resolve("dalton-entities/ElementCategory/Badge.yaml")));
        assertTrue(Files.exists(DaltonPaths.database(project)));
        ExecutionDictionary dict = new ExecutionDictionary() {};
        ProjectElementCategories.load(dict, project);
        assertTrue(dict.getFinalCategoryXPathy("Badge").getXpath().contains("from-db"));
    }

    private static int count(String text, String token) {
        int found = 0;
        int index = 0;
        while (index >= 0) {
            index = text.indexOf(token, index);
            if (index < 0) break;
            found++;
            index += token.length();
        }
        return found;
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DaltonCli.execute(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
