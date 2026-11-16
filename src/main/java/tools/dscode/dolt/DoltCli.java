package tools.dscode.dolt;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared Dolt CLI. Gradle, Workbench, and MCP only forward here.
 * {@code dolt status} and {@code dolt hydrate --dry-run} do not create a
 * database when one is missing.
 */
public final class DoltCli {
    private DoltCli() {
    }

    public static void main(String[] args) {
        int code = execute(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "dolt".equals(args[0].trim());
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: Dolt CLI expected the dolt command");
            stderr.println("fix: dolt help");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        String command = tokens.isEmpty() ? "help" : tokens.get(0);
        if (command.startsWith("--")) command = "help";
        Path project = project(tokens);
        String rejected = LegacyNames.problem(project);
        if (rejected != null) {
            stderr.println(rejected);
            return 1;
        }
        try {
            DoltScope.bindCommand(tokens);
        } catch (RuntimeException failure) {
            printFailure(stderr, failure.getMessage(), "dolt help");
            return 1;
        }
        try {
            return switch (command) {
                case "help", "--help", "-h" -> {
                    stdout.print(help());
                    yield 0;
                }
                case "migrate" -> DoltMigrate.cli(tokens, stdout, stderr);
                case "sandbox" -> DoltSandbox.execute(project, tokens, stdout, stderr);
                case "status" -> status(project, json(tokens), stdout);
                case "hydrate" -> hydrate(project, tokens, stdout, stderr);
                case "extract", "hydrate-out" -> extract(project, tokens, stdout, stderr);
                case "history" -> history(project, tokens, stdout, stderr);
                case "entity" -> entity(project, tokens, stdout, stderr);
                case "feature" -> {
                    String featureAction = action(tokens);
                    if (DoltEdit.isFeatureEdit(featureAction)) {
                        yield DoltEdit.execute(project, tokens, stdout, stderr);
                    }
                    yield feature(project, tokens, stdout, stderr);
                }
                case "category", "file" -> DoltEdit.execute(project, tokens, stdout, stderr);
                case "glue" -> {
                    if (tokens.size() > 1 && "compile".equals(tokens.get(1))) yield DoltGlueCompiler.cli(project, stdout, stderr);
                    yield DoltEdit.execute(project, tokens, stdout, stderr);
                }
                case "files" -> files(project, tokens, json(tokens), stdout, stderr);
                case "features" -> features(project, tokens, json(tokens), stdout, stderr);
                case "scenario", "scenarios" -> scenarios(project, tokens, json(tokens), stdout, stderr);
                case "step", "steps" -> steps(project, tokens, json(tokens), stdout, stderr);
                case "conflicts" -> conflicts(project, json(tokens), stdout, stderr);
                case "query" -> trackCommand(project, tokens, false, stdout, stderr);
                case "timeline" -> trackCommand(project, tokens, true, stdout, stderr);
                case "retain" -> retainCommand(project, tokens, stdout, stderr);
                case "run" -> runCommand(project, tokens, stdout, stderr);
                case "run-classpath" -> runClasspath(tokens, stdout, stderr);
                default -> {
                    stderr.println("error: Unknown Dolt command: " + command);
                    stderr.println("fix: dolt help");
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            stderr.println("error: " + failure.getMessage());
            stderr.println("fix: dolt help");
            return 1;
        }
    }

    private static int runClasspath(List<String> tokens, PrintStream stdout, PrintStream stderr) {
        String out = "";
        for (String token : tokens) {
            if (token.startsWith("--out=")) out = token.substring("--out=".length());
        }
        if (out.isBlank()) {
            stderr.println("error: dolt run-classpath needs --out=<file>");
            stderr.println("fix: dolt run-classpath --out=build/dolt-run-classpath.txt");
            return 2;
        }
        String text = System.getenv("PKB_DOLT_RUN_CLASSPATH_TEXT");
        if (text == null || text.isBlank()) text = System.getProperty("pkb_dolt_run_classpath");
        if (text == null || text.isBlank()) {
            stderr.println("error: dolt run-classpath has no classpath text");
            stderr.println("fix: set PKB_DOLT_RUN_CLASSPATH_TEXT or pkb_dolt_run_classpath");
            return 1;
        }
        try {
            Path destination = Path.of(out);
            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            if (!text.endsWith("\n")) text = text + "\n";
            Files.writeString(destination, text, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            stderr.println("error: " + failure.getMessage());
            stderr.println("fix: dolt run-classpath --out=<writable-file>");
            return 1;
        }
        stdout.println(out);
        return 0;
    }

    public static String help() {
        return """
                Usage: dolt migrate [--dry-run] [--remove-legacy] [--project=<dir>]
                Usage: dolt sandbox create <name> [--from=<commit>] [--owner=<id>] [--ttl=<duration>] [--project=<dir>]
                Usage: dolt sandbox list [--json] [--project=<dir>]
                Usage: dolt sandbox use <name> [--project=<dir>]
                Usage: dolt sandbox diff <name> [--table=<t>] [--json] [--project=<dir>]
                Usage: dolt sandbox extract <name> --to=<dir> [--into-project] [--project=<dir>]
                Usage: dolt sandbox drop <name> [--force] [--project=<dir>]
                Usage: dolt sandbox prune [--older-than=<duration>] [--owner=<id>] [--dry-run] [--with-history] [--project=<dir>]
                Usage: dolt status [--project=<dir>] [--json]
                Usage: dolt hydrate [--project=<dir>] [--path=<relative>] [--force] [--dry-run]
                Usage: dolt extract [--project=<dir>] [--path=<relative>] [--force]
                Usage: dolt history list [--project=<dir>]
                Usage: dolt history show <run-id> [--project=<dir>]
                Usage: dolt history record <run-id> [--status=<text>] [--scenario=<name>] [--passed=N] [--failed=N] [--note=<text>] [--message=<text>] [--entity=<name>] [--started=<iso>] [--from=<run-index.json>] [--link=Type#id] [--project=<dir>]
                Usage: dolt history retain [--days=N] [--max-bytes=N] [--project=<dir>]
                Usage: dolt entity list [--project=<dir>]
                Usage: dolt entity get <type> <id> [--project=<dir>]
                Usage: dolt entity put <type> <id> [--version=N] [--payload=<json>] [--payload-file=<path>] [--project=<dir>]
                Usage: dolt entity remove <type> <id> [--force] [--project=<dir>]
                Usage: dolt entity export [--force] [--project=<dir>]
                Usage: dolt entity use <type> <id> [--field=<name>] [--operation=read|write|mention] [--value-ref=<reference>] [--step=<step-exec-id>] [--project=<dir>]
                Usage: dolt feature put <relative-path> [--text=<gherkin>] [--file=<path>] [--project=<dir>]
                Usage: dolt feature scenario-add|scenario-modify|scenario-delete|step-add|step-modify|step-delete --feature=<relative-path> [--name=<text>] [--scenario=<ord-or-name>] [--step=<ord>] [--keyword=<Given|When|Then|And|But|*>] [--text=<text>] [--file=<path>] [--confirm] [--force] [--json] [--project=<dir>]
                Usage: dolt category list|show|put|remove [--id=<name>] [--text=<yaml>] [--file=<path>] [--confirm] [--force] [--json] [--project=<dir>]
                Usage: dolt file put|delete|show --path=<relative> [--text=<text>] [--file=<path>] [--confirm] [--force] [--json] [--project=<dir>]
                Usage: dolt glue list|show|put|remove|compile [--path=<relative.java-or-glue/js>] [--text=<source>] [--file=<path>] [--confirm] [--force] [--json] [--sandbox=<name>] [--project=<dir>]
                Usage: dolt files list [--project=<dir>] [--json]
                Usage: dolt files show <relative-path> [--project=<dir>] [--json]
                Usage: dolt features list [--project=<dir>] [--json]
                Usage: dolt features show <relative-path> [--project=<dir>] [--json]
                Usage: dolt scenarios list [--feature=<relative-path>] [--project=<dir>] [--json]
                Usage: dolt steps list [--feature=<relative-path>] [--scenario=<ord-or-name>] [--project=<dir>] [--json]
                Usage: dolt conflicts [--project=<dir>] [--json]
                Usage: dolt query [--entity=Type#id] [--field=<name>] [--step-def=<definition-id>] [--scenario=<key>] [--status=<status>] [--from=<iso>] [--to=<iso>] [--tag=<tag>] [--feature=<path>] [--run=<run-id>] [--text=<log text>] [--limit=N] [--offset=N] [--json] [--project=<dir>]
                Usage: dolt timeline [--from=<iso>] [--to=<iso>] [--entity=Type#id] [--tag=<tag>] [--feature=<path>] [--run=<run-id>] [--text=<log text>] [--cursor=<cursor>] [--before=<cursor>] [--latest] [--limit=N] [--offset=N] [--json] [--project=<dir>]
                Usage: dolt retain [--days=N] [--max-bytes=N] [--project=<dir>]
                Usage: dolt run [--project=<dir>] [--dry-run] [--tags=<expr>] [--name=<text>] [--glue=<packages>] [--sandbox=<name>] [--engine]
                Usage: dolt run-classpath --out=<file> [--project=<dir>]
                Files stay the source of truth. The working copy is .pickleball/dolt/main.db and is gitignored. Opening it starts no process and downloads nothing.
                status and hydrate --dry-run do not create a database when it is missing.
                hydrate copies features, configs, calls, components, resource maps, templates, and dolt-entities/*.entity files.
                A .feature file also stores scenarios, steps, tags, docstrings, and data tables. The original bytes stay in the file row.
                Unchanged content hashes are skipped. --force rewrites the database rows from files.
                Auto-hydrate on a run happens only when pkb_dolt=true or dolt.properties sets enabled=true.
                With that flag on, feature loading reads those database bytes. A path that is not in the database stays on the file. With the flag off, loading does not open the database.
                Entity files are dolt-entities/<Type>/<id>.entity. The reference is <entity:Type#id.field>. Secret fields must be references such as <password:name> or ~MASK;, never cleartext. secret-fields= in dolt.properties, or pkb_dolt_secret_fields, adds names to that list. A long spaceless value on another field warns and is stored. The database is not encrypted.
                entity export writes a changed entity back to its file. It is extract limited to dolt-entities/. It refuses when the file and the database both changed. --force overwrites the file from the stored bytes.
                entity use records a read, write, or mention on the active tracked step or on --step. value-ref is stored only when it is a reference such as <password:qaDb>, <entity:Type#id.field>, or ~MASK;. Any other text is stored as ~MASK;. A lookup during a tracked step records operation=read the same way and does not open a database when no step is active.
                extract copies stored file bytes back to disk and does not reformat them. hydrate-out is the same command. An unedited row is left untouched. A deleted file is not recreated unless that row was edited or --force is set.
                history stores a run summary in the database. It does not change diagnostic reports or pkb_reportretention. --from copies outcome and counts from a run-index.json file and links screenshot files under that directory. Those files are not deleted or rewritten. --link=Type#id writes log_entity_links for that log row. The message text is not scanned. retain drops runs older than --days and then the oldest runs until the note, scenario, and message bytes fit --max-bytes. Retaining a run does not delete the diagnostic report files. dolt retain is that same retain.
                dolt query and dolt timeline read linked executions, usages, log links, and artifacts. They do not create a database. --json is one object with command, count, total, limit, offset, then rows or events. Keys stay in that order. total is the full filtered count on every page, including a --cursor page. timeline --cursor resumes after the previous event. timeline --before rewinds to the page before that cursor. timeline --latest is the newest page. --tag, --feature, --run, and --text filter by tag, feature path, run id, and log or step text. --feature keeps step and usage rows whose scenario key contains that path. --tag keeps step and usage rows for a scenario whose tags match, by file path and scenario line, and a Scenario Outline example row still belongs to that outline. An Examples tag matches only that Examples block, from its Examples line to the next Examples block or the next scenario. A feature tag matches every scenario in that file. A log row stores the run's scenario name, not a scenario path, so a tag or feature filter does not keep it by key. A blank scenario name is stored blank and is not replaced with the scenario key, so a tag or feature filter still does not keep that log row. A timeline run row stays when that run touched a match. A run row has no scenario path. Rewind is view-only over recorded history. It does not undo browser or service side effects.
                The store driver is org.xerial:sqlite-jdbc:3.47.2.0. When the store is enabled and that driver is missing, status, hydrate, extract, and the auto-hydrate hook fail with the driver message and do not fall back to the files. A missing native library can be pointed at an executable directory with -Dorg.sqlite.tmpdir. Nothing is downloaded.
                feature put writes that .feature text into the database. It sets db_edited, leaves last_synced_hash, and replaces the stored scenarios, steps, tags, docstrings, and data tables in the same transaction. Bad Gherkin is rejected and the previous rows stay. The file is not changed until dolt extract. Use --file for multiline text. dolt scenario put and dolt step put are rejected. A database row whose content hash moved while db_edited is still 0 is an untracked DB edit. hydrate reports it and will not overwrite it unless --force is set.
                category, file, glue, and feature scenario-add, scenario-modify, scenario-delete, step-add, step-modify, and step-delete are the shared edit model. --json prints one object with ok, command, family, action, dolt, path, id, dbEdited, conflict, confirmRequired, applied, items, text, message, error, and fix. remove and delete require --confirm and do not write without it. Validation runs before a write. A conflict or an untracked DB edit on that path is refused unless --force is set. When Dolt is off the command writes the file and does not open the database. When Dolt is on, put uses the same db_edited write as feature put. Set pkb_dolt_log=edit to log each edit. The log is date-stamped, per entity, and size-capped.
                entity put uses that same write, so the entity file row and the entity table stay together.
                files, features, scenarios, steps, and conflicts are read-only. They do not create a database. --json prints one stable JSON object. Text mode prints labels. conflicts lists an untracked database edit and a row edited on both sides.
                Discovery reads include, exclude, max-file-size (default 1048576), and binary-policy (default skip) from dolt.properties. System properties pkb_dolt_include, pkb_dolt_exclude, pkb_dolt_max_file_size, and pkb_dolt_binary_policy win over that file. An empty include keeps the fixed directories. Exclude is added to the defaults, which already skip .git, target, build, .pickleball, and common binary extensions. A file over the size limit, or a binary by extension or by a null byte or a high control-byte ratio, is ignored. dolt status prints skipped and up to five sample lines and does not create a database. Hydrate prints ignored= for those files. --path still applies the limits. --path --force is the only bypass. hash-only stores a binary as a reference row (empty raw content, sha256, git blob, byte size) and extract reports binary_ref_changed when the file on disk no longer matches. Set pkb_dolt_log=discover,git,binary to log each reason.
                dolt run executes from the database on a warm JVM. It sets pkb_dolt=true and uses the pickleball-test-engine, the same engine as a normal run. --tags, --name, and --glue become pkb_tags, pkb_name, and pkb_glue. It prints one line per scenario (`scenario PASSED|FAILED <name>`) and one line per step (`step PASSED|FAILED <text>`). Each run gets its own id, the same shape as a diagnostic run id, unless the caller already set pkb_run_id or a diagnostic run is already active. An in-process PickleballEngine.run mints its own id the same way and clears a self-minted id when that call returns. Java glue is fingerprinted by path, size, and modification time. A change compiles once with mvnw test-compile or gradlew testClasses, then restarts the worker. The classpath is pkb_dolt_run_classpath or the Workbench classpath.cache. --dry-run prints the plan and writes nothing. Full Gradle or Maven remains the CI build. Set pkb_dolt_log=run to log the worker. --engine forwards the same selection to the native engine CLI and does not start the Cucumber runner.
                """;
    }

    private static int trackCommand(Path project, List<String> tokens, boolean timeline, PrintStream out, PrintStream err) {
        try {
            DoltTrack.Filter filter = new DoltTrack.Filter(
                    option(tokens, "--entity="),
                    option(tokens, "--field="),
                    option(tokens, "--step-def="),
                    option(tokens, "--scenario="),
                    option(tokens, "--status="),
                    option(tokens, "--from="),
                    option(tokens, "--to="),
                    option(tokens, "--cursor="),
                    integer(option(tokens, "--limit="), 50),
                    integer(option(tokens, "--offset="), 0),
                    option(tokens, "--before="),
                    option(tokens, "--tag="),
                    option(tokens, "--feature="),
                    first(option(tokens, "--run="), option(tokens, "--run-id=")),
                    option(tokens, "--text="),
                    has(tokens, "--latest"));
            String body = timeline
                    ? DoltTrack.timeline(project, filter, json(tokens))
                    : DoltTrack.query(project, filter, json(tokens));
            out.print(body);
            if (!body.endsWith("\n")) out.println();
            return 0;
        } catch (Exception failure) {
            err.println("error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            err.println(timeline ? "fix: dolt timeline --json" : "fix: dolt query --json");
            return 1;
        }
    }

    private static int retainCommand(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        try {
            int days = integer(option(tokens, "--days="), DoltHistory.days());
            int maxBytes = integer(option(tokens, "--max-bytes="), DoltHistory.maxBytes());
            DoltHistory.RetainResult result = DoltHistory.retain(project, days, maxBytes);
            out.println(result.line());
            boolean present = DoltPaths.ready(project);
            out.println("database " + (present ? "present" : "missing"));
            out.println("reports kept");
            return 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt history retain");
            return 1;
        }
    }

    private static int runCommand(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        if (has(tokens, "--engine")) {
            List<String> forwarded = new ArrayList<>();
            forwarded.add("engine");
            forwarded.add("run");
            forwarded.add("--dolt");
            forwarded.add("--project=" + project.toAbsolutePath().normalize());
            copyOption(tokens, forwarded, "--name");
            copyOption(tokens, forwarded, "--glue");
            copyOption(tokens, forwarded, "--feature");
            return tools.dscode.engine.EngineCli.execute(forwarded.toArray(String[]::new), out, err);
        }
        boolean dry = has(tokens, "--dry-run");
        List<String> extra = DoltRun.selection(
                option(tokens, "--tags="),
                option(tokens, "--name="),
                option(tokens, "--glue="),
                tokens
        );
        try {
            DoltRun.Outcome outcome = DoltRun.execute(project, extra, dry);
            if (!outcome.out().isEmpty()) out.print(outcome.out());
            if (!outcome.err().isEmpty()) err.print(outcome.err());
            if (!dry) {
                out.println("dolt run exit=" + outcome.exit() + " reused=" + outcome.reused()
                        + " millis=" + outcome.millis() + " " + outcome.plan().line());
            }
            return outcome.exit();
        } catch (Exception failure) {
            err.println("error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            err.println("fix: compile the project once, then dolt run --dry-run");
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
        boolean enabled;
        String source;
        try {
            enabled = DoltConfig.enabled(project);
            source = DoltConfig.source(project);
        } catch (IllegalArgumentException failure) {
            out.println("error: " + failure.getMessage());
            return 1;
        }
        Path db = DoltPaths.store(project);
        String shown = DoltPaths.display(project, db);
        boolean present = DoltPaths.ready(project);
        boolean driver = DoltDatabase.driverPresent();
        String enabledLine = "enabled=" + enabled + " source=" + source;
        if (!driver && enabled) {
            DoltLog.log(project, "status", "files", "missing-driver " + shown + " source=" + source);
            if (json) {
                out.println("{\"database\":\"" + (present ? "present" : "missing") + "\",\"path\":\""
                        + jsonEscape(shown) + "\",\"driver\":false,\"enabled\":" + enabled
                        + ",\"enabledSource\":\"" + jsonEscape(source) + "\",\"error\":\""
                        + jsonEscape(DoltDatabase.MISSING_DRIVER) + "\"}");
            } else {
                out.println(enabledLine);
                out.println(DoltDatabase.MISSING_DRIVER);
            }
            return 1;
        }
        DoltLog.log(project, "status", "files", (present ? "present " : "missing ") + shown + " source=" + source);
        List<DoltSources.Skip> skipped = discoverySkips(project);
        if (!present) {
            if (json) {
                out.println("{\"database\":\"missing\",\"path\":\"" + jsonEscape(shown) + "\",\"driver\":" + driver
                        + ",\"files\":0,\"enabled\":" + enabled + ",\"enabledSource\":\"" + jsonEscape(source) + "\""
                        + gitJson(project, null) + skipJson(skipped));
            } else {
                out.println(enabledLine);
                out.println("Dolt database missing: " + shown);
                if (!driver) out.println("store driver is not on the classpath");
                printGit(out, project, null);
                printSkips(out, skipped);
            }
            return 0;
        }
        if (!driver) {
            out.println(enabledLine);
            out.println("Dolt database missing: " + shown);
            out.println("store driver is not on the classpath");
            printSkips(out, skipped);
            return 1;
        }
        try (DoltDatabase database = DoltDatabase.open(project)) {
            int version = database.schemaVersion();
            int files = count(database, "SELECT COUNT(*) FROM files");
            int scenarios = count(database, "SELECT COUNT(*) FROM scenarios");
            int entities = count(database, "SELECT COUNT(*) FROM entities");
            int runs = count(database, "SELECT COUNT(*) FROM runs");
            int conflicts = DoltQuery.conflicts(project, database.connection()).size();
            int binaryRefs = count(database, "SELECT COUNT(*) FROM files WHERE binary_ref = 1");
            String hydrateCommit = tools.dscode.dolt.store.DoltDialect.lastHydrateCommit(database.connection());
            if (json) {
                out.println("{\"database\":\"present\",\"path\":\"" + jsonEscape(shown) + "\",\"schemaVersion\":"
                        + version + ",\"files\":" + files + ",\"scenarios\":" + scenarios
                        + ",\"entities\":" + entities + ",\"runs\":" + runs
                        + ",\"conflicts\":" + conflicts + ",\"driver\":true,\"enabled\":" + enabled
                        + ",\"enabledSource\":\"" + jsonEscape(source) + "\",\"binaryRefs\":" + binaryRefs
                        + ",\"hydrateCommit\":\"" + jsonEscape(hydrateCommit) + "\""
                        + gitJson(project, database) + skipJson(skipped));
            } else {
                out.println(enabledLine);
                out.println("Dolt schema " + version);
                out.println("files " + files);
                out.println("scenarios " + scenarios);
                out.println("entities " + entities);
                out.println("runs " + runs);
                out.println("conflicts " + conflicts);
                out.println("binaryRefs " + binaryRefs);
                printGit(out, project, database);
                printSkips(out, skipped);
                out.println("database " + shown);
                out.println("store=embedded");
                out.println("hydrate generation " + hydrateCommit);
            }
            return 0;
        } catch (SQLException failure) {
            out.println("error: " + failure.getMessage());
            return 1;
        }
    }

    private static String gitJson(Path project, DoltDatabase database) {
        DoltGit.Snapshot git = DoltGit.capture(project);
        int changed = changed(project, database);
        return ",\"git\":{\"commit\":\"" + jsonEscape(git.commit())
                + "\",\"branch\":\"" + jsonEscape(git.branch())
                + "\",\"remote\":\"" + jsonEscape(git.remote())
                + "\",\"githubRepo\":\"" + jsonEscape(git.githubRepo())
                + "\",\"dirty\":" + git.dirty()
                + ",\"changed\":" + changed + "}";
    }

    private static void printGit(PrintStream out, Path project, DoltDatabase database) {
        DoltGit.Snapshot git = DoltGit.capture(project);
        int changed = changed(project, database);
        out.println("git.commit " + git.commit());
        out.println("git.branch " + git.branch());
        out.println("git.remote " + git.remote());
        out.println("git.githubRepo " + git.githubRepo());
        out.println("git.dirty " + git.dirty());
        out.println("git.changed " + changed);
    }

    private static int changed(Path project, DoltDatabase database) {
        if (database == null) return 0;
        try {
            return DoltGit.changed(project, DoltFiles.index(database.connection()).values());
        } catch (SQLException failure) {
            return 0;
        }
    }

    private static List<DoltSources.Skip> discoverySkips(Path project) {
        try {
            return DoltSources.scan(project, List.of(), false).skipped();
        } catch (Exception failure) {
            return List.of();
        }
    }

    private static void printSkips(PrintStream out, List<DoltSources.Skip> skipped) {
        out.println("skipped " + skipped.size());
        int limit = Math.min(5, skipped.size());
        for (int index = 0; index < limit; index++) {
            DoltSources.Skip skip = skipped.get(index);
            out.println("sample " + skip.path() + " " + skip.reason());
        }
    }

    private static String skipJson(List<DoltSources.Skip> skipped) {
        StringBuilder json = new StringBuilder();
        json.append(",\"skipped\":").append(skipped.size()).append(",\"skipSamples\":[");
        int limit = Math.min(5, skipped.size());
        for (int index = 0; index < limit; index++) {
            if (index > 0) json.append(',');
            DoltSources.Skip skip = skipped.get(index);
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
            DoltHydrator.Result result = DoltHydrator.hydrate(
                    project, new DoltHydrator.Options(paths, force, dryRun));
            out.println(result.line());
            for (String notice : result.notices()) err.println(notice);
            for (String conflict : result.conflictPaths()) err.println("conflict: " + conflict);
            boolean present = DoltPaths.ready(project);
            out.println("database " + (present ? "present" : "missing"));
            return result.conflicts() > 0 ? 1 : 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt hydrate --dry-run");
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
            DoltExtractor.Result result = DoltExtractor.extract(project, new DoltExtractor.Options(paths, force));
            out.println(result.line());
            for (String conflict : result.conflictPaths()) err.println("conflict: " + conflict);
            boolean present = DoltPaths.ready(project);
            out.println("database " + (present ? "present" : "missing"));
            return result.conflicts() > 0 ? 1 : 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt extract");
            return 1;
        }
    }

    private static int files(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String action = action(tokens);
        try {
            if (action.isEmpty() || "list".equals(action)) {
                List<DoltQuery.FileSummary> files = DoltQuery.files(project);
                DoltLog.log(project, "query", "files", "list " + files.size());
                if (json) out.println(DoltQuery.filesJson(files));
                else {
                    out.println("files " + files.size());
                    for (DoltQuery.FileSummary file : files) {
                        out.println(file.path() + "  " + file.sourceType()
                                + "  edited=" + (file.edited() ? "yes" : "no")
                                + "  bytes=" + file.bytes());
                    }
                }
                return 0;
            }
            if ("show".equals(action)) {
                String path = arg(tokens, 2, "files show needs a relative path");
                DoltQuery.FileDetail detail = DoltQuery.file(project, path);
                if (detail == null) {
                    err.println("error: file not found: " + DoltPaths.normalizeRelative(path));
                    err.println("fix: dolt files list");
                    return 1;
                }
                if (detail.text() != null) {
                    detail = new DoltQuery.FileDetail(detail.summary(), FileRules.redact(detail.text()), detail.base64());
                }
                DoltLog.log(project, "query", detail.summary().path(), "show");
                if (json) out.println(DoltQuery.fileJson(detail));
                else printFile(out, detail);
                return 0;
            }
            err.println("error: Unknown Dolt files command: " + action);
            err.println("fix: dolt files list");
            return 2;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt files list");
            return 1;
        }
    }

    private static int features(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String action = action(tokens);
        try {
            if (action.isEmpty() || "list".equals(action)) {
                List<DoltQuery.FeatureSummary> features = DoltQuery.features(project);
                DoltLog.log(project, "query", "features", "list " + features.size());
                if (json) out.println(DoltQuery.featuresJson(features));
                else {
                    out.println("features " + features.size());
                    for (DoltQuery.FeatureSummary feature : features) {
                        out.println(feature.path() + "  " + feature.name() + "  scenarios=" + feature.scenarios());
                    }
                }
                return 0;
            }
            if ("show".equals(action)) {
                String path = arg(tokens, 2, "features show needs a relative path");
                DoltQuery.FeatureSummary feature = DoltQuery.feature(project, path);
                if (feature == null) {
                    err.println("error: feature not found: " + DoltPaths.normalizeRelative(path));
                    err.println("fix: dolt features list");
                    return 1;
                }
                DoltQuery.FileDetail detail = DoltQuery.file(project, feature.path());
                String text = detail == null || detail.text() == null ? "" : detail.text();
                List<DoltQuery.ScenarioRow> scenarios = DoltQuery.scenarios(project, feature.path());
                DoltLog.log(project, "query", feature.path(), "feature show");
                if (json) out.println(DoltQuery.featureJson(feature, text, scenarios));
                else {
                    out.println("path " + feature.path());
                    out.println("name " + feature.name());
                    out.println("language " + feature.language());
                    out.println("tags " + feature.tags());
                    out.println("scenarios " + feature.scenarios());
                    for (DoltQuery.ScenarioRow scenario : scenarios) {
                        out.println("scenario " + scenario.ord() + " " + scenario.keyword() + " " + scenario.name());
                    }
                    out.println("text");
                    out.print(text);
                    if (!text.endsWith("\n")) out.println();
                }
                return 0;
            }
            err.println("error: Unknown Dolt features command: " + action);
            err.println("fix: dolt features list");
            return 2;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt features list");
            return 1;
        }
    }

    private static int scenarios(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String action = action(tokens);
        if ("put".equals(action) || "update".equals(action) || "edit".equals(action) || "set".equals(action)) {
            return rejectStructured(err);
        }
        if (!action.isEmpty() && !"list".equals(action)) {
            err.println("error: Unknown Dolt scenarios command: " + action);
            err.println("fix: dolt scenarios list");
            return 2;
        }
        try {
            List<DoltQuery.ScenarioRow> scenarios = DoltQuery.scenarios(project, option(tokens, "--feature="));
            DoltLog.log(project, "query", "scenarios", "list " + scenarios.size());
            if (json) out.println(DoltQuery.scenariosJson(scenarios));
            else {
                out.println("scenarios " + scenarios.size());
                for (DoltQuery.ScenarioRow scenario : scenarios) {
                    out.println(scenario.path() + "  " + scenario.ord() + "  " + scenario.keyword() + "  " + scenario.name());
                }
            }
            return 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt scenarios list");
            return 1;
        }
    }

    private static int steps(Path project, List<String> tokens, boolean json, PrintStream out, PrintStream err) {
        String action = action(tokens);
        if ("put".equals(action) || "update".equals(action) || "edit".equals(action) || "set".equals(action)) {
            return rejectStructured(err);
        }
        if (!action.isEmpty() && !"list".equals(action)) {
            err.println("error: Unknown Dolt steps command: " + action);
            err.println("fix: dolt steps list");
            return 2;
        }
        try {
            List<DoltQuery.StepRow> steps = DoltQuery.steps(
                    project, option(tokens, "--feature="), option(tokens, "--scenario="));
            DoltLog.log(project, "query", "steps", "list " + steps.size());
            if (json) out.println(DoltQuery.stepsJson(steps));
            else {
                out.println("steps " + steps.size());
                for (DoltQuery.StepRow step : steps) {
                    out.println(step.path() + "  " + step.scenario() + "  " + step.ord() + "  " + step.keyword() + "  " + step.text());
                }
            }
            return 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt steps list");
            return 1;
        }
    }

    private static int conflicts(Path project, boolean json, PrintStream out, PrintStream err) {
        try {
            List<DoltQuery.Conflict> conflicts = DoltQuery.conflicts(project);
            DoltLog.log(project, "query", "conflicts", "list " + conflicts.size());
            if (json) out.println(DoltQuery.conflictsJson(conflicts));
            else {
                out.println("conflicts " + conflicts.size());
                for (DoltQuery.Conflict conflict : conflicts) out.println(conflict.message());
            }
            return conflicts.isEmpty() ? 0 : 1;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt conflicts");
            return 1;
        }
    }

    private static void printFile(PrintStream out, DoltQuery.FileDetail detail) {
        DoltQuery.FileSummary file = detail.summary();
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
            err.println("error: Unknown Dolt feature command: " + (action.isEmpty() ? "feature" : action));
            err.println("fix: dolt feature put <relative-path> --file=<path>");
            return 2;
        }
        try {
            String path = arg(tokens, 2, "feature put needs a relative path");
            if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".feature")) {
                throw new IllegalArgumentException("feature put needs a .feature path: " + path);
            }
            byte[] raw = featureBytes(project, tokens);
            DoltFiles.Row row = DoltContent.put(project, path, raw);
            out.println("feature " + row.path() + " edited=1 bytes=" + row.raw().length);
            return 0;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt feature put <relative-path> --file=<path>");
            return 1;
        }
    }

    private static int rejectStructured(PrintStream err) {
        err.println("error: " + DoltContent.STRUCTURED_EDIT);
        err.println("fix: dolt feature put <relative-path> --file=<path>");
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
                    List<DoltHistory.Run> runs = DoltHistory.list(project);
                    if (runs.isEmpty()) out.println("runs 0");
                    for (DoltHistory.Run run : runs) {
                        out.println(run.id() + " " + run.status() + " " + run.startedAt()
                                + " passed=" + run.passed() + " failed=" + run.failed());
                    }
                    yield 0;
                }
                case "show" -> {
                    String id = arg(tokens, 2, "history show needs a run id");
                    DoltHistory.Run run = DoltHistory.find(project, id);
                    if (run == null) {
                        err.println("error: run not found: " + id);
                        err.println("fix: dolt history list");
                        yield 1;
                    }
                    out.println("run " + run.id() + " status " + run.status());
                    out.println("started " + run.startedAt());
                    out.println("scenario " + run.scenario());
                    out.println("passed " + run.passed());
                    out.println("failed " + run.failed());
                    if (!run.note().isBlank()) out.println("note " + run.note());
                    for (DoltHistory.LogLine line : DoltHistory.logs(project, id)) {
                        out.println(line.loggedAt() + " " + line.entity() + " " + line.message());
                    }
                    yield 0;
                }
                case "record" -> {
                    String from = option(tokens, "--from=");
                    DoltHistory.Draft imported = from == null ? null : DoltHistory.fromDiagnostic(project, from);
                    String id = historyId(tokens, imported);
                    DoltHistory.Run run = DoltHistory.record(project, new DoltHistory.Draft(
                            id,
                            first(option(tokens, "--started="), imported == null ? null : imported.startedAt()),
                            first(option(tokens, "--status="), imported == null ? null : imported.status()),
                            first(option(tokens, "--scenario="), imported == null ? null : imported.scenario()),
                            integer(option(tokens, "--passed="), imported == null ? 0 : imported.passed()),
                            integer(option(tokens, "--failed="), imported == null ? 0 : imported.failed()),
                            first(option(tokens, "--note="), imported == null ? "" : imported.note()),
                            first(option(tokens, "--entity="), imported == null ? "run" : imported.entity()),
                            option(tokens, "--message=")),
                            historyLinks(tokens));
                    out.println("run " + run.id() + " status " + run.status());
                    if (from != null) {
                        int artifacts = DoltArtifacts.linkRetained(project, run.id(), from, null);
                        out.println("artifacts " + artifacts);
                    }
                    yield 0;
                }
                case "retain" -> {
                    int days = integer(option(tokens, "--days="), DoltHistory.days());
                    int maxBytes = integer(option(tokens, "--max-bytes="), DoltHistory.maxBytes());
                    DoltHistory.RetainResult result = DoltHistory.retain(project, days, maxBytes);
                    out.println(result.line());
                    boolean present = DoltPaths.ready(project);
                    out.println("database " + (present ? "present" : "missing"));
                    yield 0;
                }
                default -> {
                    err.println("error: Unknown Dolt history command: " + action);
                    err.println("fix: dolt help");
                    yield 2;
                }
            };
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt history list");
            return 1;
        }
    }

    private static List<DoltHistory.Link> historyLinks(List<String> tokens) {
        List<DoltHistory.Link> links = new ArrayList<>();
        for (String token : tokens) {
            if (!token.startsWith("--link=")) continue;
            String body = token.substring("--link=".length()).trim();
            int hash = body.indexOf('#');
            if (hash <= 0 || hash >= body.length() - 1 || body.indexOf('#', hash + 1) >= 0) {
                throw new IllegalArgumentException("history link must be Type#id");
            }
            links.add(new DoltHistory.Link(body.substring(0, hash), body.substring(hash + 1)));
        }
        return links;
    }

    private static String historyId(List<String> tokens, DoltHistory.Draft imported) {
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
                    List<DoltEntities.Entity> entities = DoltEntities.list(project);
                    if (entities.isEmpty()) out.println("entities 0");
                    for (DoltEntities.Entity entity : entities) {
                        out.println(entity.type() + " " + entity.id() + " " + entity.version());
                    }
                    yield 0;
                }
                case "get" -> {
                    String type = arg(tokens, 2);
                    String id = arg(tokens, 3);
                    DoltEntities.Entity entity = DoltEntities.get(project, type, id);
                    if (entity == null) {
                        err.println("error: entity not found: " + type + " " + id);
                        err.println("fix: dolt entity list");
                        yield 1;
                    }
                    out.write(DoltEntities.canonicalFile(entity.type(), entity.id(), entity.version(), entity.payloadJson()));
                    yield 0;
                }
                case "put" -> {
                    String type = arg(tokens, 2);
                    String id = arg(tokens, 3);
                    String payload = payload(project, tokens);
                    Integer version = version(tokens);
                    DoltEntities.Entity entity = DoltEntities.put(project, type, id, version, payload);
                    out.println("entity " + entity.type() + " " + entity.id() + " version " + entity.version());
                    yield 0;
                }
                case "remove" -> {
                    String type = arg(tokens, 2);
                    String id = arg(tokens, 3);
                    if (!DoltPaths.ready(project)) {
                        err.println("error: entity not found: " + type + " " + id);
                        yield 1;
                    }
                    boolean removed = DoltEntities.remove(project, type, id, force);
                    if (!removed) {
                        err.println("error: entity not found: " + type + " " + id);
                        yield 1;
                    }
                    out.println("removed " + type + " " + id);
                    yield 0;
                }
                case "export" -> {
                    DoltEntities.ExportResult result = DoltEntities.export(project, force);
                    out.println(result.line());
                    for (String conflict : result.conflictPaths()) err.println("conflict: " + conflict);
                    yield result.conflicts() > 0 ? 1 : 0;
                }
                case "use" -> {
                    String type = arg(tokens, 2);
                    String id = arg(tokens, 3);
                    DoltEntities.Usage usage = DoltEntities.note(
                            project,
                            option(tokens, "--step="),
                            type,
                            id,
                            option(tokens, "--field="),
                            option(tokens, "--operation="),
                            option(tokens, "--value-ref="));
                    out.println("usage " + usage.usageId() + " " + usage.operation() + " "
                            + usage.type() + "#" + usage.id()
                            + " field=" + (usage.field() == null ? "" : usage.field())
                            + " ref=" + usage.valueRef());
                    yield 0;
                }
                default -> {
                    err.println("error: Unknown Dolt entity command: " + action);
                    err.println("fix: dolt help");
                    yield 2;
                }
            };
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: dolt help");
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

    private static int count(DoltDatabase database, String sql) throws SQLException {
        try (var statement = database.connection().createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void printFailure(PrintStream err, String message, String fix) {
        String text = message == null || message.isBlank() ? "Dolt command failed" : message;
        String error = text;
        String hint = fix;
        int split = text.indexOf('\n');
        if (split >= 0) {
            error = text.substring(0, split);
            String rest = text.substring(split + 1).trim();
            if (rest.startsWith("fix:")) hint = rest.substring(4).trim();
        }
        if (error.startsWith("error:")) error = error.substring(6).trim();
        err.println("error: " + error);
        err.println("fix: " + hint);
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
