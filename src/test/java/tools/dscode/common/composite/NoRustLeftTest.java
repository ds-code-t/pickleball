package tools.dscode.common.composite;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when a removed engine name or lookup is still described as current
 * behaviour. Dated records stay out. Three G1 documents stay out until the
 * Phase 7 docs sweep, because G1 is still editing those sections:
 * {@code docs/configuration.md}, {@code docs/direct-control-handoff.md}, and
 * {@code docs/dynamic-control-api.md}. Generated guidance mirrors are copies
 * of those docs and are checked by {@code sync_consumer_guidance.py --check}.
 */
class NoRustLeftTest {
    private static final Pattern[] FORBIDDEN = {
            Pattern.compile("(?i)\\brust\\b"),
            Pattern.compile("COMPOSITE_HOME"),
            Pattern.compile("composite-native"),
            Pattern.compile("target/release/composite"),
            Pattern.compile("domcomposite/natives"),
            Pattern.compile("resolveRustBinary"),
            Pattern.compile("rustRequested")
    };
    private static final List<String> G1_DOCS_LEFT_FOR_PHASE_7 = List.of(
            "docs/configuration.md",
            "docs/direct-control-handoff.md",
            "docs/dynamic-control-api.md"
    );

    @Test
    void mainSourcesBuildScriptsAndCurrentDocsHaveNoRemovedEngine() throws IOException {
        Path root = repoRoot();
        List<String> hits = new ArrayList<>();
        for (Path file : scan(root)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                for (Pattern pattern : FORBIDDEN) {
                    if (pattern.matcher(line).find()) {
                        hits.add(root.relativize(file) + ":" + (i + 1) + ": " + line.trim());
                        break;
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), String.join("\n", hits));
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
        if (rel.contains("/src/test/") || rel.startsWith("src/test/")) return false;
        if (rel.startsWith("src/main/resources/META-INF/pickleball/guidance/")) return false;
        if (rel.startsWith("docs/maintainer/") && !rel.equals("docs/maintainer/3.0.0-dalton-design.md")) return false;
        if (rel.startsWith("docs/roadmaps/") || rel.startsWith("docs/audits/") || rel.startsWith("prompts/")) return false;
        if (rel.equals("docs/release-notes.md") || rel.equals("docs/agent/repository-index.md")) return false;
        if (G1_DOCS_LEFT_FOR_PHASE_7.contains(rel)) return false;
        if (isMain(rel)) return textFile(path);
        if (isBuildScript(rel)) return true;
        return isCurrentDoc(rel);
    }

    private static boolean isMain(String rel) {
        return rel.contains("/src/main/") || rel.startsWith("src/main/");
    }

    private static boolean isBuildScript(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return name.equals("build.gradle") || name.equals("settings.gradle")
                || name.equals("build.gradle.kts") || name.equals("settings.gradle.kts");
    }

    private static boolean isCurrentDoc(String rel) {
        if (rel.equals("AGENTS.md") || rel.equals("README.md")) return true;
        if (rel.equals("docs/maintainer/3.0.0-dalton-design.md")) return true;
        if (rel.startsWith("docs/agent/") && rel.endsWith(".md")) return true;
        return rel.startsWith("docs/") && !rel.substring("docs/".length()).contains("/") && rel.endsWith(".md");
    }

    private static boolean textFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".md") || name.endsWith(".txt")
                || name.endsWith(".properties") || name.endsWith(".html") || name.endsWith(".js")
                || name.endsWith(".css") || name.endsWith(".json") || name.endsWith(".xml")
                || name.endsWith(".gradle") || name.endsWith(".kts");
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
