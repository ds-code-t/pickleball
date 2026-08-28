package tools.dscode.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parses Maven-exec-friendly Workbench launcher arguments. */
final class WorkbenchCommandLine {
    static final Set<String> FORWARDED_COMMANDS = Set.of(
            "sync", "worker-check", "live-check", "ui", "mcp", "isolate", "session-start", "session"
    );
    static final Set<String> AGENT_CORE_COMMANDS = Set.of(
            "export-guidance", "hint", "discover-hint", "discover", "confirm"
    );
    static final Set<String> SESSION_CLIENT_COMMANDS = Set.of(
            "isolate", "session-start", "execute-step", "status", "events", "stop", "kill"
    );

    private WorkbenchCommandLine() {
    }

    record Parsed(
            String command,
            Path project,
            Path outputDirectory,
            String tags,
            String name,
            String[] forwarded
    ) {
    }

    static boolean isAgentCoreCommand(String command) {
        return command != null && AGENT_CORE_COMMANDS.contains(command);
    }

    static boolean isForwardedCommand(String command) {
        return command != null && FORWARDED_COMMANDS.contains(command);
    }

    static boolean isSessionClientCommand(String command) {
        return command != null && SESSION_CLIENT_COMMANDS.contains(command);
    }

    static Parsed parse(String[] args) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (args == null || args.length == 0) {
            return new Parsed("ui", cwd, null, null, null, new String[]{"ui", cwd.toString()});
        }
        String command = args[0];
        if ("export-guidance".equals(command)) {
            Path output = args.length >= 2 && !isFlag(args[1])
                    ? Path.of(args[1])
                    : Path.of(".pickleball");
            return new Parsed(command, cwd, output, null, null, args.clone());
        }

        String tags = null;
        String name = null;
        Path project = null;
        List<String> rest = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            String token = args[index];
            if (token == null) continue;
            if (token.startsWith("--tags=")) {
                tags = token.substring("--tags=".length());
                continue;
            }
            if ("--tags".equals(token) && index + 1 < args.length) {
                tags = args[++index];
                continue;
            }
            if (token.startsWith("--name=")) {
                name = token.substring("--name=".length());
                continue;
            }
            if ("--name".equals(token) && index + 1 < args.length) {
                name = args[++index];
                continue;
            }
            if (isFlag(token)) {
                rest.add(token);
                continue;
            }
            // Maven exec splits unquoted --name=The failing scenario into extra tokens.
            if (name != null && !looksLikeProject(token)) {
                name = name + " " + token;
                continue;
            }
            if (project == null && looksLikeProject(token)) {
                project = Path.of(token).toAbsolutePath().normalize();
                continue;
            }
            rest.add(token);
        }
        if (project == null) project = cwd;

        if (isForwardedCommand(command)) {
            List<String> forwarded = new ArrayList<>();
            forwarded.add(command);
            forwarded.add(project.toString());
            if (tags != null && !tags.isBlank()) {
                forwarded.add("--tags");
                forwarded.add(tags);
            }
            if (name != null && !name.isBlank()) {
                forwarded.add("--name");
                forwarded.add(name);
            }
            forwarded.addAll(rest);
            return new Parsed(command, project, null, tags, name, forwarded.toArray(String[]::new));
        }
        return new Parsed(command, project, null, tags, name, args.clone());
    }

    private static boolean isFlag(String token) {
        return token.startsWith("-") && token.length() > 1 && !looksLikePath(token);
    }

    private static boolean looksLikePath(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.startsWith("-d") || token.contains("/") || token.contains("\\");
    }

    private static boolean looksLikeProject(String token) {
        if (token == null || token.isBlank()) return false;
        if (".".equals(token) || "..".equals(token)) return true;
        if (token.contains("/") || token.contains("\\")) return true;
        return Files.isDirectory(Path.of(token));
    }
}
