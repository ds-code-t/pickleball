package tools.dscode.dalton;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared CLI edit model for categories, files, glue, and feature scenario/step
 * commands. Workbench, MCP, and Gradle only forward here. Deletes require
 * {@code --confirm}. Validation runs before any write. A conflict or an
 * untracked database edit is refused unless {@code --force} is set. Dalton-off
 * writes the file and does not open the database.
 */
public final class DaltonEdit {
    public static final String CATEGORY_DIR = "dalton-entities/ElementCategory";
    public static final String GLUE_JS_DIR = "glue/js";
    public static final int CONFIRM_EXIT = 3;
    static final Set<String> FEATURE_EDITS = Set.of(
            "scenario-add", "scenario-modify", "scenario-delete",
            "step-add", "step-modify", "step-delete"
    );
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DELETE_ACTIONS = Set.of("remove", "delete", "scenario-delete", "step-delete");

    private DaltonEdit() {
    }

    public static boolean isFeatureEdit(String action) {
        return action != null && FEATURE_EDITS.contains(action);
    }

    public static int execute(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        String family = tokens.isEmpty() ? "" : tokens.get(0);
        String action = tokens.size() < 2 || tokens.get(1).startsWith("--") ? "" : tokens.get(1);
        boolean asJson = tokens.contains("--json");
        String command = (family + " " + action).trim();
        try {
            if (!known(family, action)) {
                return fail(project, out, err, asJson, command, family, action, "", "", 2,
                        "Unknown Dalton edit command: " + command,
                        "dalton help");
            }
            Outcome outcome = dispatch(project, family, action, tokens);
            String entity = outcome.path() == null || outcome.path().isBlank() ? family : outcome.path();
            DaltonLog.log(project, "edit", entity, action + " ok=" + outcome.ok() + " applied=" + outcome.applied());
            if (asJson) out.println(outcome.json(command, family, action, DaltonConfig.enabled(project)));
            else out.print(outcome.text(command));
            return outcome.exit();
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage() == null ? "edit failed" : failure.getMessage();
            int exit = message.startsWith("confirmation required") ? CONFIRM_EXIT : 1;
            String fix = exit == CONFIRM_EXIT ? "pass --confirm" : "dalton " + command + " --json";
            return fail(project, out, err, asJson, command, family, action, "", "", exit, message, fix);
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return fail(project, out, err, asJson, command, family, action, "", "", 1, message, "dalton help");
        }
    }

    private static boolean known(String family, String action) {
        return switch (family) {
            case "category" -> Set.of("list", "show", "put", "remove").contains(action);
            case "file" -> Set.of("put", "delete", "show").contains(action);
            case "glue" -> Set.of("list", "show", "put", "remove").contains(action);
            case "feature" -> isFeatureEdit(action);
            default -> false;
        };
    }

    private static Outcome dispatch(Path project, String family, String action, List<String> tokens) throws Exception {
        return switch (family) {
            case "category" -> category(project, action, tokens);
            case "file" -> file(project, action, tokens);
            case "glue" -> glue(project, action, tokens);
            case "feature" -> feature(project, action, tokens);
            default -> throw new IllegalArgumentException("Unknown Dalton edit command: " + family);
        };
    }

    private static Outcome category(Path project, String action, List<String> tokens) throws Exception {
        return switch (action) {
            case "list" -> list(project, CATEGORY_DIR, ".yaml");
            case "show" -> show(project, categoryPath(option(tokens, "--id=")));
            case "put" -> put(project, categoryPath(option(tokens, "--id=")), tokens);
            case "remove" -> delete(project, categoryPath(option(tokens, "--id=")), tokens);
            default -> throw new IllegalArgumentException("Unknown Dalton edit command: category " + action);
        };
    }

    private static Outcome file(Path project, String action, List<String> tokens) throws Exception {
        String path = option(tokens, "--path=");
        return switch (action) {
            case "show" -> show(project, DaltonPaths.normalizeRelative(path));
            case "put" -> put(project, path, tokens);
            case "delete" -> delete(project, path, tokens);
            default -> throw new IllegalArgumentException("Unknown Dalton edit command: file " + action);
        };
    }

    private static Outcome glue(Path project, String action, List<String> tokens) throws Exception {
        return switch (action) {
            case "list" -> listGlue(project);
            case "show" -> show(project, gluePath(option(tokens, "--path=")));
            case "put" -> put(project, gluePath(option(tokens, "--path=")), tokens);
            case "remove" -> delete(project, gluePath(option(tokens, "--path=")), tokens);
            default -> throw new IllegalArgumentException("Unknown Dalton edit command: glue " + action);
        };
    }

    /** Scenario and step edits regenerate Gherkin, then write through feature put or the file. */
    private static Outcome feature(Path project, String action, List<String> tokens) {
        String feature = option(tokens, "--feature=");
        if (feature == null || feature.isBlank()) {
            throw new IllegalArgumentException(action + " needs --feature");
        }
        String path = DaltonPaths.normalizeRelative(feature);
        if (!path.toLowerCase(Locale.ROOT).endsWith(".feature")) {
            throw new IllegalArgumentException("feature path must end with .feature: " + path);
        }
        requireFeatureArgs(action, tokens);
        if (DELETE_ACTIONS.contains(action) && !tokens.contains("--confirm")) {
            throw new IllegalArgumentException("confirmation required");
        }
        if (GherkinRewrite.available()) {
            return GherkinRewrite.apply(project, action, path, tokens);
        }
        return Outcome.accepted(path, idOf(path), "accepted; gherkin regeneration writes through feature put");
    }

    private static void requireFeatureArgs(String action, List<String> tokens) {
        switch (action) {
            case "scenario-add" -> {
                if (blank(option(tokens, "--name="))) throw new IllegalArgumentException("scenario-add needs --name");
            }
            case "scenario-modify", "scenario-delete" -> {
                if (blank(option(tokens, "--scenario=")) && blank(option(tokens, "--name="))) {
                    throw new IllegalArgumentException(action + " needs --scenario or --name");
                }
            }
            case "step-add" -> {
                if (blank(option(tokens, "--scenario=")) && blank(option(tokens, "--name="))) {
                    throw new IllegalArgumentException("step-add needs --scenario");
                }
                if (blank(option(tokens, "--text=")) && blank(option(tokens, "--file="))) {
                    throw new IllegalArgumentException("step-add needs --text or --file");
                }
            }
            case "step-modify", "step-delete" -> {
                if (blank(option(tokens, "--scenario=")) && blank(option(tokens, "--name="))) {
                    throw new IllegalArgumentException(action + " needs --scenario");
                }
                if (blank(option(tokens, "--step="))) throw new IllegalArgumentException(action + " needs --step");
            }
            default -> throw new IllegalArgumentException("Unknown Dalton edit command: feature " + action);
        }
    }

    private static Outcome list(Path project, String directory, String suffix) throws Exception {
        List<Item> items = new ArrayList<>();
        if (DaltonConfig.enabled(project) && DaltonQuery.databasePresent(project)) {
            for (DaltonQuery.FileSummary file : DaltonQuery.files(project)) {
                if (file.path().startsWith(directory + "/") && file.path().endsWith(suffix)) {
                    items.add(new Item(idOf(file.path()), file.path(), idOf(file.path())));
                }
            }
        }
        Path dir = project.resolve(directory);
        if (Files.isDirectory(dir)) {
            try (var walk = Files.list(dir)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(suffix)) continue;
                    String relative = DaltonPaths.normalizeRelative(directory + "/" + name);
                    if (items.stream().noneMatch(item -> item.path().equals(relative))) {
                        items.add(new Item(idOf(relative), relative, idOf(relative)));
                    }
                }
            }
        }
        items.sort(Comparator.comparing(Item::path));
        return Outcome.list(items);
    }

    private static Outcome listGlue(Path project) throws IOException {
        List<Item> items = new ArrayList<>();
        collectGlue(project, project.resolve("src/main/java"), ".java", items);
        collectGlue(project, project.resolve("src/test/java"), ".java", items);
        collectGlue(project, project.resolve(GLUE_JS_DIR), ".js", items);
        collectGlue(project, project.resolve(GLUE_JS_DIR), ".ts", items);
        if (DaltonConfig.enabled(project) && DaltonQuery.databasePresent(project)) {
            try {
                for (DaltonQuery.FileSummary file : DaltonQuery.files(project)) {
                    if (!isGluePath(file.path())) continue;
                    if (items.stream().noneMatch(item -> item.path().equals(file.path()))) {
                        items.add(new Item(file.path(), file.path(), file.path()));
                    }
                }
            } catch (Exception failure) {
                throw new IOException(failure.getMessage(), failure);
            }
        }
        items.sort(Comparator.comparing(Item::path));
        return Outcome.list(items);
    }

    private static void collectGlue(Path project, Path root, String suffix, List<Item> items) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (!file.getFileName().toString().endsWith(suffix)) continue;
                String relative = project.relativize(file).toString().replace('\\', '/');
                items.add(new Item(relative, relative, relative));
            }
        }
    }

    private static Outcome show(Path project, String path) throws Exception {
        String text = FileRules.redact(readExisting(project, path));
        return Outcome.shown(path, idOf(path), text);
    }

    private static Outcome put(Path project, String path, List<String> tokens) throws Exception {
        String relative = DaltonPaths.normalizeRelative(path);
        byte[] body = body(project, tokens);
        validate(project, relative, body);
        refuseConflict(project, relative, tokens.contains("--force"));
        boolean dalton = DaltonConfig.enabled(project);
        boolean edited = false;
        if (dalton) {
            DaltonContent.put(project, relative, body);
            edited = true;
        } else {
            Path disk = project.resolve(relative);
            Files.createDirectories(disk.getParent());
            Files.write(disk, body);
        }
        GlueRules.afterWrite(project, relative);
        return Outcome.written(relative, idOf(relative), edited, new String(body, StandardCharsets.UTF_8));
    }

    private static Outcome delete(Path project, String path, List<String> tokens) throws Exception {
        String relative = DaltonPaths.normalizeRelative(path);
        if (!tokens.contains("--confirm")) {
            throw new IllegalArgumentException("confirmation required");
        }
        CategoryRules.beforeDelete(relative);
        refuseConflict(project, relative, tokens.contains("--force"));
        boolean removed;
        if (DaltonConfig.enabled(project)) {
            removed = DaltonContent.delete(project, relative);
        } else {
            Path disk = project.resolve(relative);
            if (!Files.isRegularFile(disk)) removed = false;
            else {
                Files.delete(disk);
                removed = true;
            }
            DaltonLog.log(project, "edit", relative, "delete file");
        }
        if (!removed) throw new IllegalArgumentException("not found: " + relative);
        return Outcome.deleted(relative, idOf(relative), DaltonConfig.enabled(project));
    }

    static void validate(Path project, String path, byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("edit body is empty");
        }
        EditRules.validate(project, path, body);
    }

    static void refuseConflict(Path project, String path, boolean force) throws Exception {
        if (force || !DaltonConfig.enabled(project) || !DaltonQuery.databasePresent(project)) return;
        for (DaltonQuery.Conflict conflict : DaltonQuery.conflicts(project)) {
            if (path.equals(conflict.path())) {
                throw new IllegalArgumentException(conflict.message());
            }
        }
    }

    private static String readExisting(Path project, String path) throws Exception {
        String relative = DaltonPaths.normalizeRelative(path);
        if (DaltonConfig.enabled(project) && DaltonQuery.databasePresent(project)) {
            try {
                DaltonQuery.FileDetail detail = DaltonQuery.file(project, relative);
                if (detail.text() != null) return detail.text();
            } catch (IllegalArgumentException missing) {
                // Fall through to the file when the row is absent.
            }
        }
        Path disk = project.resolve(relative);
        if (!Files.isRegularFile(disk)) throw new IllegalArgumentException("not found: " + relative);
        return Files.readString(disk, StandardCharsets.UTF_8);
    }

    private static byte[] body(Path project, List<String> tokens) throws IOException {
        String text = option(tokens, "--text=");
        String file = option(tokens, "--file=");
        if (text != null && file != null) {
            throw new IllegalArgumentException("edit accepts --text or --file, not both");
        }
        if (file != null) {
            Path path = Path.of(file);
            if (!path.isAbsolute()) path = project.resolve(file);
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("edit file not found: " + file);
            return Files.readAllBytes(path);
        }
        if (text != null) return text.getBytes(StandardCharsets.UTF_8);
        throw new IllegalArgumentException("edit needs --text or --file");
    }

    static String categoryPath(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("category needs --id");
        String name = id.trim();
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            throw new IllegalArgumentException("category id must be a single name: " + id);
        }
        return DaltonPaths.normalizeRelative(CATEGORY_DIR + "/" + name + ".yaml");
    }

    static String gluePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("glue needs --path");
        String relative = DaltonPaths.normalizeRelative(path);
        if (!isGluePath(relative)) {
            throw new IllegalArgumentException("glue path must be a .java file or a file under glue/js/: " + relative);
        }
        return relative;
    }

    static boolean isGluePath(String path) {
        if (path == null) return false;
        String normal = path.replace('\\', '/');
        if (normal.startsWith(GLUE_JS_DIR + "/") && (normal.endsWith(".js") || normal.endsWith(".ts"))) return true;
        return normal.endsWith(".java") && (normal.startsWith("src/main/java/") || normal.startsWith("src/test/java/"));
    }

    private static String idOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.endsWith(".yaml")) return name.substring(0, name.length() - 5);
        return name;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String option(List<String> tokens, String prefix) {
        for (String token : tokens) {
            if (token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    private static int fail(
            Path project,
            PrintStream out,
            PrintStream err,
            boolean asJson,
            String command,
            String family,
            String action,
            String path,
            String id,
            int exit,
            String message,
            String fix
    ) {
        DaltonLog.log(project, "edit", path == null || path.isBlank() ? family : path, "error " + message);
        if (asJson) {
            out.println(Outcome.error(command, family, action, path, id, DaltonConfig.enabled(project), exit == CONFIRM_EXIT, message, fix));
        }
        err.println("error: " + message);
        err.println("fix: " + fix);
        return exit;
    }

    record Item(String id, String path, String name) {
    }

    record Outcome(
            boolean ok,
            String path,
            String id,
            boolean dbEdited,
            boolean conflict,
            boolean confirmRequired,
            boolean applied,
            List<Item> items,
            String text,
            String message,
            String error,
            String fix,
            int exit
    ) {
        static Outcome list(List<Item> items) {
            return new Outcome(true, "", "", false, false, false, false, items, "", "", "", "", 0);
        }

        static Outcome shown(String path, String id, String text) {
            return new Outcome(true, path, id, false, false, false, false, List.of(new Item(id, path, id)), text, "", "", "", 0);
        }

        static Outcome written(String path, String id, boolean dbEdited, String text) {
            return new Outcome(true, path, id, dbEdited, false, false, true, List.of(new Item(id, path, id)), text, "written", "", "", 0);
        }

        static Outcome deleted(String path, String id, boolean dbEdited) {
            return new Outcome(true, path, id, dbEdited, false, false, true, List.of(), "", "deleted", "", "", 0);
        }

        static Outcome accepted(String path, String id, String message) {
            return new Outcome(true, path, id, false, false, false, false, List.of(), "", message, "", "", 0);
        }

        static String error(
                String command, String family, String action, String path, String id,
                boolean dalton, boolean confirm, String message, String fix
        ) {
            Outcome outcome = new Outcome(false, path, id, false, message.startsWith("conflict:") || message.startsWith("untracked"),
                    confirm, false, List.of(), "", "", message, fix, confirm ? CONFIRM_EXIT : 1);
            return outcome.json(command, family, action, dalton);
        }

        String json(String command, String family, String action, boolean dalton) {
            ObjectNode node = JSON.createObjectNode();
            node.put("ok", ok);
            node.put("command", command);
            node.put("family", family);
            node.put("action", action);
            node.put("dalton", dalton);
            node.put("path", path == null ? "" : path);
            node.put("id", id == null ? "" : id);
            node.put("dbEdited", dbEdited);
            node.put("conflict", conflict);
            node.put("confirmRequired", confirmRequired);
            node.put("applied", applied);
            ArrayNode array = node.putArray("items");
            for (Item item : items) {
                ObjectNode one = array.addObject();
                one.put("id", item.id());
                one.put("path", item.path());
                one.put("name", item.name());
            }
            node.put("text", text == null ? "" : text);
            node.put("message", message == null ? "" : message);
            node.put("error", error == null ? "" : error);
            node.put("fix", fix == null ? "" : fix);
            try {
                return JSON.writeValueAsString(node);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        String text(String command) {
            if (!applied && (text == null || text.isEmpty()) && (path == null || path.isEmpty())) {
                StringBuilder lines = new StringBuilder();
                lines.append(command).append(' ').append(items.size()).append('\n');
                for (Item item : items) lines.append(item.id()).append("  ").append(item.path()).append('\n');
                return lines.toString();
            }
            StringBuilder lines = new StringBuilder();
            lines.append(command).append(" path=").append(path == null ? "" : path)
                    .append(" applied=").append(applied)
                    .append(" edited=").append(dbEdited ? 1 : 0);
            if (message != null && !message.isBlank()) lines.append(' ').append(message);
            lines.append('\n');
            return lines.toString();
        }
    }
}
