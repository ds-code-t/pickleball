package tools.dscode.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.store.DeltaLog;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreScope;
import tools.dscode.store.StoreSecrets;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqlStore;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code engine map show} and {@code engine map set}.
 * A set writes a {@code kind=map} delta on the current fork. It does not
 * modify the original run. Show prints names, value references, and parent
 * names for that run and step. A secret value is {@code ~MASK;}.
 */
public final class EngineMap {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EngineMap() {
    }

    public static int execute(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        String action = tokens.size() < 2 || tokens.get(1).startsWith("--") ? "" : tokens.get(1);
        try {
            StoreScope.bindCommand(tokens);
            return switch (action) {
                case "show" -> show(project, tokens, out, err);
                case "set" -> set(project, tokens, out, err);
                default -> {
                    err.println("error: Unknown engine command: map " + action);
                    err.println("fix: engine help");
                    yield 2;
                }
            };
        } catch (RuntimeException failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: engine help");
            return 2;
        } catch (Exception failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: engine help");
            return 1;
        }
    }

    private static int show(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        String run = option(tokens, "--run=");
        Integer step = step(tokens);
        if (run == null || run.isBlank() || step == null) {
            err.println("error: map show needs --run=<id> and --step=<ord>");
            err.println("fix: engine map show --run=<id> --step=<ord>");
            return 2;
        }
        Map<String, String> entries = read(project, run, step);
        boolean json = tokens.contains("--json");
        if (json) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("boundRun", run);
            node.put("boundStep", step);
            ArrayNode list = node.putArray("entries");
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                ObjectNode one = list.addObject();
                one.put("name", entry.getKey());
                one.put("value", mask(project, entry.getKey(), entry.getValue()));
                one.put("parent", "");
            }
            out.println(MAPPER.writeValueAsString(node));
            return 0;
        }
        out.println("boundRun " + run);
        out.println("boundStep " + step);
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            out.println("name " + entry.getKey());
            out.println("value " + mask(project, entry.getKey(), entry.getValue()));
            out.println("parent ");
        }
        return 0;
    }

    private static int set(Path project, List<String> tokens, PrintStream out, PrintStream err) throws Exception {
        if (StoreScope.current().primary()) {
            err.println("error: map set writes on a fork");
            err.println("fix: store branch fork --from-run=<id>");
            return 2;
        }
        String run = option(tokens, "--run=");
        Integer step = step(tokens);
        String key = option(tokens, "--key=");
        String value = option(tokens, "--value=");
        if (run == null || run.isBlank() || step == null || key == null || key.isBlank() || value == null) {
            err.println("error: map set needs --run=<id> --step=<ord> --key=<k> --value=<v>");
            err.println("fix: engine map set --run=<id> --step=<ord> --key=<k> --value=<v>");
            return 2;
        }
        if (StoreSecrets.secretName(project, key) && !StoreSecrets.reference(value)) {
            err.println("error: secret key " + key + " refuses cleartext");
            err.println("fix: pass a reference such as <password:name> or ~MASK;");
            return 2;
        }
        var binding = StoreScope.current();
        if (!Files.isRegularFile(StorePaths.sandboxDatabase(project, binding.name()))) {
            err.println("error: unknown sandbox " + binding.name());
            err.println("fix: store branch fork --from-run=" + run);
            return 2;
        }
        try (SqlStore store = SqlStore.open(project, binding)) {
            Connection connection = store.session().connection();
            DeltaLog.map(connection, project, StoreScope.label(binding), run, step, key, value);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        } finally {
            SqlConnections.closeBranch(project, binding.branch());
        }
        out.println("map set");
        out.println("run " + run);
        out.println("step " + step);
        out.println("key " + key);
        return 0;
    }

    private static Map<String, String> read(Path project, String run, int step) throws Exception {
        var binding = StoreScope.current();
        Path database = binding.primary()
                ? StorePaths.mainDatabase(project)
                : StorePaths.sandboxDatabase(project, binding.name());
        Map<String, String> latest = new LinkedHashMap<>();
        if (!Files.isRegularFile(database)) return latest;
        try (SqlStore store = SqlStore.open(project, binding)) {
            for (DeltaLog.MapEdit edit : DeltaLog.mapEdits(store.session().connection())) {
                if (step != edit.step() || !run.equals(edit.run())) continue;
                latest.put(edit.key(), edit.value());
            }
        } finally {
            if (!binding.primary()) SqlConnections.closeBranch(project, binding.branch());
        }
        return latest;
    }

    static String mask(Path project, String key, String value) {
        if (StoreSecrets.secretName(project, key) || StoreSecrets.reference(value)) return "~MASK;";
        return value == null ? "" : value;
    }

    private static Integer step(List<String> tokens) {
        String raw = option(tokens, "--step=");
        if (raw == null || raw.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed < 1 ? null : parsed;
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String option(List<String> tokens, String prefix) {
        if (tokens == null) return null;
        for (String token : tokens) {
            if (token != null && token.startsWith(prefix)) return token.substring(prefix.length());
        }
        return null;
    }
}
