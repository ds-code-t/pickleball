package tools.dscode.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Current-behaviour docs describe what ships now. Dated records
 * ({@code docs/maintainer} other than the Dalton design, roadmaps, audits,
 * prompts) and {@code docs/release-notes.md} are history and stay out.
 * The generated repository index stays out; {@code refresh_agent_index.py --check}
 * owns it.
 */
class CurrentDocsTest {
    private static final Pattern RUST = Pattern.compile("(?i)\\brust\\b");
    private static final Pattern OPT_IN = Pattern.compile("(?i)opt-in");
    private static final Pattern DALTON = Pattern.compile("(?i)dalton");
    private static final Pattern ENGINE_RUST = Pattern.compile("pkb_composite_engine=rust");
    private static final Pattern HOME = Pattern.compile("COMPOSITE_HOME");
    private static final Pattern NATIVE = Pattern.compile("composite-native");
    private static final Pattern FIX_BACK = Pattern.compile("Fix-back");
    private static final Pattern ROUND3_HEADING = Pattern.compile("(?m)^#{1,6} .*Round 3");
    private static final Pattern DATED_HEADING = Pattern.compile("(?m)^#{1,6} .*\\(3\\.0\\.0\\)");
    private static final int OPT_IN_WINDOW = 80;

    @Test
    void currentBehaviourDocsMatchWhatShips() throws IOException {
        Path root = repoRoot();
        List<String> hits = new ArrayList<>();
        for (Path file : scan(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int n = i + 1;
                if (RUST.matcher(line).find()) hits.add(rel + ":" + n + ": Rust: " + clip(line));
                if (ENGINE_RUST.matcher(line).find()) hits.add(rel + ":" + n + ": engine=rust: " + clip(line));
                if (HOME.matcher(line).find()) hits.add(rel + ":" + n + ": COMPOSITE_HOME: " + clip(line));
                if (NATIVE.matcher(line).find()) hits.add(rel + ":" + n + ": composite-native: " + clip(line));
                if (FIX_BACK.matcher(line).find()) hits.add(rel + ":" + n + ": Fix-back: " + clip(line));
                if (ROUND3_HEADING.matcher(line).find()) hits.add(rel + ":" + n + ": Round 3 heading: " + clip(line));
                if (rel.equals("AGENTS.md") && DATED_HEADING.matcher(line).find()) {
                    hits.add(rel + ":" + n + ": dated heading: " + clip(line));
                }
            }
            optInNextToDalton(rel, text, hits);
        }
        assertTrue(hits.isEmpty(), String.join("\n", hits));
    }

    private static void optInNextToDalton(String rel, String text, List<String> hits) {
        List<Integer> dalton = starts(DALTON, text);
        List<Integer> optIn = starts(OPT_IN, text);
        for (int d : dalton) {
            for (int o : optIn) {
                if (Math.abs(d - o) <= OPT_IN_WINDOW) {
                    int line = text.substring(0, Math.min(d, o)).split("\n", -1).length;
                    hits.add(rel + ":" + line + ": opt-in next to Dalton");
                    return;
                }
            }
        }
    }

    private static List<Integer> starts(Pattern pattern, String text) {
        List<Integer> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found.add(matcher.start());
        return found;
    }

    private static String clip(String line) {
        String trimmed = line.trim();
        return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
    }

    private static List<Path> scan(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).filter(path -> include(root, path)).forEach(files::add);
        }
        return files;
    }

    private static boolean include(Path root, Path path) {
        String rel = root.relativize(path).toString().replace('\\', '/');
        if (rel.startsWith(".git/") || rel.contains("/build/") || rel.startsWith("build/")) return false;
        if (rel.equals("docs/release-notes.md") || rel.equals("docs/agent/repository-index.md")) return false;
        if (rel.startsWith("docs/maintainer/") && !rel.equals("docs/maintainer/3.0.0-dalton-design.md")) return false;
        if (rel.startsWith("docs/roadmaps/") || rel.startsWith("docs/audits/") || rel.startsWith("prompts/")) return false;
        if (rel.startsWith("src/main/resources/META-INF/pickleball/guidance/")) return false;
        return isCurrentDoc(rel);
    }

    private static boolean isCurrentDoc(String rel) {
        if (rel.equals("AGENTS.md") || rel.equals("README.md")) return true;
        if (rel.equals("docs/maintainer/3.0.0-dalton-design.md")) return true;
        if (rel.startsWith("docs/agent/") && rel.endsWith(".md")) return true;
        return rel.startsWith("docs/") && !rel.substring("docs/".length()).contains("/") && rel.endsWith(".md");
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) throw new IllegalStateException("settings.gradle not found");
        return dir;
    }
}
