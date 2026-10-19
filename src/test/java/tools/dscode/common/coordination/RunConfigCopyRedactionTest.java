package tools.dscode.common.coordination;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.mappings.FileAndDataParsing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RunConfigCopyRedactionTest {
    @TempDir
    Path tempDir;

    @Test
    void nestedPasswordMappingIsCopiedUnchangedAndStillParses() throws Exception {
        String yaml = "Password:\n  Default: example-password\n";
        Path copied = copyText("sauce.yaml", yaml);
        assertEquals(yaml, Files.readString(copied));
        JsonNode loaded = FileAndDataParsing.parseDataString(Files.readString(copied), "sauce.yaml");
        assertEquals("example-password", loaded.path("Password").path("Default").asText());
        assertFalse(loaded.toString().contains("***"));
    }

    @Test
    void inlineSecretValuesCopyIntact() throws Exception {
        String yaml = """
                password: secret123
                api_key: abc-123
                accessKey: sauce-key
                name: visible
                """;
        assertEquals(yaml, Files.readString(copyText("app.yaml", yaml)));
    }

    @Test
    void quotedSecretValuesCopyIntact() throws Exception {
        String yaml = """
                apiKey: "quoted-secret"
                password: "pass with spaces"
                """;
        Path copied = copyText("quoted.yaml", yaml);
        assertEquals(yaml, Files.readString(copied));
        JsonNode loaded = FileAndDataParsing.parseDataString(Files.readString(copied), "quoted.yaml");
        assertEquals("quoted-secret", loaded.path("apiKey").asText());
        assertEquals("pass with spaces", loaded.path("password").asText());
    }

    @Test
    void propertiesJsonAndXmlSecretKeysCopyIntact() throws Exception {
        String properties = "password=secret123\napi_key=abc-123\naccessKey=sauce-key\n";
        String json = "{\"password\":\"secret123\",\"api_key\":\"abc-123\",\"accessKey\":\"sauce-key\"}\n";
        String xml = "<config><password>secret123</password><apiKey>quoted-secret</apiKey></config>\n";
        assertEquals(properties, Files.readString(copyText("app.properties", properties)));
        assertEquals(json, Files.readString(copyText("app.json", json)));
        assertEquals(xml, Files.readString(copyText("app.xml", xml)));

        JsonNode props = FileAndDataParsing.parseDataString(properties, "app.properties");
        assertEquals("secret123", props.path("password").asText());
        assertEquals("abc-123", props.path("api_key").asText());
        assertEquals("sauce-key", props.path("accessKey").asText());
        JsonNode jsonNode = FileAndDataParsing.parseDataString(json, "app.json");
        assertEquals("secret123", jsonNode.path("password").asText());
        assertEquals("abc-123", jsonNode.path("api_key").asText());
        JsonNode xmlNode = FileAndDataParsing.parseDataString(xml, "app.xml");
        assertEquals("secret123", xmlNode.path("password").asText());
        assertEquals("quoted-secret", xmlNode.path("apiKey").asText());
    }

    @Test
    void workerLoadOfTheRunCopyKeepsTheRealSecret() throws Exception {
        String yaml = "Password:\n  Default: example-password\npassword: secret123\n";
        Path copied = copyText("remote.yaml", yaml);
        JsonNode loaded = FileAndDataParsing.parseDataString(Files.readString(copied), copied.getFileName().toString());
        assertEquals("example-password", loaded.path("Password").path("Default").asText());
        assertEquals("secret123", loaded.path("password").asText());
        assertFalse(Files.readString(copied).contains("***"));
    }

    private Path copyText(String name, String content) throws Exception {
        Path project = tempDir.resolve("project-" + name);
        Path configs = project.resolve("configs");
        Files.createDirectories(configs);
        Path source = configs.resolve(name);
        Files.writeString(source, content, StandardCharsets.UTF_8);
        Path destination = tempDir.resolve("run-" + name).resolve("config");
        RunConfigCopy.copyIfAbsent(
                project,
                destination,
                "wd-file:" + configs.toAbsolutePath().normalize().toString().replace('\\', '/'),
                project
        );
        assertEquals(content, Files.readString(source));
        return destination.resolve(name);
    }
}
