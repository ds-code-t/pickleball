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
                case "feature" -> {
                    String featureAction = action(tokens);
                    if (DaltonEdit.isFeatureEdit(featureAction)) {
                        yield DaltonEdit.execute(project, tokens, stdout, stderr);
                    }
                    yield feature(project, tokens, stdout, stderr);
                }
                case "category", "file", "glue" -> DaltonEdit.execute(project, tokens, stdout, stderr);
                case "files" -> files(project, tokens, json(tokens), stdout, stderr);
                case "features" -> features(project, tokens, json(tokens), stdout, stderr);
                case "scenario", "scenarios" -> scenarios(project, tokens, json(tokens), stdout, stderr);
                case "step", "steps" -> steps(project, tokens, json(tokens), stdout, stderr);
                case "conflicts" -> conflicts(project, json(tokens), stdout, stderr);
                case "run" -> runCommand(project, tokens, stdout, stderr);
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
                Usage: dalton feature put <relative-path> [--text=<gherkin>] [--file=<path>] [--project=<dir>]
                Usage: dalton feature scenario-add|scenario-modify|scenario-delete|step-add|step-modify|step-delete --feature=<relative-path> [--name=<text>] [--scenario=<ord-or-name>] [--step=<ord>] [--keyword=<Given|When|Then|And|But|*>] [--text=<text>] [--file=<path>] [--confirm] [--force] [--json] [--project=<dir>]
                Usage: dalton category list|show|put|remove [--id=<name>] [--text=<yaml>] [--file=<path>] [--confirm] [--force] [--json] [--project=<dir>]
                Usage: dalton file put|delete --path=<relative> [--text=<text>] [--file=<path>] [--confirm] [--force] [--json] [--project=<dir>]
                Usage: dalton glue list|show|put|remove [--path=<relative.java-or-glue/js>] [--text=<source>] [--file=<path>] [--confirm] [--force] [--json] [--project=<dir>]
                Usage: dalton files list [--project=<dir>] [--json]
                Usage: dalton files show <relative-path> [--project=<dir>] [--json]
                Usage: dalton features list [--project=<dir>] [--json]
                Usage: dalton features show <relative-path> [--project=<dir>] [--json]
                Usage: dalton scenarios list [--feature=<relative-path>] [--project=<dir>] [--json]
                Usage: dalton steps list [--feature=<relative-path>] [--scenario=<ord-or-name>] [--project=<dir>] [--json]
                Usage: dalton conflicts [--project=<dir>] [--json]
                Usage: dalton run [--project=<dir>] [--dry-run] [--tags=<expr>] [--name=<text>] [--glue=<packages>] [--engine]
                Files stay the source of truth. The database is .pickleball/dalton/dalton.db and is gitignored.
                status and hydrate --dry-run do not create a database when it is missing.
                hydrate copies features, configs, calls, components, resource maps, templates, and dalton-entities/*.entity files.
                A .feature file also stores scenarios, steps, tags, docstrings, and data tables. The original bytes stay in the file row.
                Unchanged content hashes are skipped. --force rewrites the database rows from files.
                Auto-hydrate on a run happens only when pkb_dalton=true or dalton.properties sets enabled=true.
                With that flag on, feature loading reads those database bytes. A path that is not in the database stays on the file. With the flag off, loading does not open the database.
                Entity files are dalton-entities/<Type>/<id>.entity. The reference is <entity:Type#id.field>. Secret fields must be references such as <password:name> or ~MASK;, never cleartext. secret-fields= in dalton.properties, or pkb_dalton_secret_fields, adds names to that list. A long spaceless value on another field warns and is stored. The database is not encrypted.
                entity export writes a changed entity back to its file. It is extract limited to dalton-entities/. It refuses when the file and the database both changed. --force overwrites the file from the stored bytes.
                extract copies stored file bytes back to disk and does not reformat them. hydrate-out is the same command. An unedited row is left untouched. A deleted file is not recreated unless that row was edited or --force is set.
                history stores a run summary in the database. It does not change diagnostic reports or pkb_reportretention. --from copies outcome and counts from a run-index.json file. retain drops runs older than --days and then the oldest runs until the note, scenario, and message bytes fit --max-bytes.
                sqlite-jdbc is required only when a command opens the database. It is not published in the Pickleball POM. When Dalton is enabled and that driver is missing, status, hydrate, extract, and the auto-hydrate hook fail with: Dalton is enabled but org.xerial:sqlite-jdbc is not on the classpath. Add it as testRuntimeOnly / test scope or use the Gradle dalton* tasks. They do not fall back to the files. Gradle dalton* tasks put the driver on their own classpath.
                feature put writes that .feature text into the database. It sets db_edited, leaves last_synced_hash, and replaces the stored scenarios, steps, tags, docstrings, and data tables in the same transaction. Bad Gherkin is rejected and the previous rows stay. The file is not changed until dalton extract. Use --file for multiline text. dalton scenario put and dalton step put are rejected. A database row whose content hash moved while db_edited is still 0 is an untracked DB edit. hydrate reports it and will not overwrite it unless --force is set.
                category, file, glue, and feature scenario-add, scenario-modify, scenario-delete, step-add, step-modify, and step-delete are the shared edit model. --json prints one object with ok, command, family, action, dalton, path, id, dbEdited, conflict, confirmRequired, applied, items, text, message, error, and fix. remove and delete require --confirm and do not write without it. Validation runs before a write. A conflict or an untracked DB edit on that path is refused unless --force is set. When Dalton is off the command writes the file and does not open the database. When Dalton is on, put uses the same db_edited write as feature put. Set pkb_dalton_log=edit to log each edit. The log is date-stamped, per entity, and size-capped.
                entity put uses that same write, so the entity file row and the entity table stay together.
                files, features, scenarios, steps, and conflicts are read-only. They do not create a database. --json prints one stable JSON object. Text mode prints labels. conflicts lists an untracked database edit and a row edited on both sides.
                Discovery reads include, exclude, max-file-size (default 1048576), and binary-policy (default skip) from dalton.properties. System properties pkb_dalton_include, pkb_dalton_exclude, pkb_dalton_max_file_size, and pkb_dalton_binary_policy win over that file. An empty include keeps the fixed directories. Exclude is added to the defaults, which already skip .git, target, build, .pickleball, and common binary extensions. A file over the size limit, or a binary by extension or by a null byte or a high control-byte ratio, is ignored. dalton status prints skipped and up to five sample lines and does not create a database. Hydrate prints ignored= for those files. --path still applies the limits. --path --force is the only bypass. hash-only is not implemented. Set pkb_dalton_log=discover to log each reason.
                dalton run executes from the database on a warm JVM. It sets pkb_dalton=true and uses the pickleball-test-engine, the same engine as a normal run. --tags, --name, and --glue become pkb_tags, pkb_name, and pkb_glue. It prints one line per scenario (`scenario PASSED|FAILED <name>`) and one line per step (`step PASSED|FAILED <text>`). Java glue is fingerprinted by path, size, and modification time. A change compiles once with mvnw test-compile or gradlew testClasses, then restarts the worker. The classpath is pkb_dalton_run_classpath or the Workbench classpath.cache. --dry-run prints the plan and writes nothing. Full Gradle or Maven remains the CI build. Set pkb_dalton_log=run to log the worker. --engine forwards the same selection to the native engine CLI and does not start the Cucumber runner.
                """;
    }

    private static int runCommand(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        if (has(tokens, "--engine")) {
            List<String> forwarded = new ArrayList<>();
            forwarded.add("engine");
            forwarded.add("run");
            forwarded.add("--dalton");
            forwarded.add("--project=" + project.toAbsolutePath().normalize());
            copyOption(tokens, forwarded, "--name");
            copyOption(tokens, forwarded, "--glue");
            copyOption(tokens, forwarded, "--feature");
            return tools.dscode.engine.EngineCli.execute(forwarded.toArray(String[]::new), out, err);
        }
        boolean dry = has(tokens, "--dry-run");
        List<String> extra = DaltonRun.selection(
                option(tokens, "--tags="),
                option(tokens, "--name="),
                option(tokens, "--glue="),
                tokens
        );
        try {
            DaltonRun.Outcome outcome = DaltonRun.execute(project, extra, dry);
            if (!outcome.out().isEmpty()) out.print(outcome.out());
            if (!outcome.err().isEmpty()) err.print(outcome.err());
            if (!dry) {
                out.println("dalton run exit=" + outcome.exit() + " reused=" + outcome.reused()
                        + " millis=" + outcome.millis() + " " + outcome.plan().line());
            }
            return outcome.exit();
        } catch (Exception failure) {
            err.println("error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            err.println("fix: compile the project once, then dalton run --dry-run");
            return 1;
        }
    }

    private static void copyOption(List<String> tokens, List<String> forwarded, String name) {
        String prefix = name + "=";
        for (String token : tokens) {
            if (token.startsWith(prefix)) forwarded.add(token);
        }
    }

    private static int status(Path project, boolean json, PrintStream out) {
        Path db = DaltonPaths.database(project);
        String shown = DaltonPaths.display(project, db);
        boolean present = Files.isRegularFile(db);
        boolean driver = DaltonDatabase.driverPresent();
        if (!driver && DaltonConfig.enabled(project)) {
            DaltonLog.log(project, "status", "files", "missing-driver " + shown);
            if (json) {
                out.println("{\"database\":\"" + (present ? "present" : "missing") + "\",\"path\":\""
                        + jsonEscape(shown) + "\",\"driver\":false,\"error\":\""
                        + jsonEscape(DaltonDatabase.MISSING_DRIVER) + "\"}");
            } else {
                out.println(DaltonDatabase.MISSING_DRIVER);
            }
            return 1;
        }
        DaltonLog.log(project, "status", "files", present ? "present " + shown : "missing " + shown);
        List<DaltonSources.Skip> skipped = discoverySkips(project);
        if (!present) {
            if (json) {
                out.println("{\"database\":\"missing\",\"path\":\"" + jsonEscape(shown) + "\",\"driver\":" + driver
                        + ",\"files\":0" + skipJson(skipped));
            } else {
                out.println("Dalton database missing: " + shown);
                if (!driver) out.println("sqlite-jdbc is not on the classpath");
                printSkips(out, skipped);
            }
            return 0;
        }
        if (!driver) {
            out.println("Dalton database missing: " + shown);
            out.println("sqlite-jdbc is not on the classpath");
            printSkips(out, skipped);
            return 1;
        }
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            int version = database.schemaVersion();
            int files = count(database, "SELECT COUNT(*) FROM files");
            int scenarios = count(database, "SELECT COUNT(*) FROM scenarios");
            int entities = count(database, "SELECT COUNT(*) FROM entities");
            int runs = count(database, "SELECT COUNT(*) FROM runs");
            int conflicts = DaltonQuery.conflicts(project, database.connection()).size();
            if (json) {
                out.println("{\"database\":\"present\",\"path\":\"" + jsonEscape(shown) + "\",\"schemaVersion\":"
                        + version + ",\"files\":" + files + ",\"scenarios\":" + scenarios
                        + ",\"entities\":" + entities + ",\"runs\":" + runs
                        + ",\"conflicts\":" + conflicts + ",\"driver\":true" + skipJson(skipped));
            } else {
                out.println("Dalton schema " + version);
                out.println("files " + files);
                out.println("scenarios " + scenarios);
                out.println("entities " + entities);
                out.println("runs " + runs);
                out.println("conflicts " + conflicts);
                printSkips(out, skipped);
                out.println("database " + shown);
            }
            return 0;
        } catch (SQLException failure) {
            out.println("error: " + failure.getMessage());
            return 1;
        }
    }

    private static List<DaltonSources.Skip> discoverySkips(Path project) {
        try {
            return DaltonSources.scan(project, List.of(), false).skipped();
        } catch (Exception failure) {
            return List.of();
        }
    }

    private static void printSkips(PrintStream out, List<DaltonSources.Skip> skipped) {
        out.println("skipped " + skipped.size());
        int limit = Math.min(5, skipped.size());
        for (int index = 0; index < limit; index++) {
            DaltonSources.Skip skip = skipped.get(index);
            out.println("sample " + skip.path() + " " + skip.reason());
        }
    }

    private static String skipJson(List<DaltonSources.Skip> skipped) {
        StringBuilder json = new StringBuilder();
        json.append(",\"skipped\":").append(skipped.size()).append(",\"skipSamples\":[");
        int limit = Math.min(5, skipped.size());
        for (int index = 0; index < limit; index++) {
            if (index > 0) json.append(',');
            DaltonSources.Skip skip = skipped.get(index);
            json.append("{\"path\":\"").append(jsonEscape(skip.path()))
                    .append("\",\"reason\":\"").append(jsonEscape(skip.reason())).append("\"}");
        }
        json.append("]}");
        return json.toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
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
            for (String notice : result.notices()) err.println(notice);
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

    private static int files(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String action = action(tokens);
        try {
            if (action.isEmpty() || "list".equals(action)) {
                List<DaltonQuery.FileSummary> files = DaltonQuery.files(project);
                DaltonLog.log(project, "query", "files", "list " + files.size());
                if (json) out.println(DaltonQuery.filesJson(files));
                else {
                    out.println("files " + files.size());
                    for (DaltonQuery.FileSummary file : files) {
                        out.println(file.path() + "  " + file.sourceType()
                                + "  edited=" + (file.edited() ? "yes" : "no")
                                + "  bytes=" + file.bytes());
                    }
                }
                return 0;
            }
            if ("show".equals(action)) {
                String path = arg(tokens, 2, "files show needs a relative path");
                DaltonQuery.FileDetail detail = DaltonQuery.file(project, path);
                if (detail == null) {
                    err.println("error: file not found: " + DaltonPaths.normalizeRelative(path));
                    err.println("fix: dalton files list");
                    return 1;
                }
                DaltonLog.log(project, "query", detail.summary().path(), "show");
                if (json) out.println(DaltonQuery.fileJson(detail));
                else printFile(out, detail);
                return 0;
            }
            err.println("error: Unknown Dalton files command: " + action);
            err.println("fix: dalton files list");
            return 2;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton files list");
            return 1;
        }
    }

    private static int features(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String action = action(tokens);
        try {
            if (action.isEmpty() || "list".equals(action)) {
                List<DaltonQuery.FeatureSummary> features = DaltonQuery.features(project);
                DaltonLog.log(project, "query", "features", "list " + features.size());
                if (json) out.println(DaltonQuery.featuresJson(features));
                else {
                    out.println("features " + features.size());
                    for (DaltonQuery.FeatureSummary feature : features) {
                        out.println(feature.path() + "  " + feature.name() + "  scenarios=" + feature.scenarios());
                    }
                }
                return 0;
            }
            if ("show".equals(action)) {
                String path = arg(tokens, 2, "features show needs a relative path");
                DaltonQuery.FeatureSummary feature = DaltonQuery.feature(project, path);
                if (feature == null) {
                    err.println("error: feature not found: " + DaltonPaths.normalizeRelative(path));
                    err.println("fix: dalton features list");
                    return 1;
                }
                DaltonQuery.FileDetail detail = DaltonQuery.file(project, feature.path());
                String text = detail == null || detail.text() == null ? "" : detail.text();
                List<DaltonQuery.ScenarioRow> scenarios = DaltonQuery.scenarios(project, feature.path());
                DaltonLog.log(project, "query", feature.path(), "feature show");
                if (json) out.println(DaltonQuery.featureJson(feature, text, scenarios));
                else {
                    out.println("path " + feature.path());
                    out.println("name " + feature.name());
                    out.println("language " + feature.language());
                    out.println("tags " + feature.tags());
                    out.println("scenarios " + feature.scenarios());
                    for (DaltonQuery.ScenarioRow scenario : scenarios) {
                        out.println("scenario " + scenario.ord() + " " + scenario.keyword() + " " + scenario.name());
                    }
                    out.println("text");
                    out.print(text);
                    if (!text.endsWith("\n")) out.println();
                }
                return 0;
            }
            err.println("error: Unknown Dalton features command: " + action);
            err.println("fix: dalton features list");
            return 2;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton features list");
            return 1;
        }
    }

    private static int scenarios(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String action = action(tokens);
        if ("put".equals(action) || "update".equals(action) || "edit".equals(action) || "set".equals(action)) {
            return rejectStructured(err);
        }
        if (!action.isEmpty() && !"list".equals(action)) {
            err.println("error: Unknown Dalton scenarios command: " + action);
            err.println("fix: dalton scenarios list");
            return 2;
        }
        try {
            List<DaltonQuery.ScenarioRow> scenarios = DaltonQuery.scenarios(project, option(tokens, "--feature="));
            DaltonLog.log(project, "query", "scenarios", "list " + scenarios.size());
            if (json) out.println(DaltonQuery.scenariosJson(scenarios));
            else {
                out.println("scenarios " + scenarios.size());
                for (DaltonQuery.ScenarioRow scenario : scenarios) {
                    out.println(scenario.path() + "  " + scenario.ord() + "  " + scenario.keyword() + "  " + scenario.name());
                }
            }
            return 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton scenarios list");
            return 1;
        }
    }

    private static int steps(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String action = action(tokens);
        if ("put".equals(action) || "update".equals(action) || "edit".equals(action) || "set".equals(action)) {
            return rejectStructured(err);
        }
        if (!action.isEmpty() && !"list".equals(action)) {
            err.println("error: Unknown Dalton steps command: " + action);
            err.println("fix: dalton steps list");
            return 2;
        }
        try {
            List<DaltonQuery.StepRow> steps = DaltonQuery.steps(
                    project, option(tokens, "--feature="), option(tokens, "--scenario="));
            DaltonLog.log(project, "query", "steps", "list " + steps.size());
            if (json) out.println(DaltonQuery.stepsJson(steps));
            else {
                out.println("steps " + steps.size());
                for (DaltonQuery.StepRow step : steps) {
                    out.println(step.path() + "  " + step.scenario() + "  " + step.ord() + "  " + step.keyword() + "  " + step.text());
                }
            }
            return 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton steps list");
            return 1;
        }
    }

    private static int conflicts(Path project, boolean json, PrintStream out, PrintStream err) {
        try {
            List<DaltonQuery.Conflict> conflicts = DaltonQuery.conflicts(project);
            DaltonLog.log(project, "query", "conflicts", "list " + conflicts.size());
            if (json) out.println(DaltonQuery.conflictsJson(conflicts));
            else {
                out.println("conflicts " + conflicts.size());
                for (DaltonQuery.Conflict conflict : conflicts) out.println(conflict.message());
            }
            return conflicts.isEmpty() ? 0 : 1;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton conflicts");
            return 1;
        }
    }

    private static void printFile(PrintStream out, DaltonQuery.FileDetail detail) {
        DaltonQuery.FileSummary file = detail.summary();
        out.println("path " + file.path());
        out.println("sourceType " + file.sourceType());
        out.println("edited " + (file.edited() ? "yes" : "no"));
        out.println("bytes " + file.bytes());
        out.println("contentHash " + file.contentHash());
        out.println("lastSyncedHash " + file.lastSyncedHash());
        if (detail.text() != null) {
            out.println("text");
            out.print(detail.text());
            if (!detail.text().endsWith("\n")) out.println();
        } else {
            out.println("binary yes");
        }
    }

    private static String action(List<String> tokens) {
        if (tokens.size() < 2 || tokens.get(1).startsWith("--")) return "";
        return tokens.get(1);
    }

    private static int feature(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        String action = tokens.size() < 2 || tokens.get(1).startsWith("--") ? "" : tokens.get(1);
        if ("scenario".equals(action) || "step".equals(action) || "scenarios".equals(action) || "steps".equals(action)) {
            return rejectStructured(err);
        }
        if (!"put".equals(action)) {
            err.println("error: Unknown Dalton feature command: " + (action.isEmpty() ? "feature" : action));
            err.println("fix: dalton feature put <relative-path> --file=<path>");
            return 2;
        }
        try {
            String path = arg(tokens, 2, "feature put needs a relative path");
            if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".feature")) {
                throw new IllegalArgumentException("feature put needs a .feature path: " + path);
            }
            byte[] raw = featureBytes(project, tokens);
            DaltonFiles.Row row = DaltonContent.put(project, path, raw);
            out.println("feature " + row.path() + " edited=1 bytes=" + row.raw().length);
            return 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dalton feature put <relative-path> --file=<path>");
            return 1;
        }
    }

    private static int rejectStructured(PrintStream err) {
        err.println("error: " + DaltonContent.STRUCTURED_EDIT);
        err.println("fix: dalton feature put <relative-path> --file=<path>");
        return 1;
    }

    private static byte[] featureBytes(Path project, List<String> tokens) throws java.io.IOException {
        String text = option(tokens, "--text=");
        String file = option(tokens, "--file=");
        if (text != null && file != null) {
            throw new IllegalArgumentException("feature put accepts --text or --file, not both");
        }
        if (file != null) {
            Path path = Path.of(file);
            if (!path.isAbsolute()) path = project.resolve(file);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("feature file not found: " + file);
            }
            return Files.readAllBytes(path);
        }
        if (text != null) return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        throw new IllegalArgumentException("feature put needs --text or --file");
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
