package dev.domengine.query;

import dev.domengine.image.TemplateMatch;

/**
 * Slot for the Microsoft-signed ONNX Runtime. This build does not bundle the runtime
 * or a model, does not download either, and does not load a native library.
 */
public final class OnnxOcrRuntime implements OcrRuntime {
    public static final OnnxOcrRuntime INSTANCE = new OnnxOcrRuntime();
    static final String MESSAGE =
            "OCR runtime not installed: the Microsoft-signed ONNX Runtime and model are not bundled";

    public OnnxOcrRuntime() {
    }

    @Override
    public boolean opensRuntime() {
        return false;
    }

    @Override
    public OcrHook.Result recognize(TemplateMatch.Gray crop, String hash) {
        return OcrHook.Result.fail(MESSAGE, "ocr_runtime_missing", hash);
    }
}
