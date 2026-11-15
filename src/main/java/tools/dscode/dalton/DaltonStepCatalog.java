package tools.dscode.dalton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Step-definition catalog derived from Java glue annotations and from
 * {@code glue/js} source. JS and TS stay source only. There is no JS/TS step runtime.
 * {@code definition_id} is SHA-256 of language, pattern, path, and line.
 */
public final class DaltonStepCatalog {
    private static final int MAX_BYTES = 1_048_576;
    private static final Pattern JAVA_ANNOTATION = Pattern.compile("@(?:Given|When|Then|And|But)\\b");
    private static final Pattern JS_STEP = Pattern.compile(
            "\\b(?:Given|When|Then|And|But|defineStep)\\s*\\(\\s*(['\"`])((?:\\\\.|(?!\\1).)*)\\1");

    private DaltonStepCatalog() {
    }

    public record Definition(
            String id,
            String language,
            String pattern,
            String methodName,
            String sourcePath,
            int sourceLine,
            String contentHash
    ) {
    }

    /** Replaces the catalog from the current glue sources. */
    public static int refresh(Path project) throws SQLException {
        String now = Instant.now().toString();
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            Connection connection = database.connection();
            connection.setAutoCommit(false);
            try {
                int count = refresh(connection, project, now);
                connection.commit();
                return count;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public static int refresh(Connection connection, Path project, String hydratedAt) throws SQLException {
        List<Definition> found = scan(project);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM step_definitions");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO step_definitions(
                    definition_id, language, pattern, method_name, source_path, source_line, content_hash, hydrated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (Definition definition : found) {
                statement.setString(1, definition.id());
                statement.setString(2, definition.language());
                statement.setString(3, definition.pattern());
                statement.setString(4, definition.methodName());
                statement.setString(5, definition.sourcePath());
                statement.setInt(6, definition.sourceLine());
                statement.setString(7, definition.contentHash());
                statement.setString(8, hydratedAt);
                statement.addBatch();
            }
            if (!found.isEmpty()) statement.executeBatch();
        }
        DaltonLog.log(project, "track", "step-definitions", "catalog " + found.size());
        return found.size();
    }

    public static List<Definition> list(Path project) throws SQLException {
        if (!Files.isRegularFile(DaltonPaths.database(project))) return List.of();
        try (DaltonDatabase database = DaltonDatabase.open(project);
             PreparedStatement statement = database.connection().prepareStatement("""
                     SELECT definition_id, language, pattern, method_name, source_path, source_line, content_hash
                     FROM step_definitions ORDER BY source_path, source_line, definition_id
                     """);
             ResultSet rows = statement.executeQuery()) {
            List<Definition> found = new ArrayList<>();
            while (rows.next()) {
                found.add(new Definition(
                        rows.getString(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getString(4),
                        rows.getString(5),
                        rows.getInt(6),
                        rows.getString(7)));
            }
            return found;
        }
    }

    public static String definitionId(String language, String pattern, String sourcePath, int sourceLine) {
        String raw = language + "|" + pattern + "|" + sourcePath + "|" + sourceLine;
        return DaltonHashes.sha256(raw.getBytes(StandardCharsets.UTF_8));
    }

    static List<Definition> scan(Path project) {
        List<Definition> found = new ArrayList<>();
        walk(project, project.resolve("src/main/java"), "java", found);
        walk(project, project.resolve("src/test/java"), "java", found);
        walk(project, project.resolve("glue/js"), "js", found);
        found.sort(Comparator.comparing(Definition::sourcePath).thenComparingInt(Definition::sourceLine).thenComparing(Definition::id));
        return List.copyOf(found);
    }

    private static void walk(Path project, Path root, String language, List<Definition> found) {
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                String kind = language;
                if ("js".equals(language)) {
                    if (name.endsWith(".js")) kind = "js";
                    else if (name.endsWith(".ts")) kind = "ts";
                    else continue;
                } else if (!name.endsWith(".java")) {
                    continue;
                }
                if (Files.size(file) > MAX_BYTES) continue;
                byte[] raw = Files.readAllBytes(file);
                String text = new String(raw, StandardCharsets.UTF_8);
                String path = DaltonPaths.display(project, file);
                String hash = DaltonHashes.sha256(raw);
                List<Hit> hits = "java".equals(kind) ? javaHits(text) : scriptHits(text);
                for (Hit hit : hits) {
                    found.add(new Definition(
                            definitionId(kind, hit.pattern(), path, hit.line()),
                            kind,
                            hit.pattern(),
                            hit.method(),
                            path,
                            hit.line(),
                            hash));
                }
            }
        } catch (IOException ignored) {
            // A catalog scan must not hide a hydrate error from the caller when the caller asked;
            // a missing or unreadable tree contributes nothing.
        }
    }

    private record Hit(int line, String pattern, String method) {
    }

    static List<Hit> javaHits(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) return hits;
        Matcher matcher = JAVA_ANNOTATION.matcher(text);
        while (matcher.find()) {
            if (inComment(text, matcher.start())) continue;
            int open = after(text, matcher.end());
            if (open >= text.length() || text.charAt(open) != '(') continue;
            int close = matching(text, open);
            if (close < 0) continue;
            String pattern = literal(text.substring(open + 1, close));
            if (pattern == null || pattern.isEmpty()) continue;
            hits.add(new Hit(lineAt(text, matcher.start()), pattern, methodName(text, close + 1)));
        }
        return hits;
    }

    static List<Hit> scriptHits(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) return hits;
        Matcher matcher = JS_STEP.matcher(text);
        while (matcher.find()) {
            if (inComment(text, matcher.start())) continue;
            String pattern = unescape(matcher.group(2));
            if (pattern.isEmpty()) continue;
            hits.add(new Hit(lineAt(text, matcher.start()), pattern, null));
        }
        return hits;
    }

    private static boolean inComment(String text, int at) {
        int line = text.lastIndexOf('\n', at) + 1;
        String prefix = text.substring(line, at).trim();
        return prefix.startsWith("//") || prefix.startsWith("*") || prefix.startsWith("/*");
    }

    private static int after(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        return cursor;
    }

    private static int matching(String text, int open) {
        int depth = 0;
        boolean string = false;
        boolean escape = false;
        boolean block = false;
        for (int index = open; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (block) {
                if (ch == '"' && index + 2 < text.length()
                        && text.charAt(index + 1) == '"' && text.charAt(index + 2) == '"') {
                    block = false;
                    index += 2;
                }
                continue;
            }
            if (string) {
                if (escape) escape = false;
                else if (ch == '\\') escape = true;
                else if (ch == '"') string = false;
                continue;
            }
            if (ch == '"') {
                if (index + 2 < text.length() && text.charAt(index + 1) == '"' && text.charAt(index + 2) == '"') {
                    block = true;
                    index += 2;
                } else {
                    string = true;
                }
                continue;
            }
            if (ch == '(') depth++;
            else if (ch == ')') {
                depth--;
                if (depth == 0) return index;
            }
        }
        return -1;
    }

    private static String literal(String inside) {
        String body = inside.trim();
        if (body.startsWith("value")) {
            String rest = body.substring("value".length()).trim();
            if (rest.startsWith("=")) body = rest.substring(1).trim();
        }
        if (body.startsWith("\"\"\"")) {
            int end = body.indexOf("\"\"\"", 3);
            if (end < 0) return null;
            if (!body.substring(end + 3).trim().isEmpty()) return null;
            String raw = body.substring(3, end);
            if (raw.startsWith("\r\n")) raw = raw.substring(2);
            else if (raw.startsWith("\n")) raw = raw.substring(1);
            return raw;
        }
        if (!body.startsWith("\"")) return null;
        boolean escape = false;
        for (int index = 1; index < body.length(); index++) {
            char ch = body.charAt(index);
            if (escape) {
                escape = false;
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (ch == '"') {
                if (!body.substring(index + 1).trim().isEmpty()) return null;
                return unescape(body.substring(1, index));
            }
        }
        return null;
    }

    private static String unescape(String body) {
        StringBuilder out = new StringBuilder(body.length());
        for (int index = 0; index < body.length(); index++) {
            char ch = body.charAt(index);
            if (ch == '\\' && index + 1 < body.length()) {
                char next = body.charAt(++index);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\'' -> out.append('\'');
                    case '\\' -> out.append('\\');
                    default -> out.append(next);
                }
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static String methodName(String text, int from) {
        int cursor = from;
        int guard = 0;
        while (cursor < text.length() && guard++ < 4000) {
            cursor = after(text, cursor);
            if (cursor >= text.length()) return null;
            if (text.startsWith("//", cursor)) {
                int end = text.indexOf('\n', cursor);
                cursor = end < 0 ? text.length() : end + 1;
                continue;
            }
            if (text.startsWith("/*", cursor)) {
                int end = text.indexOf("*/", cursor + 2);
                cursor = end < 0 ? text.length() : end + 2;
                continue;
            }
            if (text.charAt(cursor) == '@') {
                cursor = skipAnnotation(text, cursor);
                continue;
            }
            break;
        }
        int window = Math.min(text.length(), cursor + 400);
        String slice = text.substring(cursor, window);
        Matcher matcher = Pattern.compile(
                "(?:(?:public|protected|private|static|final|synchronized|native|abstract)\\s+)*([\\w$]+)\\s*\\("
        ).matcher(slice);
        String name = null;
        while (matcher.find()) {
            String token = matcher.group(1);
            if ("if".equals(token) || "for".equals(token) || "while".equals(token) || "switch".equals(token)) continue;
            name = token;
            break;
        }
        return name;
    }

    private static int skipAnnotation(String text, int at) {
        int cursor = at + 1;
        while (cursor < text.length() && (Character.isJavaIdentifierPart(text.charAt(cursor)) || text.charAt(cursor) == '.')) {
            cursor++;
        }
        cursor = after(text, cursor);
        if (cursor < text.length() && text.charAt(cursor) == '(') {
            int close = matching(text, cursor);
            return close < 0 ? text.length() : close + 1;
        }
        return cursor;
    }

    private static int lineAt(String text, int index) {
        int line = 1;
        int end = Math.min(index, text.length());
        for (int cursor = 0; cursor < end; cursor++) {
            if (text.charAt(cursor) == '\n') line++;
        }
        return line;
    }
}
