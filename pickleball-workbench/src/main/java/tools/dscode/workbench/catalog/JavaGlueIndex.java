package tools.dscode.workbench.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Display-only index of consumer {@code @Given}/{@code @When}/{@code @Then} methods.
 * This is not Cucumber's matcher and does not execute steps.
 */
public final class JavaGlueIndex {
    private static final Pattern ANNOTATION = Pattern.compile(
            "@(?:Given|When|Then|And|But)\\s*\\(\\s*([\"`'])(.*?)\\1",
            Pattern.DOTALL
    );
    private static final Pattern METHOD = Pattern.compile(
            "(?:public|protected|private)?\\s*(?:static\\s+)?[\\w.<>,\\[\\]]+\\s+(\\w+)\\s*\\("
    );

    public record Match(
            String pattern,
            String className,
            String methodName,
            Path file,
            String snippet
    ) {
        public Match {
            pattern = pattern == null ? "" : pattern;
            className = className == null ? "" : className;
            methodName = methodName == null ? "" : methodName;
            snippet = snippet == null ? "" : snippet;
        }
    }

    private final List<Match> matches;

    public JavaGlueIndex(List<Match> matches) {
        this.matches = List.copyOf(matches == null ? List.of() : matches);
    }

    public static JavaGlueIndex scan(Path projectRoot) {
        List<Match> found = new ArrayList<>();
        if (projectRoot == null) return new JavaGlueIndex(found);
        List<Path> roots = List.of(
                projectRoot.resolve("src").resolve("test").resolve("java"),
                projectRoot.resolve("src").resolve("main").resolve("java")
        );
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root, 16)) {
                walk.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                        .forEach(path -> found.addAll(readFile(path)));
            } catch (IOException ignored) {
                // Index is best-effort display.
            }
        }
        return new JavaGlueIndex(found);
    }

    public Optional<Match> find(String stepText) {
        String haystack = stripKeyword(stepText);
        if (haystack.isBlank()) return Optional.empty();
        for (Match match : matches) {
            if (patternMatches(match.pattern(), haystack)) return Optional.of(match);
        }
        return Optional.empty();
    }

    public List<Match> matches() {
        return matches;
    }

    static String stripKeyword(String text) {
        String trimmed = text == null ? "" : text.strip();
        for (String keyword : List.of("Given ", "When ", "Then ", "And ", "But ", "* ")) {
            if (trimmed.startsWith(keyword)) return trimmed.substring(keyword.length()).strip();
        }
        return trimmed;
    }

    static boolean patternMatches(String cucumberPattern, String step) {
        if (cucumberPattern == null || cucumberPattern.isBlank()) return false;
        if (cucumberPattern.equals(step)) return true;
        String regex = cucumberPattern
                .replace("\\\\", "\\")
                .replace("{string}", "\"([^\"]*)\"")
                .replace("{int}", "(-?\\d+)")
                .replace("{float}", "(-?\\d+(?:\\.\\d+)?)")
                .replace("{word}", "(\\S+)");
        try {
            return Pattern.compile("^" + regex + "$").matcher(step).matches();
        } catch (RuntimeException ignored) {
            return step.toLowerCase(Locale.ROOT).contains(cucumberPattern.toLowerCase(Locale.ROOT));
        }
    }

    private static List<Match> readFile(Path file) {
        List<Match> found = new ArrayList<>();
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String className = className(file, source);
            Matcher annotations = ANNOTATION.matcher(source);
            while (annotations.find()) {
                String pattern = annotations.group(2);
                String rest = source.substring(annotations.end());
                Matcher method = METHOD.matcher(rest);
                String methodName = method.find() ? method.group(1) : "";
                int from = Math.max(0, annotations.start());
                int to = Math.min(source.length(), annotations.end() + 240);
                String snippet = source.substring(from, to);
                found.add(new Match(pattern, className, methodName, file, snippet.strip()));
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return found;
    }

    private static String className(Path file, String source) {
        Matcher pack = Pattern.compile("package\\s+([\\w.]+)\\s*;").matcher(source);
        String pkg = pack.find() ? pack.group(1) + "." : "";
        String name = file.getFileName().toString();
        if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
        return pkg + name;
    }
}