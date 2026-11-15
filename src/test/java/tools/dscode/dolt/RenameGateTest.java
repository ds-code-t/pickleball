package tools.dscode.dolt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two rename gates. Git listing respects the same ignores {@code rg} uses.
 * The needle is split so this file itself is not a hit.
 */
class RenameGateTest {
    private static final String NEEDLE = "dal" + "ton";
    private static final Path ALLOWLIST = Path.of("scripts/rename-history-allowlist.txt");

    @Test
    void formerNameStaysOnTheAllowlist() throws Exception {
        Set<String> allowed = allowlist();
        List<String> contentHits = new ArrayList<>();
        for (String path : git("ls-files", "-co", "--exclude-standard")) {
            if (allowed.contains(path)) continue;
            if (contains(Path.of(path))) contentHits.add(path);
        }
        List<String> pathHits = new ArrayList<>();
        for (String path : git("ls-files")) {
            if (path.toLowerCase(Locale.ROOT).contains(NEEDLE) && !allowed.contains(path)) {
                pathHits.add(path);
            }
        }
        assertEquals(List.of(), contentHits, "content");
        assertEquals(List.of(), pathHits, "paths");

        String agents = Files.readString(Path.of("AGENTS.md"));
        assertEquals(1, count(agents.toLowerCase(Locale.ROOT), NEEDLE), agents);
        assertTrue(agents.contains("former name of the Dolt core"), agents);

        List<String> cucumber = new ArrayList<>();
        Path dolt = Path.of("src/main/java/tools/dscode/dolt");
        try (var walk = Files.walk(dolt)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file).contains("io.cucumber")) {
                    cucumber.add(dolt.relativize(file).toString());
                }
            }
        }
        assertEquals(List.of(), cucumber);
    }

    private static Set<String> allowlist() throws Exception {
        Set<String> allowed = new HashSet<>();
        for (String line : Files.readAllLines(ALLOWLIST)) {
            if (!line.isBlank() && !line.startsWith("#")) allowed.add(line.trim());
        }
        return allowed;
    }

    private static boolean contains(Path path) throws Exception {
        if (!Files.isRegularFile(path)) return false;
        byte[] bytes = Files.readAllBytes(path);
        int sniff = Math.min(bytes.length, 8000);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) return false;
        }
        String text = new String(bytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return text.contains(NEEDLE);
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }

    private static List<String> git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile()).start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread stdout = Thread.ofVirtual().start(() -> drain(process.getInputStream(), out));
        Thread stderr = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), err));
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        stdout.join(5_000);
        stderr.join(5_000);
        if (!finished || process.exitValue() != 0) {
            throw new AssertionError("git exit " + (finished ? process.exitValue() : "timeout") + " " + err);
        }
        Set<String> sorted = new TreeSet<>();
        for (String line : out.toString().split("\n")) {
            if (!line.isBlank()) sorted.add(line.trim());
        }
        return List.copyOf(sorted);
    }

    private static void drain(java.io.InputStream input, StringBuilder into) {
        try {
            into.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            into.append(failure.getMessage());
        }
    }
}
