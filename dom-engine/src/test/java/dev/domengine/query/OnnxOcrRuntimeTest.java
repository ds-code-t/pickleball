package dev.domengine.query;

import dev.domengine.image.TemplateMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxOcrRuntimeTest {
    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        System.clearProperty("pkb_ocr_dir");
    }

    @Test
    void artifactsArePinnedWithChecksumsAndLicenses() {
        assertEquals(3, OnnxOcrRuntime.ARTIFACTS.size());
        for (OnnxOcrRuntime.Artifact artifact : OnnxOcrRuntime.ARTIFACTS) {
            assertTrue(artifact.sha256().matches("[0-9a-f]{64}"), artifact.toString());
            assertTrue(artifact.url().startsWith("https://"), artifact.toString());
            assertFalse(artifact.license().isBlank(), artifact.toString());
        }
        assertTrue(OnnxOcrRuntime.MESSAGE.contains("setup ocr --runtime=onnx --confirm"));
    }

    @Test
    void partialDirStaysNotInstalledAndNamesMissingFiles() throws Exception {
        Files.writeString(temp.resolve("en_dict.txt"), "a\nb\n");
        System.setProperty("pkb_ocr_dir", temp.toString());
        assertEquals(temp, OnnxOcrRuntime.dir());
        List<String> missing = OnnxOcrRuntime.missing(temp);
        assertEquals(List.of("onnxruntime-1.32.0.jar", "en_PP-OCRv3_rec_infer.onnx"), missing);
        assertFalse(new OnnxOcrRuntime().opensRuntime());
        OcrHook.Result result = new OnnxOcrRuntime().recognize(new TemplateMatch.Gray(1, 1, new int[]{0}), "h");
        assertEquals("ocr_runtime_missing", result.code());
        assertEquals(OnnxOcrRuntime.MESSAGE, result.error());
    }

    @Test
    void ctcDecodeDropsBlanksAndRepeatsAndMapsSpace() {
        List<String> dict = List.of("a", "b");
        float[][] steps = {
                {0, 9, 0, 0}, {0, 9, 0, 0}, {9, 0, 0, 0}, {0, 9, 0, 0}, {0, 0, 0, 9}, {0, 0, 9, 0}, {9, 0, 0, 0}
        };
        assertEquals("aa b", OnnxOcrRuntime.decode(steps, dict));
    }

    @Test
    void tensorIsNormalizedAtHeight48() {
        int[] width = new int[1];
        float[] data = OnnxOcrRuntime.tensor(new TemplateMatch.Gray(2, 1, new int[]{0, 255}), width);
        assertEquals(96, width[0]);
        assertEquals(3 * 48 * 96, data.length);
        assertEquals(-1f, data[0], 1e-6);
        assertEquals(1f, data[95], 1e-6);
    }

    @Test
    void recognizesRenderedTextWhenInstalled() {
        String dir = System.getenv("PKB_OCR_LIVE_DIR");
        Assumptions.assumeTrue(dir != null && OnnxOcrRuntime.missing(Path.of(dir)).isEmpty(),
                "live OCR skipped: set PKB_OCR_LIVE_DIR to an installed setup ocr dir");
        System.setProperty("pkb_ocr_dir", dir);
        BufferedImage image = new BufferedImage(240, 48, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 240, 48);
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        g.drawString("Submit 42", 10, 36);
        g.dispose();
        int[] pixels = new int[240 * 48];
        image.getRaster().getPixels(0, 0, 240, 48, pixels);
        OcrHook.Result result = new OnnxOcrRuntime().recognize(new TemplateMatch.Gray(240, 48, pixels), "live");
        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals("Submit 42", result.text());
    }
}
