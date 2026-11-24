package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D9/D11: opt-in installs print licenses first, need --confirm, verify checksums, and never download in tests. */
class OptInSetupTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @Test
    void covers_command_setup() throws Exception {
        Capture none = agent("setup");
        assertEquals(2, none.code);
        assertTrue(none.err.contains("error: setup needs desktop-driver or ocr"), none.err);
        Capture unnamed = agent("setup", "desktop-driver");
        assertEquals(2, unnamed.code, unnamed.out + unnamed.err);
        assertTrue(unnamed.err.contains("error: setup desktop-driver needs --driver="), unnamed.err);

        Path dir = temp.resolve("cache");
        Capture shown = agent("setup", "desktop-driver", "--driver=flaui", "--dir=" + dir);
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.startsWith("license: MIT (https://github.com/FlaUI/FlaUI.WebDriver)"), shown.out);
        JsonNode body = JSON.readTree(shown.out.substring(shown.out.indexOf('{')));
        assertFalse(body.path("installed").asBoolean(), "nothing installs without --confirm");
        assertEquals("v0.4.0", body.path("version").asText());
        assertEquals(64, body.path("sha256").asText().length());
        assertFalse(Files.exists(dir), "no download and no directory without --confirm");

        JsonNode appium = JSON.readTree(after(agent("setup", "desktop-driver", "--driver=mac2", "--dir=" + dir).out));
        assertTrue(appium.path("command").asText().startsWith("appium driver install mac2@"), appium.toString());
        assertTrue(appium.path("needs").asText().contains("macos-accessibility"), appium.toString());
    }

    @Test
    void downloadsAreChecksumVerifiedBeforeTheyLand() throws Exception {
        Path artifact = temp.resolve("driver.bin");
        Files.writeString(artifact, "fixture driver bytes");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
        Path dir = temp.resolve("installed");
        Path landed = SetupCommand.fetchVerified(artifact.toUri(), sha, dir);
        assertEquals("fixture driver bytes", Files.readString(landed));
        Path wrong = temp.resolve("wrong");
        IOException mismatch = assertThrows(IOException.class, () -> SetupCommand.fetchVerified(artifact.toUri(), "00".repeat(32), wrong));
        assertTrue(mismatch.getMessage().contains("checksum mismatch"), mismatch.getMessage());
        try (var left = Files.list(wrong)) {
            assertEquals(0, left.count(), "a mismatched download leaves nothing behind");
        }
    }

    @Test
    void doctorDesktopReportsDriversWithFixLines() {
        Capture doctor = agent("doctor", "--desktop");
        assertEquals(0, doctor.code, doctor.err);
        assertTrue(doctor.out.contains("desktopDriver=native"), doctor.out);
        assertTrue(doctor.out.contains("desktop={"), doctor.out);
        assertFalse(agent("doctor").out.contains("desktop="), "plain doctor does not probe desktop drivers");
    }

    @Test
    void setupOcrPrintsLicensesAndInstallsOnlyVerifiedFilesWithConfirm() throws Exception {
        Path dir = temp.resolve("ocr");
        Capture shown = agent("setup", "ocr", "--runtime=onnx", "--dir=" + dir);
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.startsWith("license: MIT (onnxruntime-1.32.0.jar, https://repo1.maven.org/"), shown.out);
        assertTrue(shown.out.contains("license: Apache-2.0 (en_PP-OCRv3_rec_infer.onnx"), shown.out);
        JsonNode body = JSON.readTree(after(shown.out));
        assertFalse(body.path("installed").asBoolean(), body.toString());
        assertEquals(3, body.path("files").size());
        for (JsonNode file : body.path("files")) assertEquals(64, file.path("sha256").asText().length(), file.toString());
        assertTrue(body.path("use").asText().contains("pkb_ocr_dir=" + dir), body.toString());
        assertFalse(Files.exists(dir), "nothing downloads without --confirm");

        Capture other = agent("setup", "ocr", "--runtime=tesseract");
        assertEquals(2, other.code);
        assertTrue(other.err.contains("error: setup ocr supports --runtime=onnx only"), other.err);

        Path mirror = Files.createDirectories(temp.resolve("mirror"));
        for (dev.domengine.query.OnnxOcrRuntime.Artifact artifact : dev.domengine.query.OnnxOcrRuntime.ARTIFACTS)
            Files.writeString(mirror.resolve(artifact.name()), "tampered " + artifact.name());
        System.setProperty(OcrSetup.MIRROR_PROPERTY, mirror.toString());
        try {
            Capture tampered = agent("setup", "ocr", "--runtime=onnx", "--dir=" + dir, "--confirm");
            assertEquals(1, tampered.code, tampered.out);
            assertTrue(tampered.err.contains("checksum mismatch"), tampered.err);
            assertEquals(3, dev.domengine.query.OnnxOcrRuntime.missing(dir).size(), "no unverified file lands");
        } finally {
            System.clearProperty(OcrSetup.MIRROR_PROPERTY);
        }
    }

    @Test
    void doctorReportsOcrRuntimeStatusWithFix() {
        Path dir = temp.resolve("no-ocr");
        System.setProperty("pkb_ocr_dir", dir.toString());
        try {
            Capture doctor = agent("doctor");
            assertEquals(0, doctor.code, doctor.err);
            assertTrue(doctor.out.contains("ocrRuntime=not installed (missing onnxruntime-1.32.0.jar,en_PP-OCRv3_rec_infer.onnx,en_dict.txt in " + dir
                    + "; fix: setup ocr --runtime=onnx --confirm)"), doctor.out);
        } finally {
            System.clearProperty("pkb_ocr_dir");
        }
    }

    private static String after(String out) {
        return out.substring(out.indexOf('{'));
    }

    Capture agent(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(command, new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    record Capture(int code, String out, String err) {
    }
}
