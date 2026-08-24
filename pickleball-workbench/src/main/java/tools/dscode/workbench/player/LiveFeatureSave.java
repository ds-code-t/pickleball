package tools.dscode.workbench.player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Copies the live session buffer into the originating scenario of the original
 * {@code .feature} file. Demo buffers have no save path. Picker load never writes.
 */
public final class LiveFeatureSave {
    private LiveFeatureSave() {
    }

    public static WorkbenchSavePreview preview(LivePlaybackCoordinator playback) {
        Objects.requireNonNull(playback, "playback");
        ScenarioOrigin origin = playback.origin();
        if (!origin.savable()) {
            return WorkbenchSavePreview.unsavable(
                    "The default demo is session-only and has no original .feature file to write."
            );
        }
        List<String> body = scenarioBody(playback.player().documentText());
        String fileName = origin.file().getFileName().toString();
        String scenario = origin.scenarioName().isBlank() ? "(unnamed scenario)" : origin.scenarioName();
        return new WorkbenchSavePreview(
                true,
                origin.file(),
                origin.scenarioName(),
                "Copy these live steps into file " + fileName + " / scenario " + scenario + "?",
                body
        );
    }

    public static WorkbenchSaveResult write(LivePlaybackCoordinator playback) {
        WorkbenchSavePreview preview = preview(playback);
        if (!preview.savable()) {
            return WorkbenchSaveResult.unsavable(preview.summary());
        }
        Path file = preview.featurePath();
        ScenarioOrigin origin = playback.origin();
        try {
            List<String> original = Files.exists(file)
                    ? Files.readAllLines(file, StandardCharsets.UTF_8)
                    : new ArrayList<>();
            List<String> replacement = preview.liveScenarioLines();
            List<String> rewritten = splice(original, origin.startLine(), origin.endLine(), replacement);
            String newline = detectNewline(file);
            Files.writeString(file, join(rewritten, newline), StandardCharsets.UTF_8);
            int newEnd = origin.startLine() + replacement.size() - 1;
            playback.updateOrigin(origin.withEndLine(Math.max(origin.startLine(), newEnd)));
            return WorkbenchSaveResult.written(file, origin.scenarioName());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write the originating feature file: " + file, failure);
        }
    }

    static List<String> scenarioBody(String documentText) {
        List<String> lines = splitPreserve(documentText);
        int start = 0;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).strip();
            if (trimmed.startsWith("Scenario:") || trimmed.startsWith("Scenario Outline:")) {
                start = i;
                break;
            }
        }
        List<String> body = new ArrayList<>(lines.subList(start, lines.size()));
        while (!body.isEmpty() && body.getLast().isBlank()) {
            body.removeLast();
        }
        return body;
    }

    static List<String> splice(List<String> original, int startLine, int endLine, List<String> replacement) {
        List<String> rewritten = new ArrayList<>();
        int start = Math.max(1, startLine);
        int end = Math.max(start, endLine);
        int size = original.size();
        for (int i = 1; i < start && i <= size; i++) {
            rewritten.add(original.get(i - 1));
        }
        rewritten.addAll(replacement);
        for (int i = end + 1; i <= size; i++) {
            rewritten.add(original.get(i - 1));
        }
        return rewritten;
    }

    private static List<String> splitPreserve(String documentText) {
        if (documentText == null || documentText.isEmpty()) return new ArrayList<>();
        String normalized = documentText.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            lines.add(part);
        }
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    private static String detectNewline(Path file) throws IOException {
        if (!Files.exists(file)) return System.lineSeparator();
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (raw.contains("\r\n")) return "\r\n";
        if (raw.contains("\n")) return "\n";
        return System.lineSeparator();
    }

    private static String join(List<String> lines, String newline) {
        return String.join(newline, lines) + newline;
    }
}
