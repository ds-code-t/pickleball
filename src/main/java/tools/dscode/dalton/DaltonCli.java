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
                Usage: dalton hydrate --dry-run [--project=<dir>] [--path=<relative>] [--force]
                Files stay the source of truth. The database is .pickleball/dalton/dalton.db and is gitignored.
                status and hydrate --dry-run do not create a database.
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
            if (json) {
                out.println("{\"database\":\"present\",\"path\":\"" + shown + "\",\"schemaVersion\":"
                        + version + ",\"files\":" + files + ",\"driver\":true}");
            } else {
                out.println("Dalton schema " + version);
                out.println("files " + files);
                out.println("database " + shown);
            }
            return 0;
        } catch (SQLException failure) {
            out.println("error: " + failure.getMessage());
            return 1;
        }
    }

    private static int hydrate(Path project, List<String> tokens, PrintStream out, PrintStream err) {
        if (!has(tokens, "--dry-run")) {
            err.println("error: hydrate needs --dry-run until file hydration is enabled");
            err.println("fix: dalton hydrate --dry-run");
            return 2;
        }
        boolean present = Files.isRegularFile(DaltonPaths.database(project));
        DaltonLog.log(project, "hydrate", "files", "dry-run");
        out.println("dry-run");
        out.println("would write 0");
        out.println("database " + (present ? "present" : "missing"));
        return 0;
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
