package tools.dscode.launcher;

import tools.dscode.control.protocol.WorkbenchFeatureIndex;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dry-run over the feature index and consumer glue. It does not start a
 * browser, Maven, or a worker. Undefined and ambiguous steps and unresolved
 * {@code RUN SCENARIO} targets are one line each.
 */
public final class WorkbenchValidate {
    private static final Pattern STEP = Pattern.compile(
            "^\\s*(?::+\\s*)?(?:Given|When|Then|And|But|\\*)\\s+(.*)$"
    );
    private static final Pattern ANNOTATION = Pattern.compile(
            "@(?:Given|When|Then|And|But)\\s*\\(\\s*([\"'])(.*?)\\1",
            Pattern.DOTALL
    );
    private static final Pattern RUN_TARGET = Pattern.compile(
            "^RUN SCENARIOS?\\s*:\\s*(.+)$"
    );

    private WorkbenchValidate() {
    }

    public record Finding(String kind, String where, String text) {
    }

    public static int run(Path project, String tags, String name, PrintStream out, PrintStream err) {
        if (project == null || !Files.isDirectory(project)) {
            if (err != null) err.print(WorkbenchCliHelp.usageError("missing project", "validate"));
            return 2;
        }
        long started = System.nanoTime();
        List<String> glue = gluePatterns(project);
        WorkbenchFeatureIndex.Selection all = WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                project, List.of(), null, null, null, List.of(), false
        ));
        boolean filtered = (tags != null && !tags.isBlank()) || (name != null && !name.isBlank());
        WorkbenchFeatureIndex.Selection selection = filtered
                ? WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                        project, List.of(), tags, name, null, List.of(), false))
                : all;
        List<Finding> findings = new ArrayList<>();
        for (WorkbenchFeatureIndex.Hit hit : selection.hits()) {
            Path file = project.resolve(hit.relativePath().replace('/', java.io.File.separatorChar));
            findings.addAll(scan(project, file, hit.startLine(), hit.endLine(), glue, all));
        }
        if (selection.hits().isEmpty() && selection.fullScanFallback()) {
            findings.addAll(scanTree(project, glue, all));
        }
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        int undefined = count(findings, "undefined");
        int ambiguous = count(findings, "ambiguous");
        int unresolved = count(findings, "unresolved");
        if (out != null) {
            for (Finding finding : findings) {
                out.println(finding.kind() + ": " + finding.where() + " " + finding.text());
            }
            out.println("validate undefined=" + undefined
                    + " ambiguous=" + ambiguous
                    + " unresolved=" + unresolved
                    + " elapsedMs=" + elapsed);
        }
        return undefined + ambiguous + unresolved == 0 ? 0 : 1;
    }

    private static List<Finding> scanTree(Path project, List<String> glue, WorkbenchFeatureIndex.Selection selection) {
        List<Finding> findings = new ArrayList<>();
        Path features = project.resolve("src/test/resources");
        if (!Files.isDirectory(features)) return findings;
        try (Stream<Path> walk = Files.walk(features)) {
            walk.filter(path -> path.toString().endsWith(".feature")).forEach(file ->
                    findings.addAll(scan(project, file, 1, Integer.MAX_VALUE, glue, selection)));
        } catch (IOException ignored) {
            // A missing tree is an empty validate, not a launch.
        }
        return findings;
    }

    static List<Finding> scan(
            Path project,
            Path file,
            int start,
            int end,
            List<String> glue,
            WorkbenchFeatureIndex.Selection selection
    ) {
        List<Finding> findings = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) return findings;
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return findings;
        }
        String where = project == null ? file.toString() : project.relativize(file).toString().replace('\\', '/');
        int from = Math.max(1, start);
        int to = Math.min(lines.size(), end <= 0 ? lines.size() : end);
        for (int number = from; number <= to; number++) {
            String raw = lines.get(number - 1);
            Matcher step = STEP.matcher(raw);
            if (!step.matches()) continue;
            String text = step.group(1).strip();
            if (text.isBlank() || framework(text)) {
                findings.addAll(runTarget(where, number, text, selection));
                continue;
            }
            int matches = countGlue(glue, text);
            if (matches > 1) findings.add(new Finding("ambiguous", where + ":" + number, text));
            else if (matches == 0) findings.add(new Finding("undefined", where + ":" + number, text));
        }
        return findings;
    }

    private static List<Finding> runTarget(
            String where,
            int number,
            String text,
            WorkbenchFeatureIndex.Selection selection
    ) {
        Matcher matcher = RUN_TARGET.matcher(text.strip());
        if (!matcher.matches()) return List.of();
        String target = matcher.group(1).strip();
        if (target.isBlank() || target.indexOf('<') >= 0) return List.of();
        if (selection == null) return List.of(new Finding("unresolved", where + ":" + number, target));
        boolean found = false;
        String name = target.startsWith("%") || target.startsWith("@") ? "" : target;
        String tag = target.startsWith("%") ? "@" + target.substring(1) : target.startsWith("@") ? target : "";
        for (WorkbenchFeatureIndex.Hit hit : selection.hits()) {
            if (!name.isEmpty() && hit.scenarioName() != null && hit.scenarioName().contains(name)) {
                found = true;
                break;
            }
            if (!tag.isEmpty() && hit.tags() != null && hit.tags().stream().anyMatch(tag::equals)) {
                found = true;
                break;
            }
        }
        if (found) return List.of();
        return List.of(new Finding("unresolved", where + ":" + number, target));
    }

    static boolean framework(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.strip();
        if (trimmed.startsWith(",")) return true;
        String head = trimmed.toUpperCase(Locale.ROOT);
        return head.startsWith("TRACE:")
                || head.startsWith("DEBUG:")
                || head.startsWith("INFO:")
                || head.startsWith("WARN:")
                || head.startsWith("ERROR:")
                || head.startsWith("FAIL:")
                || head.startsWith("ASSERT:")
                || head.startsWith("SOFT ASSERT:")
                || head.startsWith("SOFT FAIL")
                || head.startsWith("RUN ")
                || head.startsWith("IF ")
                || head.startsWith("IF:")
                || head.startsWith("ELSE")
                || head.startsWith("MAP ")
                || head.startsWith("CLEAR ")
                || head.startsWith("ALWAYS ")
                || head.startsWith("END SCENARIO")
                || head.startsWith("END TEST")
                || head.startsWith("NAVIGATE TO:");
    }

    private static int countGlue(List<String> glue, String text) {
        int matches = 0;
        for (String pattern : glue) {
            if (patternMatches(pattern, text)) matches++;
        }
        return matches;
    }

    static boolean patternMatches(String cucumberPattern, String step) {
        if (cucumberPattern == null || cucumberPattern.isBlank()) return false;
        if (cucumberPattern.equals(step)) return true;
        String regex = cucumberPattern
                .replace("{string}", "\"([^\"]*)\"")
                .replace("{int}", "(-?\\d+)")
                .replace("{word}", "(\\S+)");
        try {
            return Pattern.compile("^" + regex + "$").matcher(step).matches();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static List<String> gluePatterns(Path project) {
        List<String> patterns = new ArrayList<>();
        for (Path root : List.of(project.resolve("src/test/java"), project.resolve("src/main/java"))) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(file -> patterns.addAll(readGlue(file)));
            } catch (IOException ignored) {
                // Glue that cannot be read is not a match.
            }
        }
        return patterns;
    }

    private static List<String> readGlue(Path file) {
        List<String> patterns = new ArrayList<>();
        try {
            Matcher annotations = ANNOTATION.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (annotations.find()) patterns.add(annotations.group(2));
        } catch (IOException ignored) {
            return List.of();
        }
        return patterns;
    }

    private static int count(List<Finding> findings, String kind) {
        int count = 0;
        for (Finding finding : findings) if (kind.equals(finding.kind())) count++;
        return count;
    }
}
