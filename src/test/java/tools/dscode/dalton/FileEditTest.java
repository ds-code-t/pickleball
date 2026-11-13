package tools.dscode.dalton;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileEditTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    private String previousDalton;

    @BeforeEach
    void filePathIsTheOptOutTheseAssertionsCover() {
        previousDalton = System.getProperty("pkb_dalton");
        System.setProperty("pkb_dalton", "false");
    }

    @AfterEach
    void restoreDaltonFlag() {
        if (previousDalton == null) System.clearProperty("pkb_dalton");
        else System.setProperty("pkb_dalton", previousDalton);
    }

    @Test
    void configDataAndPropertiesRejectCleartextAndKeepReferences() throws Exception {
        Capture yaml = run("dalton", "file", "put", "--path=configs/browser.yaml",
                "--text=browser: chrome\npassword: hunter2\n", "--json", "--project=" + project);
        assertEquals(1, yaml.code, yaml.err);
        assertTrue(yaml.err.contains("password"), yaml.err);
        assertFalse(Files.exists(project.resolve("configs/browser.yaml")));

        Capture safe = run("dalton", "file", "put", "--path=configs/browser.yaml",
                "--text=browser: chrome\npassword: <login>\napi_key: ~MASK\n", "--json", "--project=" + project);
        assertEquals(0, safe.code, safe.err);
        assertEquals("browser: chrome\npassword: <login>\napi_key: ~MASK\n",
                Files.readString(project.resolve("configs/browser.yaml")));
        assertFalse(Files.exists(DaltonPaths.database(project)));

        Capture props = run("dalton", "file", "put", "--path=dalton.properties",
                "--text=enabled=false\nsecret-fields=vault\nvault=clear\n", "--json", "--project=" + project);
        assertEquals(1, props.code, props.err);
        assertTrue(props.err.contains("vault"), props.err);

        Capture csv = run("dalton", "file", "put", "--path=data/users.csv",
                "--text=name,password\nada,hunter2\n", "--json", "--project=" + project);
        assertEquals(1, csv.code, csv.err);
        Capture csvOk = run("dalton", "file", "put", "--path=data/users.csv",
                "--text=name,password\nada,<login>\n", "--json", "--project=" + project);
        assertEquals(0, csvOk.code, csvOk.err);

        Files.writeString(project.resolve("configs/browser.yaml"), "browser: chrome\npassword: hunter2\n");
        Capture shown = run("dalton", "file", "show", "--path=configs/browser.yaml", "--json", "--project=" + project);
        assertEquals(0, shown.code, shown.err);
        assertTrue(JSON.readTree(shown.out).get("text").asText().contains("~MASK"), shown.out);
        assertFalse(JSON.readTree(shown.out).get("text").asText().contains("hunter2"), shown.out);
    }

    @Test
    void daltonOnStoresTheFileUntilExtractAndRefusesAConflict() throws Exception {
        System.clearProperty("pkb_dalton");
        Files.writeString(project.resolve("dalton.properties"), "enabled=true\n");
        Files.createDirectories(project.resolve("configs"));
        Files.writeString(project.resolve("configs/app.yaml"), "browser: chrome\n");
        assertEquals(0, run("dalton", "hydrate", "--project=" + project).code);
        Capture put = run("dalton", "file", "put", "--path=configs/app.yaml",
                "--text=browser: edge\n", "--json", "--project=" + project);
        assertEquals(0, put.code, put.err);
        assertTrue(JSON.readTree(put.out).get("dbEdited").asBoolean());
        assertEquals("browser: chrome\n", Files.readString(project.resolve("configs/app.yaml")));
        Files.writeString(project.resolve("configs/app.yaml"), "browser: disk\n");
        Capture conflict = run("dalton", "file", "delete", "--path=configs/app.yaml", "--confirm", "--json", "--project=" + project);
        assertEquals(1, conflict.code, conflict.err);
        assertTrue(Files.exists(project.resolve("configs/app.yaml")));
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
