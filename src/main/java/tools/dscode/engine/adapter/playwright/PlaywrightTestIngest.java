package tools.dscode.engine.adapter.playwright;

import tools.dscode.engine.EngineLog;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.SourceLocation;
import tools.dscode.store.StoreDatabase;
import tools.dscode.store.StoreHashes;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreStepCatalog;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads stored {@code *.spec.ts} and {@code *.spec.js} bytes into scenario rows.
 * The file bytes stay on {@code files}. This class does not launch a browser.
 */
public final class PlaywrightTestIngest {
    public static final String KEYWORD = "Playwright";
    public static final String HOST = "playwright";

    private PlaywrightTestIngest() {
    }

    public static boolean isSpec(String path) {
        if (path == null) return false;
        String name = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".spec.ts") || name.endsWith(".spec.js");
    }

    /** After the Java and JavaScript catalog refresh, and after Cucumber translation. */
    public static void apply(Connection connection, java.nio.file.Path project) throws SQLException {
        List<SpecFile> specs = specFiles(connection);
        java.util.Set<String> current = new java.util.LinkedHashSet<>();
        for (SpecFile spec : specs) {
            current.add(spec.path());
            deletePath(connection, spec.path());
            write(connection, project, spec);
        }
        List<String> stale = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DISTINCT file_path FROM scenarios WHERE keyword = ?")) {
            statement.setString(1, KEYWORD);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String path = rows.getString(1);
                    if (path == null || current.contains(path) || path.endsWith(".feature")) continue;
                    stale.add(path);
                }
            }
        }
        for (String path : stale) deletePath(connection, path);
    }

    public static List<String> specPaths(java.nio.file.Path project) throws SQLException {
        if (project == null || !StorePaths.ready(project)) return List.of();
        try (StoreDatabase database = StoreDatabase.open(project)) {
            List<String> paths = new ArrayList<>();
            for (SpecFile spec : specFiles(database.connection())) paths.add(spec.path());
            return paths;
        }
    }

    /** {@code null} when that path is not a stored spec file. */
    public static EngineFeature loadFeature(java.nio.file.Path project, String path) throws SQLException {
        String stored = StorePaths.normalizeRelative(path);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            int enabled = -1;
            try (PreparedStatement file = database.connection().prepareStatement(
                    "SELECT enabled FROM files WHERE path = ?")) {
                file.setString(1, stored);
                try (ResultSet rows = file.executeQuery()) {
                    if (rows.next()) enabled = rows.getInt(1);
                }
            }
            if (enabled < 0) return null;
            if (enabled == 0) {
                return new EngineFeature(stored, "", "en", List.of(), List.of(), List.of(),
                        SourceLocation.of("store:" + stored, 1, 0L));
            }
            List<EngineScenario> scenarios = new ArrayList<>();
            try (PreparedStatement statement = database.connection().prepareStatement("""
                    SELECT ord, name, line, description, enabled
                    FROM scenarios
                    WHERE file_path = ? AND keyword = ?
                    ORDER BY ord
                    """)) {
                statement.setString(1, stored);
                statement.setString(2, KEYWORD);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (rows.getInt(5) == 0) continue;
                        int ord = rows.getInt(1);
                        String name = rows.getString(2) == null ? "" : rows.getString(2);
                        int line = rows.getInt(3);
                        String description = rows.getString(4) == null ? "" : rows.getString(4);
                        String definition = StoreStepCatalog.definitionId(HOST, name, stored, line);
                        scenarios.add(new EngineScenario(
                                KEYWORD,
                                name,
                                description,
                                List.of(),
                                steps(database.connection(), stored, ord, definition),
                                List.of(),
                                SourceLocation.of("store:" + stored, line, 0L),
                                java.util.Map.of()));
                    }
                }
            }
            return new EngineFeature(stored, "", "en", List.of(), List.of(), scenarios,
                    SourceLocation.of("store:" + stored, 1, 0L));
        }
    }

    private static List<EngineStep> steps(Connection connection, String path, int ord, String definition) throws SQLException {
        List<EngineStep> steps = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT keyword, text, line, enabled
                FROM steps
                WHERE file_path = ? AND scenario_ord = ?
                ORDER BY ord
                """)) {
            statement.setString(1, path);
            statement.setInt(2, ord);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getInt(4) == 0) continue;
                    EngineStep step = EngineStep.of(rows.getString(1), rows.getString(2) == null ? "" : rows.getString(2));
                    steps.add(step.withMetadata("definition", definition));
                }
            }
        }
        return steps;
    }

    private static void write(Connection connection, java.nio.file.Path project, SpecFile spec) throws SQLException {
        Parsed parsed = parse(spec.text());
        String now = Instant.now().toString();
        try (PreparedStatement scenario = connection.prepareStatement("""
                INSERT INTO scenarios(file_path, ord, keyword, name, line, tags, description, enabled)
                VALUES (?, ?, ?, ?, ?, '', ?, ?)
                """);
             PreparedStatement step = connection.prepareStatement("""
                INSERT INTO steps(file_path, scenario_ord, ord, keyword, text, line, docstring, docstring_type, table_json)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL)
                """);
             PreparedStatement definition = connection.prepareStatement("""
                INSERT INTO step_definitions(
                    definition_id, language, pattern, method_name, source_path, source_line,
                    content_hash, hydrated_at, removed, enabled, parameter_types, host)
                VALUES (?, ?, ?, 'test', ?, ?, ?, ?, 0, ?, '[]', ?)
                """);
             PreparedStatement fixture = connection.prepareStatement("""
                INSERT OR REPLACE INTO fixtures(name, kind, deps, source, file_path)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            int ord = 1;
            for (TestDraft test : parsed.tests()) {
                scenario.setString(1, spec.path());
                scenario.setInt(2, ord);
                scenario.setString(3, KEYWORD);
                scenario.setString(4, test.name());
                scenario.setInt(5, test.line());
                scenario.setString(6, test.describe());
                scenario.setInt(7, spec.enabled());
                scenario.executeUpdate();
                int stepOrd = 1;
                StringBuilder body = new StringBuilder();
                for (int index = 0; index < test.lines().size(); index++) {
                    Line line = test.lines().get(index);
                    step.setString(1, spec.path());
                    step.setInt(2, ord);
                    step.setInt(3, stepOrd++);
                    step.setString(4, test.keywords().get(index));
                    step.setString(5, line.text());
                    step.setInt(6, line.line());
                    step.executeUpdate();
                    if (!body.isEmpty()) body.append('\n');
                    body.append(line.text());
                }
                String id = StoreStepCatalog.definitionId(HOST, test.name(), spec.path(), test.line());
                definition.setString(1, id);
                definition.setString(2, HOST);
                definition.setString(3, test.name());
                definition.setString(4, spec.path());
                definition.setInt(5, test.line());
                definition.setString(6, StoreHashes.sha256((test.name() + "\n" + body).getBytes(StandardCharsets.UTF_8)));
                definition.setString(7, now);
                definition.setInt(8, spec.enabled());
                definition.setString(9, HOST);
                definition.executeUpdate();
                ord++;
            }
            for (Fixture row : parsed.fixtures()) {
                fixture.setString(1, row.name());
                fixture.setString(2, row.kind());
                fixture.setString(3, row.deps());
                fixture.setString(4, row.source());
                fixture.setString(5, spec.path());
                fixture.executeUpdate();
            }
            for (String builtin : List.of("page", "context", "browser")) {
                fixture.setString(1, builtin);
                fixture.setString(2, "builtin");
                fixture.setString(3, "");
                fixture.setString(4, "");
                fixture.setString(5, spec.path());
                fixture.executeUpdate();
            }
        }
        EngineLog.log(project, "ingest", spec.path(), "playwright spec " + spec.path() + " tests=" + parsed.tests().size());
    }

    private static List<SpecFile> specFiles(Connection connection) throws SQLException {
        List<SpecFile> found = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT path, raw_content, enabled
                FROM files
                WHERE lower(path) LIKE '%.spec.ts' OR lower(path) LIKE '%.spec.js'
                ORDER BY path
                """);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String path = rows.getString(1);
                byte[] raw = rows.getBytes(2);
                String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
                found.add(new SpecFile(path, text, rows.getInt(3) == 0 ? 0 : 1));
            }
        }
        return found;
    }

    private static void deletePath(Connection connection, String path) throws SQLException {
        for (String sql : List.of(
                "DELETE FROM steps WHERE file_path = ?",
                "DELETE FROM example_blocks WHERE file_path = ?",
                "DELETE FROM scenario_tags WHERE file_path = ?",
                "DELETE FROM scenarios WHERE file_path = ?",
                "DELETE FROM fixtures WHERE file_path = ?",
                "DELETE FROM step_definitions WHERE source_path = ? AND host = 'playwright'")) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, path);
                statement.executeUpdate();
            }
        }
    }

    static Parsed parse(String source) {
        String text = source == null ? "" : source;
        List<TestDraft> tests = new ArrayList<>();
        scan(text, 0, text.length(), "", List.of(), tests);
        return new Parsed(tests, fixtures(text));
    }

    private static void scan(
            String source,
            int from,
            int to,
            String describe,
            List<Hook> outer,
            List<TestDraft> tests
    ) {
        List<Call> calls = collect(source, from, to);
        List<Hook> local = new ArrayList<>();
        for (Call call : calls) {
            if (call.kind().startsWith("before") || call.kind().startsWith("after")) {
                local.add(new Hook(call.kind(), call.lines()));
            }
        }
        List<Hook> around = new ArrayList<>(outer);
        around.addAll(local);
        for (Call call : calls) {
            if ("describe".equals(call.kind())) {
                String name = describe.isEmpty() ? call.title() : describe + " / " + call.title();
                scan(source, call.bodyFrom(), call.bodyTo(), name, around, tests);
            } else if ("test".equals(call.kind())) {
                List<Line> lines = new ArrayList<>();
                List<String> keywords = new ArrayList<>();
                for (Hook hook : around) {
                    if (!hook.kind().startsWith("before")) continue;
                    for (Line line : hook.lines()) {
                        lines.add(line);
                        keywords.add(hook.kind());
                    }
                }
                for (Line line : call.lines()) {
                    lines.add(line);
                    keywords.add("*");
                }
                for (Hook hook : around) {
                    if (!hook.kind().startsWith("after")) continue;
                    for (Line line : hook.lines()) {
                        lines.add(line);
                        keywords.add(hook.kind());
                    }
                }
                tests.add(new TestDraft(call.title().isEmpty() ? "test" : call.title(), call.line(), describe, lines, keywords));
            }
        }
    }

    private static List<Call> collect(String source, int from, int to) {
        List<Call> calls = new ArrayList<>();
        int index = from;
        while (index < to) {
            int at = findTest(source, index, to);
            if (at < 0) break;
            String kind = classify(source, at, to);
            if (kind == null) {
                index = at + 4;
                continue;
            }
            Call call = readCall(source, at, to, kind);
            if (call == null) {
                index = at + 4;
                continue;
            }
            calls.add(call);
            index = Math.max(call.end(), at + 4);
        }
        return calls;
    }

    private static String classify(String source, int at, int limit) {
        int cursor = skipTrivia(source, at + 4, limit);
        if (cursor >= limit) return null;
        if (source.charAt(cursor) == '.') {
            int name = skipTrivia(source, cursor + 1, limit);
            if (memberAt(source, name, "describe")) return followedByParen(source, name + 8, limit) ? "describe" : null;
            if (memberAt(source, name, "beforeEach")) return followedByParen(source, name + 11, limit) ? "beforeEach" : null;
            if (memberAt(source, name, "afterEach")) return followedByParen(source, name + 10, limit) ? "afterEach" : null;
            if (memberAt(source, name, "beforeAll")) return followedByParen(source, name + 9, limit) ? "beforeAll" : null;
            if (memberAt(source, name, "afterAll")) return followedByParen(source, name + 8, limit) ? "afterAll" : null;
            if (memberAt(source, name, "only")) return followedByParen(source, name + 4, limit) ? "test" : null;
            if (memberAt(source, name, "skip")) return followedByParen(source, name + 4, limit) ? "test" : null;
            return null;
        }
        if (source.charAt(cursor) == '(') return "test";
        return null;
    }

    private static boolean followedByParen(String source, int index, int limit) {
        int cursor = skipTrivia(source, index, limit);
        return cursor < limit && source.charAt(cursor) == '(';
    }

    private static Call readCall(String source, int at, int limit, String kind) {
        int paren = indexOfParen(source, at, limit);
        if (paren < 0) return null;
        String title = "describe".equals(kind) || "test".equals(kind) ? firstString(source, paren + 1, limit) : "";
        int bodyOpen = findBody(source, paren, limit);
        int bodyClose = bodyOpen < 0 ? -1 : matchBrace(source, bodyOpen, limit);
        int end;
        if (bodyClose > bodyOpen) {
            end = endOfCall(source, bodyClose, limit);
        } else {
            end = matchParen(source, paren, limit);
            if (end < limit && source.charAt(end) == ';') end++;
        }
        if (end <= paren) end = Math.min(limit, paren + 1);
        List<Line> lines = bodyClose > bodyOpen ? linesOf(source, bodyOpen, bodyClose - 1) : List.of();
        int bodyFrom = bodyClose > bodyOpen ? bodyOpen + 1 : end;
        int bodyTo = bodyClose > bodyOpen ? bodyClose - 1 : end;
        return new Call(kind, title, lineOf(source, at), bodyFrom, bodyTo, end, lines);
    }

    private static int indexOfParen(String source, int at, int limit) {
        int index = at;
        while (index < limit) {
            char c = source.charAt(index);
            if (c == '(') return index;
            if (c == '\'' || c == '"' || c == '`' || c == '/') {
                int next = advance(source, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            index++;
        }
        return -1;
    }

    private static int findBody(String source, int paren, int limit) {
        int index = paren + 1;
        int depth = 1;
        boolean arrow = false;
        boolean function = false;
        boolean paramsDone = false;
        while (index < limit && depth > 0) {
            char c = source.charAt(index);
            if (c == '\'' || c == '"' || c == '`' || (c == '/' && index + 1 < limit
                    && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*'))) {
                int next = advance(source, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            if (c == '(') {
                depth++;
                index++;
                continue;
            }
            if (c == ')') {
                depth--;
                if (function && depth == 1) paramsDone = true;
                index++;
                continue;
            }
            if (!arrow && !function && wordAt(source, index, "function")) {
                function = true;
                index += 8;
                continue;
            }
            if (!function && c == '=' && index + 1 < limit && source.charAt(index + 1) == '>') {
                arrow = true;
                index += 2;
                continue;
            }
            if (c == '{' && depth == 1 && (arrow || (function && paramsDone))) return index;
            index++;
        }
        return -1;
    }

    private static List<Line> linesOf(String source, int open, int close) {
        List<Line> lines = new ArrayList<>();
        int start = open + 1;
        for (int index = start; index <= close; index++) {
            boolean boundary = index == close || source.charAt(index) == '\n';
            if (!boundary) continue;
            String raw = source.substring(start, index);
            String trimmed = raw.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !braceOnly(trimmed)) {
                lines.add(new Line(lineOf(source, start), trimmed));
            }
            start = index + 1;
        }
        return lines;
    }

    private static boolean braceOnly(String trimmed) {
        for (int index = 0; index < trimmed.length(); index++) {
            char c = trimmed.charAt(index);
            if (c != '{' && c != '}' && c != '(' && c != ')' && c != ';' && c != ',' && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static List<Fixture> fixtures(String source) {
        List<Fixture> found = new ArrayList<>();
        int index = 0;
        int limit = source.length();
        while (index < limit) {
            char c = source.charAt(index);
            if (c == '\'' || c == '"' || c == '`' || (c == '/' && index + 1 < limit
                    && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*'))) {
                int next = advance(source, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            if (source.startsWith(".extend", index) && (index + 7 >= limit || !isIdent(source.charAt(index + 7)))) {
                int paren = skipTrivia(source, index + 7, limit);
                if (paren < limit && source.charAt(paren) == '(') {
                    int open = skipTrivia(source, paren + 1, limit);
                    if (open < limit && source.charAt(open) == '{') {
                        parseObject(source, open, found);
                        int close = matchBrace(source, open, limit);
                        index = close > open ? close : open + 1;
                        continue;
                    }
                }
            }
            index++;
        }
        return found;
    }

    private static void parseObject(String source, int open, List<Fixture> found) {
        int close = matchBrace(source, open, source.length());
        int index = open + 1;
        while (index < close) {
            index = skipTrivia(source, index, close);
            if (index >= close || source.charAt(index) == '}') break;
            if (source.charAt(index) == ',') {
                index++;
                continue;
            }
            String name;
            if (source.charAt(index) == '\'' || source.charAt(index) == '"' || source.charAt(index) == '`') {
                int end = endString(source, index, close);
                name = source.substring(index + 1, Math.max(index + 1, end - 1));
                index = end;
            } else if (isIdent(source.charAt(index))) {
                int start = index;
                while (index < close && isIdent(source.charAt(index))) index++;
                name = source.substring(start, index);
            } else {
                index++;
                continue;
            }
            index = skipTrivia(source, index, close);
            if (index >= close || source.charAt(index) != ':') continue;
            int valueStart = skipTrivia(source, index + 1, close);
            int valueEnd = valueEnd(source, valueStart, close);
            String value = source.substring(valueStart, valueEnd).trim();
            if (!name.isBlank()) found.add(new Fixture(name, kind(value), deps(value), value));
            index = valueEnd;
        }
    }

    private static int valueEnd(String source, int index, int limit) {
        int depth = 0;
        while (index < limit) {
            char c = source.charAt(index);
            if (c == '\'' || c == '"' || c == '`' || (c == '/' && index + 1 < limit
                    && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*'))) {
                int next = advance(source, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            if (c == '{' || c == '(' || c == '[') {
                depth++;
                index++;
                continue;
            }
            if (c == '}' || c == ')' || c == ']') {
                if (depth == 0 && c == '}') return index;
                if (depth > 0) depth--;
                index++;
                continue;
            }
            if (c == ',' && depth == 0) return index;
            index++;
        }
        return index;
    }

    private static String kind(String value) {
        if (flag(value, "option")) return "option";
        if (flag(value, "auto")) return "auto";
        return "fixture";
    }

    private static boolean flag(String value, String name) {
        return value != null && value.matches("(?s).*\\b" + name + "\\s*:\\s*true\\b.*");
    }

    private static String deps(String value) {
        int index = 0;
        int limit = value.length();
        while (index < limit) {
            char c = value.charAt(index);
            if (c == '\'' || c == '"' || c == '`' || (c == '/' && index + 1 < limit
                    && (value.charAt(index + 1) == '/' || value.charAt(index + 1) == '*'))) {
                int next = advance(value, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            if (c == '{') {
                int close = matchBrace(value, index, limit);
                String inside = value.substring(index + 1, Math.max(index + 1, close - 1));
                if (!containsColon(inside)) {
                    List<String> names = new ArrayList<>();
                    for (String part : inside.split(",")) {
                        String token = part.trim();
                        if (!token.isEmpty() && isIdent(token.charAt(0))) names.add(token);
                    }
                    return String.join(",", names);
                }
                index = close > index ? close : index + 1;
                continue;
            }
            index++;
        }
        return "";
    }

    private static boolean containsColon(String text) {
        int index = 0;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                int next = endString(text, index, text.length());
                index = next <= index ? index + 1 : next;
                continue;
            }
            if (c == ':') return true;
            index++;
        }
        return false;
    }

    private static int findTest(String source, int index, int limit) {
        while (index < limit) {
            char c = source.charAt(index);
            if (c == '\'' || c == '"' || c == '`' || (c == '/' && index + 1 < limit
                    && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*'))) {
                int next = advance(source, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            if (wordAt(source, index, "test")) return index;
            index++;
        }
        return -1;
    }

    private static boolean wordAt(String source, int index, String word) {
        if (!tokenAt(source, index, word)) return false;
        if (index > 0 && source.charAt(index - 1) == '.') return false;
        return true;
    }

    /** A member after {@code test.}, where the preceding character is a dot. */
    private static boolean memberAt(String source, int index, String word) {
        return tokenAt(source, index, word);
    }

    private static boolean tokenAt(String source, int index, String word) {
        if (index < 0 || index + word.length() > source.length() || !source.startsWith(word, index)) return false;
        if (index > 0 && isIdent(source.charAt(index - 1))) return false;
        int after = index + word.length();
        return after >= source.length() || !isIdent(source.charAt(after));
    }

    private static int skipTrivia(String source, int index, int limit) {
        while (index < limit) {
            char c = source.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '/' && index + 1 < limit && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*')) {
                int next = advance(source, index, limit);
                if (next <= index) return index;
                index = next;
                continue;
            }
            break;
        }
        return index;
    }

    private static String firstString(String source, int index, int limit) {
        while (index < limit) {
            char c = source.charAt(index);
            if (c == '\'' || c == '"' || c == '`') {
                int end = endString(source, index, limit);
                return source.substring(index + 1, Math.max(index + 1, end - 1));
            }
            if (c == '/' && index + 1 < limit && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*')) {
                int next = advance(source, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            index++;
        }
        return "";
    }

    private static int endOfCall(String source, int bodyClose, int limit) {
        int index = bodyClose;
        while (index < limit) {
            char c = source.charAt(index);
            if (Character.isWhitespace(c) || c == ')') {
                index++;
                continue;
            }
            if (c == ';') return index + 1;
            return index;
        }
        return index;
    }

    private static int matchParen(String source, int open, int limit) {
        int depth = 0;
        int index = open;
        while (index < limit) {
            char c = source.charAt(index);
            if (c == '\'' || c == '"' || c == '`' || (c == '/' && index + 1 < limit
                    && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*'))) {
                int next = advance(source, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return index + 1;
            }
            index++;
        }
        return index;
    }

    private static int matchBrace(String source, int open, int limit) {
        int depth = 0;
        int index = open;
        while (index < limit) {
            char c = source.charAt(index);
            if (c == '\'' || c == '"' || c == '`' || (c == '/' && index + 1 < limit
                    && (source.charAt(index + 1) == '/' || source.charAt(index + 1) == '*'))) {
                int next = advance(source, index, limit);
                index = next <= index ? index + 1 : next;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return index + 1;
            }
            index++;
        }
        return index;
    }

    private static int advance(String source, int index, int limit) {
        if (index >= limit) return index;
        char c = source.charAt(index);
        if (c == '/' && index + 1 < limit) {
            char next = source.charAt(index + 1);
            if (next == '/') {
                while (index < limit && source.charAt(index) != '\n') index++;
                return index;
            }
            if (next == '*') {
                index += 2;
                while (index + 1 < limit && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) index++;
                return Math.min(limit, index + 2);
            }
        }
        if (c == '\'' || c == '"' || c == '`') return endString(source, index, limit);
        return index + 1;
    }

    private static int endString(String source, int index, int limit) {
        char quote = source.charAt(index);
        index++;
        while (index < limit) {
            char c = source.charAt(index);
            if (c == '\\') {
                index += 2;
                continue;
            }
            if (quote == '`' && c == '$' && index + 1 < limit && source.charAt(index + 1) == '{') {
                index = matchBrace(source, index + 1, limit);
                continue;
            }
            if (c == quote) return index + 1;
            if (c == '\n' && quote != '`') return index;
            index++;
        }
        return index;
    }

    private static int lineOf(String source, int index) {
        int line = 1;
        int end = Math.min(Math.max(index, 0), source.length());
        for (int cursor = 0; cursor < end; cursor++) {
            if (source.charAt(cursor) == '\n') line++;
        }
        return line;
    }

    private static boolean isIdent(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private record SpecFile(String path, String text, int enabled) {
    }

    private record Line(int line, String text) {
    }

    private record Hook(String kind, List<Line> lines) {
    }

    private record Call(String kind, String title, int line, int bodyFrom, int bodyTo, int end, List<Line> lines) {
    }

    private record Fixture(String name, String kind, String deps, String source) {
    }

    record Parsed(List<TestDraft> tests, List<Fixture> fixtures) {
    }

    record TestDraft(String name, int line, String describe, List<Line> lines, List<String> keywords) {
    }
}
