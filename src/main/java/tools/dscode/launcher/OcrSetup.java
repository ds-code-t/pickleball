package tools.dscode.launcher;

import java.io.PrintStream;

/** Placeholder until D11 wires the ONNX OCR opt-in install. */
final class OcrSetup {
    private OcrSetup() {
    }

    static int run(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        err.println(WorkbenchCliHelp.structured("setup ocr is not available yet", "use setup desktop-driver").stripTrailing());
        return 2;
    }
}
