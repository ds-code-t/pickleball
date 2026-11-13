package tools.dscode.dalton;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scenario and step edits splice one region of the feature, then write the
 * regenerated text through {@code feature put} (Dalton on) or the file
 * (Dalton off). Comments outside that region stay. The edited region is
 * indented to the feature's existing scenario indent, or two and four spaces
 * when the feature has no scenario yet. The result is always parsed Gherkin.
 */
final class GherkinRewrite {
    private static final String[] SCENARIO_HEADERS = {
            "Scenario Outline:",
            "Scenario Template:",
            "Background:",
            "Scenario:",
            "Example:",
            "Rule:"
    };
    private static final String[] STEP_KEYWORDS = {"Given", "When", "Then", "And", "But", "*"};

    private GherkinRewrite() {
    }

    static boolean available() {
        return true;
    }

    static DaltonEdit.Outcome apply(Path project, String action, String feature, List<String> tokens) {
        try {
            DaltonEdit.refuseConflict(project, feature, tokens.contains("--force"));
            String current = currentText(project, feature);
            String rewritten = edit(project, current, action, feature, tokens);
            if (DaltonGherkin.parse(rewritten) == null) {
                throw new IllegalArgumentException("Gherkin is not valid: " + feature);
            }
            byte[] body = rewritten.getBytes(StandardCharsets.UTF_8);
            boolean dalton = DaltonConfig.enabled(project);
            if (dalton) {
                DaltonContent.put(project, feature, body);
            } else {
                Path disk = project.resolve(feature);
                Files.createDirectories(disk.getParent());
                Files.write(disk, body);
            }
            String name = feature.substring(feature.lastIndexOf('/') + 1);
            return DaltonEdit.Outcome.written(feature, name, dalton, rewritten);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException(failure.getMessage() == null ? "gherkin rewrite failed" : failure.getMessage());
        }
    }

    static String edit(Path project, String current, String action, String feature, List<String> tokens) {
        String newline = current != null && current.contains("\r\n") ? "\r\n" : "\n";
        boolean trailing = current == null || current.isEmpty() || current.endsWith("\n") || current.endsWith("\r");
        List<String> lines = split(current);
        return switch (action) {
            case "scenario-add" -> join(addScenario(project, lines, feature, tokens), newline, trailing);
            case "scenario-modify" -> join(modifyScenario(project, lines, tokens), newline, trailing);
            case "scenario-delete" -> join(deleteScenario(lines, tokens), newline, trailing);
            case "step-add" -> join(addStep(project, lines, tokens), newline, trailing);
            case "step-modify" -> join(modifyStep(project, lines, tokens), newline, trailing);
            case "step-delete" -> join(deleteStep(lines, tokens), newline, trailing);
            default -> throw new IllegalArgumentException("Unknown Dalton edit command: feature " + action);
        };
    }

    private static List<String> addScenario(Path project, List<String> lines, String feature, List<String> tokens) {
        if (lines.isEmpty()) {
            String stem = stem(feature);
            lines.add("Feature: " + stem);
            lines.add("");
        }
        String name = option(tokens, "--name=");
        Indent indent = indent(lines);
        List<String> block = new ArrayList<>();
        block.add(indent.scenario + "Scenario: " + name.trim());
        block.addAll(stepLines(body(project, tokens), option(tokens, "--keyword="), indent.step));
        if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
        lines.addAll(block);
        return lines;
    }

    private static List<String> modifyScenario(Path project, List<String> lines, List<String> tokens) {
        Block block = find(lines, selector(tokens));
        String rename = option(tokens, "--scenario=") != null ? option(tokens, "--name=") : null;
        if (rename != null && !rename.isBlank()) {
            lines.set(block.header, renameHeader(lines.get(block.header), rename.trim()));
        }
        if (option(tokens, "--text=") == null && option(tokens, "--file=") == null) {
            if (rename == null || rename.isBlank()) {
                throw new IllegalArgumentException("scenario-modify needs --text, --file, or a new --name");
            }
            return lines;
        }
        Indent indent = indent(lines);
        int examples = examplesAt(lines, block);
        List<String> kept = comments(lines, block.header + 1, examples);
        List<String> replacement = new ArrayList<>();
        replacement.addAll(kept);
        replacement.addAll(stepLines(body(project, tokens), option(tokens, "--keyword="), indent.step));
        lines.subList(block.header + 1, examples).clear();
        lines.addAll(block.header + 1, replacement);
        return lines;
    }

    private static List<String> deleteScenario(List<String> lines, List<String> tokens) {
        Block block = find(lines, selector(tokens));
        int end = block.end;
        if (end < lines.size() && lines.get(end).isBlank()) end++;
        lines.subList(block.start, end).clear();
        return lines;
    }

    private static List<String> addStep(Path project, List<String> lines, List<String> tokens) {
        Block block = find(lines, selector(tokens));
        Indent indent = indent(lines);
        List<String> added = stepLines(body(project, tokens), option(tokens, "--keyword="), indent.step);
        if (added.isEmpty()) throw new IllegalArgumentException("step-add needs a step");
        int at = examplesAt(lines, block);
        lines.addAll(at, added);
        return lines;
    }

    private static List<String> modifyStep(Path project, List<String> lines, List<String> tokens) {
        Block block = find(lines, selector(tokens));
        Span span = step(lines, block, option(tokens, "--step="));
        if (option(tokens, "--text=") == null && option(tokens, "--file=") == null) {
            throw new IllegalArgumentException("step-modify needs --text or --file");
        }
        Indent indent = indent(lines);
        String keyword = option(tokens, "--keyword=");
        if (keyword == null || keyword.isBlank()) keyword = keywordOf(lines.get(span.start));
        List<String> comments = comments(lines, span.start, span.end);
        List<String> replacement = new ArrayList<>();
        replacement.addAll(comments);
        replacement.addAll(stepLines(body(project, tokens), keyword, indent.step));
        if (replacement.isEmpty()) throw new IllegalArgumentException("step-modify needs --text or --file");
        lines.subList(span.start, span.end).clear();
        lines.addAll(span.start, replacement);
        return lines;
    }

    private static List<String> deleteStep(List<String> lines, List<String> tokens) {
        Block block = find(lines, selector(tokens));
        Span span = step(lines, block, option(tokens, "--step="));
        List<String> comments = comments(lines, span.start, span.end);
        lines.subList(span.start, span.end).clear();
        lines.addAll(span.start, comments);
        return lines;
    }

    private static String currentText(Path project, String feature) throws Exception {
        if (DaltonConfig.enabled(project) && DaltonQuery.databasePresent(project)) {
            try {
                DaltonQuery.FileDetail detail = DaltonQuery.file(project, feature);
                if (detail != null && detail.text() != null) return detail.text();
            } catch (IllegalArgumentException missing) {
                // The row is absent. A file, or a new scenario, can still supply the text.
            }
        }
        Path disk = project.resolve(feature);
        if (Files.isRegularFile(disk)) return Files.readString(disk, StandardCharsets.UTF_8);
        return null;
    }

    private static List<String> split(String text) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        String[] raw = text.split("\\R", -1);
        List<String> lines = new ArrayList<>();
        int end = raw.length;
        if (end > 0 && raw[end - 1].isEmpty()) end--;
        for (int index = 0; index < end; index++) lines.add(raw[index]);
        return lines;
    }

    private static String join(List<String> lines, String newline, boolean trailing) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) out.append(newline);
            out.append(lines.get(index));
        }
        if (trailing && (out.length() > 0 || lines.isEmpty())) out.append(newline);
        return out.toString();
    }

    private static String stem(String feature) {
        String name = feature.substring(feature.lastIndexOf('/') + 1);
        if (name.toLowerCase(Locale.ROOT).endsWith(".feature")) name = name.substring(0, name.length() - 8);
        return name.isBlank() ? "Feature" : name;
    }

    private static Block find(List<String> lines, String selector) {
        if (selector == null || selector.isBlank()) throw new IllegalArgumentException("scenario is required");
        List<Block> blocks = blocks(lines);
        List<Block> scenarios = new ArrayList<>();
        for (Block block : blocks) {
            String keyword = keywordOfHeader(lines.get(block.header));
            if (!"Background".equals(keyword) && !"Rule".equals(keyword)) scenarios.add(block);
        }
        if (selector.chars().allMatch(Character::isDigit)) {
            int ord = Integer.parseInt(selector);
            if (ord < 1 || ord > scenarios.size()) throw new IllegalArgumentException("scenario not found: " + selector);
            return scenarios.get(ord - 1);
        }
        for (Block block : scenarios) {
            if (selector.equals(headerName(lines.get(block.header)))) return block;
        }
        throw new IllegalArgumentException("scenario not found: " + selector);
    }

    private static String selector(List<String> tokens) {
        String scenario = option(tokens, "--scenario=");
        if (scenario != null && !scenario.isBlank()) return scenario.trim();
        String name = option(tokens, "--name=");
        if (name != null && !name.isBlank()) return name.trim();
        return null;
    }

    private static List<Block> blocks(List<String> lines) {
        List<Block> found = new ArrayList<>();
        int index = 0;
        while (index < lines.size()) {
            if (!startsBlock(lines, index)) {
                index++;
                continue;
            }
            int header = headerAt(lines, index);
            int start = header;
            while (start > 0 && isTag(lines.get(start - 1).trim())) start--;
            int end = header + 1;
            while (end < lines.size() && !startsBlock(lines, end)) end++;
            found.add(new Block(start, header, end));
            index = end;
        }
        return found;
    }

    private static boolean startsBlock(List<String> lines, int index) {
        String trimmed = lines.get(index).trim();
        if (scenarioHeader(trimmed) != null) return true;
        if (!isTag(trimmed)) return false;
        for (int next = index + 1; next < lines.size(); next++) {
            String candidate = lines.get(next).trim();
            if (candidate.isEmpty() || candidate.startsWith("#") || isTag(candidate)) continue;
            return scenarioHeader(candidate) != null;
        }
        return false;
    }

    private static int headerAt(List<String> lines, int index) {
        for (int next = index; next < lines.size(); next++) {
            if (scenarioHeader(lines.get(next).trim()) != null) return next;
        }
        return index;
    }

    private static Span step(List<String> lines, Block block, String ordText) {
        if (ordText == null || ordText.isBlank() || !ordText.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("step needs a numeric --step");
        }
        int ord = Integer.parseInt(ordText);
        List<Span> steps = steps(lines, block);
        if (ord < 1 || ord > steps.size()) throw new IllegalArgumentException("step not found: " + ordText);
        return steps.get(ord - 1);
    }

    private static List<Span> steps(List<String> lines, Block block) {
        List<Span> found = new ArrayList<>();
        int index = block.header + 1;
        int stop = examplesAt(lines, block);
        while (index < stop) {
            if (!isStep(lines.get(index).trim())) {
                index++;
                continue;
            }
            int end = index + 1;
            end = consumeDoc(lines, end, stop);
            while (end < stop && (lines.get(end).trim().startsWith("|") || lines.get(end).isBlank() && end + 1 < stop && lines.get(end + 1).trim().startsWith("|"))) {
                end++;
            }
            found.add(new Span(index, end));
            index = end;
        }
        return found;
    }

    private static int examplesAt(List<String> lines, Block block) {
        for (int index = block.header + 1; index < block.end; index++) {
            String trimmed = lines.get(index).trim();
            if (trimmed.startsWith("Examples:") || trimmed.startsWith("Scenarios:")) return index;
        }
        return block.end;
    }

    private static int consumeDoc(List<String> lines, int index, int stop) {
        int cursor = index;
        while (cursor < stop && lines.get(cursor).isBlank()) cursor++;
        if (cursor >= stop) return index;
        String trimmed = lines.get(cursor).trim();
        String fence = trimmed.startsWith("\"\"\"") ? "\"\"\"" : trimmed.startsWith("```") ? "```" : null;
        if (fence == null) return index;
        cursor++;
        while (cursor < stop && !lines.get(cursor).trim().startsWith(fence)) cursor++;
        return cursor < stop ? cursor + 1 : stop;
    }

    private static List<String> comments(List<String> lines, int from, int to) {
        Indent indent = indent(lines);
        List<String> kept = new ArrayList<>();
        for (int index = from; index < to; index++) {
            String trimmed = lines.get(index).trim();
            if (trimmed.startsWith("#")) kept.add(indent.step + trimmed);
        }
        return kept;
    }

    private static List<String> stepLines(String raw, String keyword, String stepIndent) {
        if (raw == null || raw.isBlank()) return List.of();
        if (raw.lines().anyMatch(line -> line.trim().startsWith("Feature:"))) {
            throw new IllegalArgumentException("scenario body must not include a Feature header; use dalton feature put for the whole file");
        }
        String chosen = keyword == null || keyword.isBlank() ? "Given" : keyword.trim();
        if (!isKeyword(chosen)) throw new IllegalArgumentException("unknown step keyword: " + chosen);
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\R", -1)) {
            if (line.isEmpty() && out.isEmpty()) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                out.add("");
                continue;
            }
            if (trimmed.startsWith("Feature:")) {
                throw new IllegalArgumentException("scenario body must not include a Feature header; use dalton feature put for the whole file");
            }
            if (scenarioHeader(trimmed) != null) {
                throw new IllegalArgumentException("scenario body must be steps; use --name for the scenario name");
            }
            if (trimmed.startsWith("#") || trimmed.startsWith("|") || trimmed.startsWith("\"\"\"") || trimmed.startsWith("```")
                    || trimmed.startsWith("Examples:") || trimmed.startsWith("Scenarios:") || isStep(trimmed)) {
                out.add(stepIndent + trimmed);
            } else {
                out.add(stepIndent + chosen + " " + trimmed);
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    private static String body(Path project, List<String> tokens) {
        String text = option(tokens, "--text=");
        String file = option(tokens, "--file=");
        if (text != null && file != null) throw new IllegalArgumentException("edit accepts --text or --file, not both");
        if (file != null) {
            try {
                Path path = Path.of(file);
                if (!path.isAbsolute()) path = project.resolve(file);
                if (!Files.isRegularFile(path)) throw new IllegalArgumentException("edit file not found: " + file);
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalArgumentException("edit file not found: " + file);
            }
        }
        return text;
    }

    private static Indent indent(List<String> lines) {
        for (String line : lines) {
            String header = scenarioHeader(line.trim());
            if (header == null || header.startsWith("Background") || header.startsWith("Rule")) continue;
            int count = 0;
            while (count < line.length() && line.charAt(count) == ' ') count++;
            if (count == 0) count = 2;
            return new Indent(" ".repeat(count), " ".repeat(count + 2));
        }
        return new Indent("  ", "    ");
    }

    private static String renameHeader(String line, String name) {
        String trimmed = line.trim();
        String header = scenarioHeader(trimmed);
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
        return " ".repeat(spaces) + header + " " + name;
    }

    private static String keywordOfHeader(String line) {
        String header = scenarioHeader(line.trim());
        if (header == null) return "";
        return header.substring(0, header.length() - 1);
    }

    private static String headerName(String line) {
        String trimmed = line.trim();
        String header = scenarioHeader(trimmed);
        if (header == null) return "";
        return trimmed.substring(header.length()).trim();
    }

    private static String keywordOf(String line) {
        String trimmed = line.trim();
        for (String keyword : STEP_KEYWORDS) {
            if (trimmed.equals(keyword) || trimmed.startsWith(keyword + " ")) return keyword;
        }
        return "Given";
    }

    private static String scenarioHeader(String trimmed) {
        for (String header : SCENARIO_HEADERS) {
            if (trimmed.equals(header) || trimmed.startsWith(header + " ") || trimmed.startsWith(header)) {
                if (trimmed.startsWith(header)) return header;
            }
        }
        return null;
    }

    private static boolean isStep(String trimmed) {
        for (String keyword : STEP_KEYWORDS) {
            if (trimmed.equals(keyword) || trimmed.startsWith(keyword + " ")) return true;
        }
        return false;
    }

    private static boolean isKeyword(String keyword) {
        for (String candidate : STEP_KEYWORDS) {
            if (candidate.equals(keyword)) return true;
        }
        return false;
    }

    private static boolean isTag(String trimmed) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != '@') return false;
        for (String part : trimmed.split("\\s+")) {
            if (!part.startsWith("@") || part.length() < 2) return false;
        }
        return true;
    }

    private static String option(List<String> tokens, String prefix) {
        for (String token : tokens) {
            if (token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    private record Block(int start, int header, int end) {
    }

    private record Span(int start, int end) {
    }

    private record Indent(String scenario, String step) {
    }
}
