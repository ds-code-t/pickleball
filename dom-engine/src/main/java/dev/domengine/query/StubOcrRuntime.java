package dev.domengine.query;

import dev.domengine.image.TemplateMatch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Stub/test lookup runtime. A text file whose first line is {@code pkb-ocr 1}
 * maps a crop hash to the rest of that line. The file is not executed.
 */
public final class StubOcrRuntime implements OcrRuntime {
    static final String HEADER = "pkb-ocr 1";
    private final Path path;

    public StubOcrRuntime(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    @Override
    public OcrHook.Result recognize(TemplateMatch.Gray crop, String hash) {
        Answer answer = lookup(path, hash);
        if (!answer.ok) return OcrHook.Result.fail(answer.error, answer.code, hash);
        return OcrHook.Result.hit(answer.text, hash, false);
    }

    private static Answer lookup(Path path, String hash) {
        byte[] head = new byte[16];
        int read;
        try (InputStream in = Files.newInputStream(path)) {
            read = in.read(head);
        } catch (IOException failure) {
            return Answer.fail("OCR runtime cannot be invoked", "ocr_runtime_unsupported");
        }
        String prefix = new String(head, 0, Math.max(0, read), StandardCharsets.UTF_8);
        if (!prefix.startsWith(HEADER)) {
            return Answer.fail("OCR runtime cannot be invoked", "ocr_runtime_unsupported");
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int space = indexOfWhitespace(trimmed);
                String key = (space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT);
                if (!key.equals(hash)) continue;
                String text = space < 0 ? "" : trimmed.substring(space).trim();
                return Answer.ok(text);
            }
        } catch (IOException failure) {
            return Answer.fail("OCR runtime cannot be invoked", "ocr_runtime_unsupported");
        }
        return Answer.ok("");
    }

    private static int indexOfWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    private record Answer(boolean ok, String text, String error, String code) {
        static Answer ok(String text) {
            return new Answer(true, text == null ? "" : text, "", "");
        }

        static Answer fail(String error, String code) {
            return new Answer(false, "", error, code);
        }
    }
}
