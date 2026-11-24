package tools.dscode.launcher;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.query.OnnxOcrRuntime;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code setup ocr --runtime=onnx [--dir=<path>] [--confirm]} (D11). Prints the licenses (ONNX Runtime MIT,
 * PP-OCRv3 English model and dictionary Apache-2.0) and the pinned files; only with {@code --confirm} does it download
 * them, each checked against its pinned SHA-256 before it lands in the OCR dir. Nothing else ever downloads them.
 */
final class OcrSetup {
    /** Test-only mirror dir holding files with the pinned names; never set in normal use. */
    static final String MIRROR_PROPERTY = "pkb.ocr.setup.mirror";

    private OcrSetup() {
    }

    static int run(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String runtime = SetupCommand.value(parsed.forwarded(), "--runtime");
        if (runtime == null) runtime = "onnx";
        if (!"onnx".equalsIgnoreCase(runtime.trim())) {
            err.println(WorkbenchCliHelp.structured("setup ocr supports --runtime=onnx only", "setup ocr --runtime=onnx [--dir=<path>] [--confirm]").stripTrailing());
            return 2;
        }
        String explicit = SetupCommand.value(parsed.forwarded(), "--dir");
        Path dir = explicit == null || explicit.isBlank()
                ? SetupCommand.dir(parsed, "ocr").resolve(OnnxOcrRuntime.MODEL_ID).resolve("1")
                : Path.of(explicit).toAbsolutePath().normalize();
        ObjectNode body = SetupCommand.JSON.createObjectNode();
        body.put("ok", true).put("op", "setup").put("component", "ocr").put("runtime", "onnx")
                .put("model", OnnxOcrRuntime.MODEL_ID).put("dir", dir.toString());
        ArrayNode files = body.putArray("files");
        for (OnnxOcrRuntime.Artifact artifact : OnnxOcrRuntime.ARTIFACTS) {
            files.addObject().put("name", artifact.name()).put("source", artifact.url())
                    .put("sha256", artifact.sha256()).put("license", artifact.license());
            out.println("license: " + artifact.license() + " (" + artifact.name() + ", " + artifact.url() + ")");
        }
        if (explicit != null && !explicit.isBlank()) body.put("use", "set pkb_ocr_dir=" + dir + " (or PKB_OCR_DIR) so the onnx runtime finds it");
        if (!SetupCommand.confirmed(parsed.forwarded())) {
            body.put("installed", OnnxOcrRuntime.missing(dir).isEmpty());
            body.put("next", "re-run with --confirm to download and verify the ONNX OCR runtime and model");
            out.println(body);
            return 0;
        }
        String mirror = System.getProperty(MIRROR_PROPERTY);
        try {
            for (OnnxOcrRuntime.Artifact artifact : OnnxOcrRuntime.ARTIFACTS) {
                if (Files.isRegularFile(dir.resolve(artifact.name()))) continue;
                URI source = mirror == null || mirror.isBlank() ? URI.create(artifact.url()) : Path.of(mirror, artifact.name()).toUri();
                SetupCommand.fetchVerified(source, artifact.sha256(), dir);
            }
            Files.writeString(dir.resolve("LICENSE-NOTICE.txt"),
                    "ONNX Runtime 1.32.0 (MIT, Microsoft); PP-OCRv3 English recognition model and en_dict.txt (Apache-2.0, PaddlePaddle/PaddleOCR via SWHL/RapidOCR)\n");
            body.put("installed", true);
            out.println(body);
            return 0;
        } catch (IOException failure) {
            err.println(WorkbenchCliHelp.structured("setup ocr failed: " + failure.getMessage(),
                    "check the network, or download the pinned files yourself into " + dir).stripTrailing());
            return 1;
        }
    }
}
