package tools.dscode.store.dashboard;

import tools.dscode.store.StorePaths;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code dashboard} writes one static HTML file from the embedded store.
 * It is a core CLI, like {@code store}. It does not start a server and it
 * does not change the store.
 */
public final class DashboardCli {
    private DashboardCli() {
    }

    public static boolean handles(String[] args) {
        return args != null && args.length > 0 && args[0] != null && "dashboard".equals(args[0].trim());
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!handles(args)) {
            stderr.println("error: expected the dashboard command");
            stderr.println("fix: dashboard");
            return 2;
        }
        List<String> tokens = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) tokens.add(args[index]);
        }
        if (tokens.stream().anyMatch(token -> "help".equals(token) || "--help".equals(token) || "-h".equals(token))) {
            stdout.print(help());
            return 0;
        }
        Path project = project(tokens);
        if ("query".equals(bare(tokens))) {
            return DashboardHistory.query(project, tokens, stdout, stderr);
        }
        Path destination = out(tokens, project);
        DashboardQuery query = new DashboardQuery(
                value(tokens, "--from"),
                value(tokens, "--to"),
                value(tokens, "--status"),
                value(tokens, "--feature"),
                value(tokens, "--run"),
                value(tokens, "--entity"),
                value(tokens, "--text")
        );
        try {
            DashboardSnapshot snapshot = new SqliteDashboardSource(project).read(query);
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, DashboardHtml.render(snapshot, linkBase(project, destination)),
                    StandardCharsets.UTF_8);
            stdout.println("dashboard=" + StorePaths.display(project, destination));
            stdout.println("runs=" + snapshot.runs().size());
            return 0;
        } catch (Exception failure) {
            stderr.println("error: " + failure.getMessage());
            stderr.println("fix: dashboard --project=<dir>");
            return 1;
        }
    }

    static String help() {
        return """
                Usage: dashboard [--project=<dir>] [--out=<file>] [--from=<iso>] [--to=<iso>] [--status=<status>] [--feature=<path>] [--run=<id>] [--entity=<Type#id>] [--text=<words>]
                Writes one static HTML page from the embedded store. It does not change the store and it does not start a server. The page has sticky filters, a colored timeline, clickable entity panels, and links grouped as Features, Data, Artifacts, and Commit.
                Usage: dashboard query [--project=<dir>] [--from=<iso>] [--to=<iso>] [--status=<status>] [--feature=<path>] [--run=<id>] [--entity=<Type#id>] [--text=<words>] [--element=<hint>] [--limit=<n>]
                Prints one JSON object of historical runs. Fields are name, ref, status, scenarios, artifacts, data, and summary. --element matches a step, scenario, artifact, entity, or log line. It does not write the store.
               
                """;
    }

    /** The project root as seen from the page's folder, so relative links open from the file. */
    static String linkBase(Path project, Path destination) {
        Path folder = destination.toAbsolutePath().normalize().getParent();
        Path root = project.toAbsolutePath().normalize();
        try {
            String relative = folder.relativize(root).toString().replace('\\', '/');
            return relative.isEmpty() ? "" : relative + "/";
        } catch (IllegalArgumentException differentRoot) {
            return root.toUri().toString();
        }
    }

    private static String bare(List<String> tokens) {
        for (String token : tokens) {
            if (token.startsWith("-")) continue;
            return token;
        }
        return "";
    }

    private static Path project(List<String> tokens) {
        String value = value(tokens, "--project");
        if (!value.isEmpty()) return Path.of(value).toAbsolutePath().normalize();
        return Path.of("").toAbsolutePath().normalize();
    }

    private static Path out(List<String> tokens, Path project) {
        String value = value(tokens, "--out");
        if (!value.isEmpty()) return Path.of(value).toAbsolutePath().normalize();
        return project.resolve(".pickleball").resolve("dashboard").resolve("index.html");
    }

    private static String value(List<String> tokens, String name) {
        String prefix = name + "=";
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith(prefix)) return token.substring(prefix.length()).trim();
            if (name.equals(token) && index + 1 < tokens.size()) return tokens.get(index + 1).trim();
        }
        return "";
    }
}
