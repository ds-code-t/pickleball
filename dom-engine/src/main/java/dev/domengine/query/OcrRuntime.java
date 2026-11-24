package dev.domengine.query;

import dev.domengine.image.TemplateMatch;

/**
 * One OCR backend. {@link OcrHook} keeps the shared hash cache and the human-mask gate.
 * Selection is the {@code pkb_ocr_runtime} property, then env {@code PKB_OCR_RUNTIME}.
 * {@code onnx} is {@link OnnxOcrRuntime}. Any other value is a file path for {@link StubOcrRuntime}.
 */
public interface OcrRuntime {
    OcrHook.Result recognize(TemplateMatch.Gray crop, String hash);

    /** True when {@link #recognize} opens a runtime file. The ONNX slot does not. */
    default boolean opensRuntime() {
        return true;
    }
}
