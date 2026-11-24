package dev.domengine.query;

import dev.domengine.image.TemplateMatch;
import dev.domengine.log.EngineLog;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ONNX OCR runtime (D11), opt-in only. Pickleball does not bundle ONNX Runtime or a model and never downloads
 * them on its own: {@code setup ocr --runtime=onnx --confirm} puts the pinned, checksum-verified files in the OCR
 * dir ({@code pkb_ocr_dir} property, env {@code PKB_OCR_DIR}, default {@code ~/.pickleball/opt-in/ocr/pp-ocrv3-en/1}).
 * When all three files are there, ONNX Runtime for Java is loaded reflectively from that dir and the PP-OCRv3
 * English recognition model reads the crop (CTC greedy decode). Otherwise every call is {@link #MESSAGE}.
 */
public final class OnnxOcrRuntime implements OcrRuntime {
    public static final OnnxOcrRuntime INSTANCE = new OnnxOcrRuntime();
    static final String MESSAGE =
            "OCR runtime not installed: the Microsoft-signed ONNX Runtime and model are not bundled;"
                    + " fix: run setup ocr --runtime=onnx --confirm (ONNX Runtime MIT, PP-OCRv3 model Apache-2.0)";

    /** One pinned opt-in file: name, source URL, SHA-256, license. */
    public record Artifact(String name, String url, String sha256, String license) {
    }

    public static final String MODEL_ID = "pp-ocrv3-en";
    public static final List<Artifact> ARTIFACTS = List.of(
            new Artifact("onnxruntime-1.32.0.jar",
                    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.32.0/onnxruntime-1.32.0.jar",
                    "5f14563a52ac9dd5166c18b18ce837fd7589d4834e76e4e552c7b053d4b0538c", "MIT"),
            new Artifact("en_PP-OCRv3_rec_infer.onnx",
                    "https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv3/en_PP-OCRv3_rec_infer.onnx",
                    "ef7abd8bd3629ae57ea2c28b425c1bd258a871b93fd2fe7c433946ade9b5d9ea", "Apache-2.0"),
            new Artifact("en_dict.txt",
                    "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/v2.7.0/ppocr/utils/en_dict.txt",
                    "5662df9d2d03f0e8ca0d3b0649d6acbab904b6a14b3d3521463c71c37c668ce3", "Apache-2.0")
    );

    private volatile Loaded loaded;

    public OnnxOcrRuntime() {
    }

    public static Path dir() {
        String explicit = System.getProperty("pkb_ocr_dir");
        if (explicit == null || explicit.isBlank()) explicit = System.getenv("PKB_OCR_DIR");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit.trim());
        return Path.of(System.getProperty("user.home"), ".pickleball", "opt-in", "ocr", MODEL_ID, "1");
    }

    /** Missing files in the OCR dir; empty when the runtime can load. */
    public static List<String> missing(Path dir) {
        List<String> out = new ArrayList<>();
        for (Artifact artifact : ARTIFACTS) if (!Files.isRegularFile(dir.resolve(artifact.name()))) out.add(artifact.name());
        return out;
    }

    @Override
    public boolean opensRuntime() {
        return missing(dir()).isEmpty();
    }

    @Override
    public OcrHook.Result recognize(TemplateMatch.Gray crop, String hash) {
        Path dir = dir();
        if (!missing(dir).isEmpty()) return OcrHook.Result.fail(MESSAGE, "ocr_runtime_missing", hash);
        try {
            Loaded runtime = load(dir);
            String text = runtime.read(crop);
            return OcrHook.Result.hit(text, hash, false);
        } catch (Throwable failure) {
            EngineLog.warn("ocr", "onnx runtime failed: " + failure);
            return OcrHook.Result.fail("OCR runtime cannot be invoked: " + root(failure), "ocr_runtime_unsupported", hash);
        }
    }

    private Loaded load(Path dir) throws Exception {
        Loaded current = loaded;
        if (current != null && current.dir.equals(dir)) return current;
        synchronized (this) {
            if (loaded == null || !loaded.dir.equals(dir)) loaded = new Loaded(dir);
            return loaded;
        }
    }

    private static String root(Throwable failure) {
        Throwable at = failure;
        while (at.getCause() != null) at = at.getCause();
        return at.getClass().getSimpleName() + (at.getMessage() == null ? "" : ": " + at.getMessage());
    }

    /** Height and width bounds of the recognition input. */
    static final int HEIGHT = 48;
    static final int MAX_WIDTH = 320;

    /** Gray crop to a normalized [1,3,48,W] tensor, as PaddleOCR's recognizer expects. */
    static float[] tensor(TemplateMatch.Gray crop, int[] widthOut) {
        int w = Math.max(1, crop.width());
        int h = Math.max(1, crop.height());
        int width = Math.max(16, Math.min(MAX_WIDTH, (int) Math.ceil(HEIGHT * (double) w / h)));
        widthOut[0] = width;
        float[] data = new float[3 * HEIGHT * width];
        for (int y = 0; y < HEIGHT; y++) {
            int sy = Math.min(h - 1, y * h / HEIGHT);
            for (int x = 0; x < width; x++) {
                int sx = Math.min(w - 1, x * w / width);
                int pixel = crop.pixels()[sy * w + sx] & 0xFF;
                float value = (pixel / 255f - 0.5f) / 0.5f;
                for (int c = 0; c < 3; c++) data[c * HEIGHT * width + y * width + x] = value;
            }
        }
        return data;
    }

    /** CTC greedy decode: index 0 is blank, then the dictionary, then a space. */
    static String decode(float[][] steps, List<String> dict) {
        StringBuilder text = new StringBuilder();
        int previous = -1;
        for (float[] step : steps) {
            int best = 0;
            for (int i = 1; i < step.length; i++) if (step[i] > step[best]) best = i;
            if (best != 0 && best != previous) {
                if (best - 1 < dict.size()) text.append(dict.get(best - 1));
                else if (best - 1 == dict.size()) text.append(' ');
            }
            previous = best;
        }
        return text.toString().trim();
    }

    private static final class Loaded {
        final Path dir;
        final List<String> dict;
        final Object env;
        final Object session;
        final Method createTensor;
        final Method run;
        final Method getValue;
        final String input;

        Loaded(Path dir) throws Exception {
            this.dir = dir;
            this.dict = Files.readAllLines(dir.resolve("en_dict.txt"));
            URLClassLoader loader = new URLClassLoader(new URL[]{dir.resolve(ARTIFACTS.get(0).name()).toUri().toURL()},
                    OnnxOcrRuntime.class.getClassLoader());
            Class<?> envType = loader.loadClass("ai.onnxruntime.OrtEnvironment");
            Class<?> optionsType = loader.loadClass("ai.onnxruntime.OrtSession$SessionOptions");
            Class<?> sessionType = loader.loadClass("ai.onnxruntime.OrtSession");
            Class<?> tensorType = loader.loadClass("ai.onnxruntime.OnnxTensor");
            Class<?> valueType = loader.loadClass("ai.onnxruntime.OnnxValue");
            this.env = envType.getMethod("getEnvironment").invoke(null);
            Object options = optionsType.getConstructor().newInstance();
            this.session = envType.getMethod("createSession", String.class, optionsType)
                    .invoke(env, dir.resolve(ARTIFACTS.get(1).name()).toString(), options);
            @SuppressWarnings("unchecked")
            Set<String> inputs = (Set<String>) sessionType.getMethod("getInputNames").invoke(session);
            this.input = inputs.iterator().next();
            this.createTensor = tensorType.getMethod("createTensor", envType, FloatBuffer.class, long[].class);
            this.run = sessionType.getMethod("run", Map.class);
            this.getValue = valueType.getMethod("getValue");
            EngineLog.info("ocr", "onnx runtime loaded from " + dir);
        }

        synchronized String read(TemplateMatch.Gray crop) throws Exception {
            int[] width = new int[1];
            float[] data = tensor(crop, width);
            Object tensor = createTensor.invoke(null, env, FloatBuffer.wrap(data), new long[]{1, 3, HEIGHT, width[0]});
            try (AutoCloseable ignored = (AutoCloseable) tensor) {
                Object result = run.invoke(session, Map.of(input, tensor));
                try (AutoCloseable closing = (AutoCloseable) result) {
                    Object first = result.getClass().getMethod("get", int.class).invoke(result, 0);
                    float[][][] value = (float[][][]) getValue.invoke(first);
                    return decode(value[0], dict);
                }
            }
        }
    }
}
