package tools.dscode.dalton;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * English Gherkin query model. The original file bytes stay in {@code files.raw_content}
 * and are what execution parses. This structure is for lookup only.
 * {@code Example} is stored as {@code Scenario}, {@code Scenario Template} as
 * {@code Scenario Outline}, and {@code Scenarios} as {@code Examples}.
 * An Examples step keeps its tags in {@code text} ahead of the name.
 */
public final class DaltonGherkin {
    private static final Pattern STEP = Pattern.compile("^(Given|When|Then|And|But|\\*)(?:\\s+(.*))?$");
    private static final Pattern LANGUAGE = Pattern.compile("^#\\s*language\\s*:\\s*(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final String[] HEADERS = {
            "Scenario Outline",
            "Scenario Template",
            "Examples",
            "Scenarios",
            "Scenario",
            "Example",
            "Background",
            "Feature",
            "Rule"
    };

    private DaltonGherkin() {
    }

    public record Document(
            String keyword,
            String name,
            String language,
            String tags,
            String description,
            List<Scenario> scenarios
    ) {
    }

    public record Scenario(
            int ord,
            String keyword,
            String name,
            int line,
            String tags,
            String description,
            List<Step> steps
    ) {
    }

    public record Step(
            int ord,
            String keyword,
            String text,
            int line,
            String docstring,
            String docstringType,
            String tableJson
    ) {
    }

    public static boolean isGherkin(String path, String sourceType) {
        if (path == null || sourceType == null) return false;
        if (!path.toLowerCase(Locale.ROOT).endsWith(".feature")) return false;
        return "feature".equals(sourceType) || "call".equals(sourceType) || "component".equals(sourceType);
    }

    public static Document parse(byte[] raw) {
        if (raw == null) return null;
        return parse(new String(raw, StandardCharsets.UTF_8));
    }

    public static Document parse(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\r\\n|\\n|\\r", -1);
        String language = "en";
        boolean languageSeen = false;
        List<String> pendingTags = new ArrayList<>();
        String featureKeyword = null;
        String featureName = "";
        String featureTags = "";
        List<String> featureDescription = new ArrayList<>();
        List<ScenarioBuilder> scenarios = new ArrayList<>();
        ScenarioBuilder current = null;
        StepBuilder step = null;
        boolean describingFeature = false;
        boolean describingScenario = false;
        boolean inDoc = false;
        String fence = null;
        int fenceIndent = 0;
        String docType = null;
        List<String> docLines = new ArrayList<>();

        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int lineNo = index + 1;
            if (inDoc) {
                if (closes(line, fence)) {
                    if (step != null) {
                        step.docstring = joinDoc(docLines);
                        step.docstringType = docType == null || docType.isBlank() ? null : docType;
                    }
                    inDoc = false;
                    fence = null;
                    docType = null;
                    docLines.clear();
                } else {
                    docLines.add(stripIndent(line, fenceIndent));
                }
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (describingFeature) featureDescription.add("");
                if (describingScenario && current != null) current.description.add("");
                continue;
            }
            if (trimmed.startsWith("#")) {
                if (!languageSeen) {
                    Matcher languageMatcher = LANGUAGE.matcher(trimmed);
                    if (languageMatcher.matches()) {
                        language = languageMatcher.group(1);
                        languageSeen = true;
                    }
                }
                continue;
            }
            languageSeen = true;
            if (isTags(trimmed)) {
                describingFeature = false;
                describingScenario = false;
                trimTrailingBlank(featureDescription);
                if (current != null) trimTrailingBlank(current.description);
                pendingTags.add(trimmed);
                continue;
            }
            Header header = header(trimmed);
            if (header != null) {
                describingFeature = false;
                describingScenario = false;
                trimTrailingBlank(featureDescription);
                if (current != null) trimTrailingBlank(current.description);
                String tags = String.join(" ", pendingTags);
                pendingTags.clear();
                if ("Feature".equals(header.keyword())) {
                    featureKeyword = "Feature";
                    featureName = header.name();
                    featureTags = tags;
                    describingFeature = true;
                    current = null;
                    step = null;
                    continue;
                }
                if ("Examples".equals(header.keyword())) {
                    if (current == null) continue;
                    step = current.addStep("Examples", examplesText(tags, header.name()), lineNo);
                    continue;
                }
                current = new ScenarioBuilder(scenarios.size() + 1, header.keyword(), header.name(), lineNo, tags);
                scenarios.add(current);
                step = null;
                describingScenario = true;
                continue;
            }
            DocStart docStart = docStart(line);
            if (docStart != null) {
                describingFeature = false;
                describingScenario = false;
                inDoc = true;
                fence = docStart.fence();
                fenceIndent = docStart.indent();
                docType = docStart.type();
                docLines.clear();
                continue;
            }
            if (trimmed.startsWith("|")) {
                describingFeature = false;
                describingScenario = false;
                if (step != null) step.table.add(cells(trimmed));
                continue;
            }
            Matcher stepMatcher = STEP.matcher(trimmed);
            if (stepMatcher.matches()) {
                describingFeature = false;
                describingScenario = false;
                trimTrailingBlank(featureDescription);
                if (current != null) trimTrailingBlank(current.description);
                if (current == null) continue;
                String body = stepMatcher.group(2);
                step = current.addStep(stepMatcher.group(1), body == null ? "" : body, lineNo);
                continue;
            }
            if (describingFeature) featureDescription.add(trimmed);
            else if (describingScenario && current != null) current.description.add(trimmed);
        }
        if (featureKeyword == null) return null;
        trimTrailingBlank(featureDescription);
        List<Scenario> built = new ArrayList<>();
        for (ScenarioBuilder scenario : scenarios) {
            trimTrailingBlank(scenario.description);
            built.add(scenario.build());
        }
        return new Document(
                featureKeyword,
                featureName,
                language,
                featureTags,
                String.join("\n", featureDescription),
                List.copyOf(built)
        );
    }

    public static void store(Connection connection, java.nio.file.Path project, String path, String sourceType, byte[] raw)
            throws SQLException {
        delete(connection, path);
        if (!isGherkin(path, sourceType)) return;
        Document document = parse(raw);
        if (document == null) return;
        try (PreparedStatement feature = connection.prepareStatement("""
                INSERT INTO feature_docs(file_path, keyword, name, language, tags, description)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            feature.setString(1, path);
            feature.setString(2, document.keyword());
            feature.setString(3, document.name());
            feature.setString(4, document.language());
            feature.setString(5, document.tags());
            feature.setString(6, document.description());
            feature.executeUpdate();
        }
        try (PreparedStatement scenario = connection.prepareStatement("""
                INSERT INTO scenarios(file_path, ord, keyword, name, line, tags, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement step = connection.prepareStatement("""
                INSERT INTO steps(file_path, scenario_ord, ord, keyword, text, line, docstring, docstring_type, table_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (Scenario one : document.scenarios()) {
                scenario.setString(1, path);
                scenario.setInt(2, one.ord());
                scenario.setString(3, one.keyword());
                scenario.setString(4, one.name());
                scenario.setInt(5, one.line());
                scenario.setString(6, one.tags());
                scenario.setString(7, one.description());
                scenario.executeUpdate();
                for (Step oneStep : one.steps()) {
                    step.setString(1, path);
                    step.setInt(2, one.ord());
                    step.setInt(3, oneStep.ord());
                    step.setString(4, oneStep.keyword());
                    step.setString(5, oneStep.text());
                    step.setInt(6, oneStep.line());
                    step.setString(7, oneStep.docstring());
                    step.setString(8, oneStep.docstringType());
                    step.setString(9, oneStep.tableJson());
                    step.executeUpdate();
                }
            }
        }
        DaltonLog.log(project, "parse", path, "scenarios=" + document.scenarios().size());
    }

    /** Parses only when a previous hydrate stored the file before the query model existed. */
    public static void storeIfMissing(Connection connection, java.nio.file.Path project, String path, String sourceType, byte[] raw)
            throws SQLException {
        if (!isGherkin(path, sourceType)) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM feature_docs WHERE file_path = ?")) {
            statement.setString(1, path);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) return;
            }
        }
        store(connection, project, path, sourceType, raw);
    }

    private static void delete(Connection connection, String path) throws SQLException {
        for (String sql : List.of(
                "DELETE FROM steps WHERE file_path = ?",
                "DELETE FROM scenarios WHERE file_path = ?",
                "DELETE FROM feature_docs WHERE file_path = ?"
        )) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, path);
                statement.executeUpdate();
            }
        }
    }

    private static String examplesText(String tags, String name) {
        if (tags == null || tags.isBlank()) return name == null ? "" : name;
        if (name == null || name.isBlank()) return tags;
        return tags + "\n" + name;
    }

    private static boolean isTags(String trimmed) {
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return false;
        for (String part : parts) {
            if (!part.startsWith("@") || part.length() < 2) return false;
        }
        return true;
    }

    private static Header header(String trimmed) {
        for (String keyword : HEADERS) {
            String prefix = keyword + ":";
            if (!trimmed.equals(prefix) && !trimmed.startsWith(prefix + " ") && !trimmed.startsWith(prefix + "\t")) {
                if (!trimmed.startsWith(prefix)) continue;
            }
            if (trimmed.equals(prefix) || trimmed.startsWith(prefix)) {
                String name = trimmed.substring(prefix.length()).trim();
                return new Header(canonical(keyword), name);
            }
        }
        return null;
    }

    private static String canonical(String keyword) {
        return switch (keyword) {
            case "Example" -> "Scenario";
            case "Scenario Template" -> "Scenario Outline";
            case "Scenarios" -> "Examples";
            default -> keyword;
        };
    }

    private static DocStart docStart(String line) {
        int indent = 0;
        while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) indent++;
        String trimmed = line.substring(indent);
        String fence = null;
        if (trimmed.startsWith("\"\"\"")) fence = "\"\"\"";
        else if (trimmed.startsWith("```")) fence = "```";
        if (fence == null) return null;
        String type = trimmed.substring(fence.length()).trim();
        if (type.indexOf(' ') >= 0) return null;
        return new DocStart(fence, type, indent);
    }

    private static boolean closes(String line, String fence) {
        return line.trim().equals(fence);
    }

    private static String stripIndent(String line, int indent) {
        int kept = 0;
        while (kept < indent && kept < line.length()) {
            char c = line.charAt(kept);
            if (c != ' ' && c != '\t') break;
            kept++;
        }
        return line.substring(kept);
    }

    private static String joinDoc(List<String> lines) {
        return String.join("\n", lines);
    }

    private static void trimTrailingBlank(List<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
    }

    static List<String> cells(String trimmed) {
        String body = trimmed;
        if (body.startsWith("|")) body = body.substring(1);
        if (body.endsWith("|") && body.length() > 0) body = body.substring(0, body.length() - 1);
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char next = body.charAt(i + 1);
                if (next == '|' || next == '\\' || next == 'n') {
                    current.append(next == 'n' ? '\n' : next);
                    i++;
                    continue;
                }
            }
            if (c == '|') {
                cells.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        cells.add(current.toString().trim());
        return cells;
    }

    static String tableJson(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return null;
        StringBuilder json = new StringBuilder();
        json.append('[');
        for (int row = 0; row < rows.size(); row++) {
            if (row > 0) json.append(',');
            json.append('[');
            List<String> cells = rows.get(row);
            for (int column = 0; column < cells.size(); column++) {
                if (column > 0) json.append(',');
                json.append(jsonString(cells.get(column)));
            }
            json.append(']');
        }
        json.append(']');
        return json.toString();
    }

    private static String jsonString(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2);
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
                    else json.append(c);
                }
            }
        }
        json.append('"');
        return json.toString();
    }

    private record Header(String keyword, String name) {
    }

    private record DocStart(String fence, String type, int indent) {
    }

    private static final class ScenarioBuilder {
        final int ord;
        final String keyword;
        final String name;
        final int line;
        final String tags;
        final List<String> description = new ArrayList<>();
        final List<StepBuilder> steps = new ArrayList<>();

        ScenarioBuilder(int ord, String keyword, String name, int line, String tags) {
            this.ord = ord;
            this.keyword = keyword;
            this.name = name;
            this.line = line;
            this.tags = tags == null ? "" : tags;
        }

        StepBuilder addStep(String keyword, String text, int line) {
            StepBuilder step = new StepBuilder(steps.size() + 1, keyword, text, line);
            steps.add(step);
            return step;
        }

        Scenario build() {
            List<Step> built = new ArrayList<>();
            for (StepBuilder step : steps) built.add(step.build());
            return new Scenario(ord, keyword, name, line, tags, String.join("\n", description), List.copyOf(built));
        }
    }

    private static final class StepBuilder {
        final int ord;
        final String keyword;
        final String text;
        final int line;
        String docstring;
        String docstringType;
        final List<List<String>> table = new ArrayList<>();

        StepBuilder(int ord, String keyword, String text, int line) {
            this.ord = ord;
            this.keyword = keyword;
            this.text = text;
            this.line = line;
        }

        Step build() {
            return new Step(ord, keyword, text, line, docstring, docstringType, tableJson(table));
        }
    }
}
