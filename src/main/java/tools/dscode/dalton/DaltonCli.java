package tools.dscode.dalton;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared Dalton CLI. Gradle, Workbench, and MCP only forward here.
 * {@code dalton status} and {@code dalton hydrate --dry-run} do not create a
 * database when one is missing.
 */
public final class DaltonCli {
    private DaltonCli() {
    }

    public static void main(String[] args) {
        int code = execute(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "dalton".equals(args[0].trim());
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: Dalton CLI expected the dalton command");
            stderr.println("fix: dalton help");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        String command = tokens.isEmpty() ? "help" : tokens.get(0);
        if (command.startsWith("--")) command = "help";
        Path project = project(tokens);
        try {
            return switch (command) {
                case "help", "--help", "-h" -> {
                    stdout.print(help());
                    yield 0;
                }
                case "status" -> status(project, json(tokens), stdout);
                case "hydrate" -> hydrate(project, tokens, stdout, stderr);
                case "extract", "hydrate-out" -> extract(project, tokens, stdout, stderr);
                case "history" -> history(project, tokens, stdout, stderr);
                case "entity" -> entity(project, tokens, stdout, stderr);
                default -> {
                    stderr.println("error: Unknown Dalton command: " + command);
                    stderr.println("fix: dalton help");
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            stderr.println("error: " + failure.getMessage());
            stderr.println("fix: dalton help");
            return 1;
        }
    }

    public static String help() {
        return """
                Usage: dalton status [--project=<dir>] [--json]
                Usage: dalton hydrate [--project=<dir>] [--path=<relative>] [--force] [--dry-run]
                Usage: dalton extract [--project=<dir>] [--path=<relative>] [--force]
                Usage: dalton history list [--project=<dir>]
                Usage: dalton history show <run-id> [--project=<dir>]
                Usage: dalton history record <run-id> [--status=<text>] [--scenario=<name>] [--passed=N] [--failed=N] [--note=<text>] [--message=<text>] [--entity=<name>] [--started=<iso>] [--from=<run-index.json>] [--project=<dir>]
                Usage: dalton history retain [--days=N] [--max-bytes=N] [--project=<dir>]
                Usage: dalton entity list [--project=<dir>]
                Usage: dalton entity get <type> <id> [--project=<dir>]
                Usage: dalton entity put <type> <id> [--version=N] [--payload=<json>] [--payload-file=<path>] [--project=<dir>]
                Usage: dalton entity remove <type> <id> [--force] [--project=<dir>]
                Usage: dalton entity export [--force] [--project=<dir>]
                Files stay the source of truth. The database is .pickleball/dalton/dalton.db and is gitignored.
                status and hydrate --dry-run do not create a database when it is missing.
                hydrate copies features, configs, calls, components, resource maps, templates, and dalton-entities/*.entity files.
                A .feature file also stores scenarios, steps, tags, docstrings, and data tables. The original bytes stay in the file row.
                Unchanged content hashes are skipped. --force rewrites the database rows from files.
                Auto-hydrate on a run happens only when pkb_dalton=true or dalton.properties sets enabled=true.
                With that flag on, feature loading reads those database bytes. A path that is not in the database stays on the file. With the flag off, loading does not open the database.
                Entity files are dalton-entities/<Type>/<id>.entity. The reference is <entity:Type#id.field>. Secret fields must be references such as <password:name> or ~MASK;, never cleartext.
                entity export writes a changed entity back to its file. It is extract limited to dalton-entities/. It refuses when the file and the database both changed. --force overwrites the file from the stored bytes.
                extract copies stored file bytes back to disk and does not reformat them. hydrate-out is the same command. An unedited row is left untouched. A deleted file is not recreated unless that row was edited or --force is set.
                history stores a run summary in the database. It does not change diagnostic reports or pkb_reportretention. --from copies outcome and counts from a run-index.json file. retain drops runs older than --days and then the oldest runs until the note, scenario, and message bytes fit --max-bytes.
                sqlite-jdbc is required only when a command opens the database. It is not published in the Pickleball POM.
                """;
    }

    private static int status(Path project, boolean json, PrintStream out) {
        Path db = DaltonPaths.database(project);
        String shown = DaltonPaths.display(project, db);
        boolean present = Files.isRegularFile(db);
        boolean driver = DaltonDatabase.driverPresent();
        DaltonLog.log(project, "status", "files", present ? "present " + shown : "missing " + shown);
        if (!present) {
            if (json) {
                out.println("{\"database\":\"missing\",\"path\":\"" + shown + "\",\"driver\":" + driver + ",\"files\":0}");
            } else {
                out.println("Dalton database missing: " + shown);
                if (!driver) out.println("sqlite-jdbc is not on the classpath");
            }
            return 0;
        }
        if (!driver) {
            out.println("Dalton database missing: " + shown);
            out.println("sqlite-jdbc is not on the classpath");
            return 1;
        }
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            int version = database.schemaVersion();
            int files = count(database, "SELECT COUNT(*) FROM files");
            int scenarios = count(database, "SELECT COUNT(*) FROM scenarios");
            int entities = count(database, "SELECT COUNT(*) FROM entities");
            int runs = count(database, "SELECT COUNT(*) FROM runs");
            if (json) {
                out.println("{\"database\":\"present\",\"path\":\"" + shown + "\",\"schemaVersion\":"
                        + version + ",\"files\":" + files + ",\"scenarios\":" + scenarios
                        + ",\"entities\":" + entities + ",\"runs\":" + runs + ",\"driver\":true}");
            } else {
                out.println("Dalton schema " + version);
                out.println("files " + files);
                out.println("scenarios " + scenarios);
                out.println("entities " + entities);
                out.println("runs " + runs);
                out.println("database " + shown);
            }
            return 0;
        } catch (SQLException failure) {
            out.println("error: " + failure.getMessage());
            return 1;
        }
    }

    private static int hydrate(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        List<String> paths = new ArrayList<>();
        for (String token : tokens) {
            if (token.startsWith("--path=")) paths.add(token.substring("--path=".length()));
        }
        boolean dryRun = has(tokens, "--dry-run");
        boolean force = has(tokens, "--force");
        try {
            DaltonHydrator.Result result = DaltonHydrator.hydrate(
                    project, new DaltonHydrator.Options(paths, force, dryRun));
            out.println(result.line());
            for (String conflict : result.conflictPaths()) err.println("conflict: " + conflict);
            boolean present = Files.isRegularFile(DaltonPaths.database(project));
            out.println("database " + (present ? "present" : "missing"));
            return result.conflicts() > 0 ? 1 : 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton hydrate --dry-run");
            return 1;
        }
    }

    private static int extract(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        List<String> paths = new ArrayList<>();
        for (String token : tokens) {
            if (token.startsWith("--path=")) paths.add(token.substring("--path=".length()));
        }
        boolean force = has(tokens, "--force");
        try {
            DaltonExtractor.Result result = DaltonExtractor.extract(project, new DaltonExtractor.Options(paths, force));
            out.println(result.line());
            for (String conflict : result.conflictPaths()) err.println("conflict: " + conflict);
            boolean present = Files.isRegularFile(DaltonPaths.database(project));
            out.println("database " + (present ? "present" : "missing"));
            return result.conflicts() > 0 ? 1 : 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton extract");
            return 1;
        }
    }

    private static int history(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        String action = tokens.size() < 2 || tokens.get(1).startsWith("--") ? "list" : tokens.get(1);
        try {
            return switch (action) {
                case "list" -> {
                    List<DaltonHistory.Run> runs = DaltonHistory.list(project);
                    if (runs.isEmpty()) out.println("runs 0");
                    for (DaltonHistory.Run run : runs) {
                        out.println(run.id() + " " + run.status() + " " + run.startedAt()
                                + " passed=" + run.passed() + " failed=" + run.failed());
                    }
                    yield 0;
                }
                case "show" -> {
                    String id = arg(tokens, 2, "history show needs a run id");
                    DaltonHistory.Run run = DaltonHistory.find(project, id);
                    if (run == null) {
                        err.println("error: run not found: " + id);
                        err.println("fix: dalton history list");
                        yield 1;
                    }
                    out.println("run " + run.id() + " status " + run.status());
                    out.println("started " + run.startedAt());
                    out.println("scenario " + run.scenario());
                    out.println("passed " + run.passed());
                    out.println("failed " + run.failed());
                    if (!run.note().isBlank()) out.println("note " + run.note());
                    for (DaltonHistory.LogLine line : DaltonHistory.logs(project, id)) {
                        out.println(line.loggedAt() + " " + line.entity() + " " + line.message());
                    }
                    yield 0;
                }
                case "record" -> {
                    String from = option(tokens, "--from=");
                    DaltonHistory.Draft imported = from == null ? null : DaltonHistory.fromDiagnostic(project, from);
                    String id = historyId(tokens, imported);
                    DaltonHistory.Run run = DaltonHistory.record(project, new DaltonHistory.Draft(
                            id,
                            first(option(tokens, "--started="), imported == null ? null : imported.startedAt()),
                            first(option(tokens, "--status="), imported == null ? null : imported.status()),
                            first(option(tokens, "--scenario="), imported == null ? null : imported.scenario()),
                            integer(option(tokens, "--passed="), imported == null ? 0 : imported.passed()),
                            integer(option(tokens, "--failed="), imported == null ? 0 : imported.failed()),
                            first(option(tokens, "--note="), imported == null ? "" : imported.note()),
                            first(option(tokens, "--entity="), imported == null ? "run" : imported.entity()),
                            option(tokens, "--message=")));
                    out.println("run " + run.id() + " status " + run.status());
                    yield 0;
                }
                case "retain" -> {
                    int days = integer(option(tokens, "--days="), DaltonHistory.days());
                    int maxBytes = integer(option(tokens, "--max-bytes="), DaltonHistory.maxBytes());
                    DaltonHistory.RetainResult result = DaltonHistory.retain(project, days, maxBytes);
                    out.println(result.line());
                    boolean present = Files.isRegularFile(DaltonPaths.database(project));
                    out.println("database " + (present ? "present" : "missing"));
                    yield 0;
                }
                default -> {
                    err.println("error: Unknown Dalton history command: " + action);
                    err.println("fix: dalton help");
                    yield 2;
                }
            };
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton history list");
            return 1;
        }
    }

    private static String historyId(List<String> tokens, DaltonHistory.Draft imported) {
        if (tokens.size() >= 3 && !tokens.get(2).startsWith("--")) return tokens.get(2);
        if (imported != null && imported.id() != null && !imported.id().isBlank()) return imported.id();
        throw new IllegalArgumentException("history record needs a run id");
    }

    private static String option(List<String> tokens, String prefix) {
        for (String token : tokens) {
            if (token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }

    private static String first(String chosen, String fallback) {
        return chosen == null || chosen.isBlank() ? fallback : chosen;
    }

    private static int integer(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("expected an integer: " + raw);
        }
    }

    private static String arg(List<String> tokens, int index, String message) {
        if (index >= tokens.size() || tokens.get(index).startsWith("--")) {
            throw new IllegalArgumentException(message);
        }
        return tokens.get(index);
    }

    private static int entity(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        String action = tokens.size() < 2 ? "help" : tokens.get(1);
        boolean force = has(tokens, "--force");
        try {
            return switch (action) {
                case "list" -> {
                    List<DaltonEntities.Entity> entities = DaltonEntities.list(project);
                    if (entities.isEmpty()) out.println("entities 0");
                    for (DaltonEntities.Entity entity : entities) {
                        out.println(entity.type() + " " + entity.id() + " " + entity.version());
                    }
                    yield 0;
                }
                case "get" -> {
                    String type = arg(tokens, 2);
                    String id = arg(tokens, 3);
                    DaltonEntities.Entity entity = DaltonEntities.get(project, type, id);
                    if (entity == null) {
                        err.println("error: entity not found: " + type + " " + id);
                        err.println("fix: dalton entity list");
                        yield 1;
                    }
                    out.write(DaltonEntities.canonicalFile(entity.type(), entity.id(), entity.version(), entity.payloadJson()));
                    yield 0;
                }
                case "put" -> {
                    String type = arg(tokens, 2);
                    String id = arg(tokens, 3);
                    String payload = payload(project, tokens);
                    Integer version = version(tokens);
                    DaltonEntities.Entity entity = DaltonEntities.put(project, type, id, version, payload);
                    out.println("entity " + entity.type() + " " + entity.id() + " version " + entity.version());
                    yield 0;
                }
                case "remove" -> {
                    String type = arg(tokens, 2);
                    String id = arg(tokens, 3);
                    if (!Files.isRegularFile(DaltonPaths.database(project))) {
                        err.println("error: entity not found: " + type + " " + id);
                        yield 1;
                    }
                    boolean removed = DaltonEntities.remove(project, type, id, force);
                    if (!removed) {
                        err.println("error: entity not found: " + type + " " + id);
                        yield 1;
                    }
                    out.println("removed " + type + " " + id);
                    yield 0;
                }
                case "export" -> {
                    DaltonEntities.ExportResult result = DaltonEntities.export(project, force);
                    out.println(result.line());
                    for (String conflict : result.conflictPaths()) err.println("conflict: " + conflict);
                    yield result.conflicts() > 0 ? 1 : 0;
                }
                default -> {
                    err.println("error: Unknown Dalton entity command: " + action);
                    err.println("fix: dalton help");
                    yield 2;
                }
            };
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton help");
            return failure.getMessage() != null && failure.getMessage().startsWith("conflict:") ? 1 : 1;
        }
    }

    private static String arg(List<String> tokens, int index) {
        if (index >= tokens.size() || tokens.get(index).startsWith("--")) {
            throw new IllegalArgumentException("entity command needs a type and an id");
        }
        return tokens.get(index);
    }

    private static Integer version(List<String> tokens) {
        for (String token : tokens) {
            if (token.startsWith("--version=")) return Integer.valueOf(token.substring("--version=".length()));
        }
        return null;
    }

    private static String payload(Path project, List<String> tokens) throws java.io.IOException {
        for (String token : tokens) {
            if (token.startsWith("--payload=")) return token.substring("--payload=".length());
            if (token.startsWith("--payload-file=")) {
                String raw = token.substring("--payload-file=".length());
                Path file = Path.of(raw);
                if (!file.isAbsolute()) file = project.resolve(raw);
                return Files.readString(file);
            }
        }
        throw new IllegalArgumentException("entity put needs --payload or --payload-file");
    }

    private static int count(DaltonDatabase database, String sql) throws SQLException {
        try (var statement = database.connection().createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static Path project(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("--project=")) {
                return Path.of(token.substring("--project=".length())).toAbsolutePath().normalize();
            }
            if ("--project".equals(token) && index + 1 < tokens.size()) {
                return Path.of(tokens.get(index + 1)).toAbsolutePath().normalize();
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static boolean json(List<String> tokens) {
        return has(tokens, "--json");
    }

    private static boolean has(List<String> tokens, String flag) {
        return tokens.contains(flag);
    }
}
