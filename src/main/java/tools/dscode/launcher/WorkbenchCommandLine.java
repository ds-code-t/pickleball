package tools.dscode.launcher;

import tools.dscode.common.reporting.diagnostic.ReportRetentionPolicy;

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
            "export-guidance", "hint", "discover-hint", "discover", "confirm", "resolve-runvars",
            "short-log", "note", "inbox", "finish"
    );
    static final Set<String> SESSION_CLIENT_COMMANDS = Set.of(
            "isolate", "session-start", "execute-step", "status", "events", "stop", "kill",
            "open-scenario", "example", "play", "from-here", "pause",
            "insert-step", "update-step", "diagnostic-run", "save", "refresh",
            "session-sync", "worker-start", "worker-restart", "worker-stop"
    );

    private WorkbenchCommandLine() {
    }

    record Coordination(
            String runId,
            String agentId,
            String group,
            Integer sequence,
            String who,
            String why,
            String learned,
            String text,
            String inboxTo,
            String inboxFrom,
            boolean inboxWrite,
            boolean inboxList,
            boolean inboxTake,
            Integer limit
    ) {
        static Coordination empty() {
            return new Coordination(null, null, null, null, null, null, null, null, null, null, false, false, false, null);
        }
    }

    record Parsed(
            String command,
            Path project,
            Path outputDirectory,
            String tags,
            String name,
            String example,
            String retention,
            String[] forwarded,
            Coordination coordination
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
            return new Parsed("ui", cwd, null, null, null, null, null, new String[]{"ui", cwd.toString()}, Coordination.empty());
        }
        String command = args[0];
        if ("export-guidance".equals(command)) {
            Path output = args.length >= 2 && !isFlag(args[1])
                    ? Path.of(args[1])
                    : Path.of(".pickleball");
            return new Parsed(command, cwd, output, null, null, null, null, args.clone(), Coordination.empty());
        }

        String tags = null;
        String name = null;
        String example = null;
        String retention = null;
        String runId = null;
        String agentId = null;
        String group = null;
        Integer sequence = null;
        String who = null;
        String why = null;
        String learned = null;
        String text = null;
        String inboxTo = null;
        String inboxFrom = null;
        boolean inboxWrite = false;
        boolean inboxList = false;
        boolean inboxTake = false;
        Integer limit = null;
        String absorb = null;
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
                absorb = "name";
                continue;
            }
            if ("--name".equals(token) && index + 1 < args.length) {
                name = args[++index];
                absorb = "name";
                continue;
            }
            if (token.startsWith("--example=")) {
                example = blankToNull(token.substring("--example=".length()));
                absorb = example == null ? null : "example";
                continue;
            }
            if ("--example".equals(token) && index + 1 < args.length) {
                example = blankToNull(args[++index]);
                absorb = example == null ? null : "example";
                continue;
            }
            if (token.startsWith("--retention=")) {
                retention = parseRetention(token.substring("--retention=".length()));
                continue;
            }
            if ("--retention".equals(token)) {
                String value = index + 1 < args.length ? args[++index] : null;
                retention = parseRetention(value);
                continue;
            }
            if (token.startsWith("--run-id=")) {
                runId = blankToNull(token.substring("--run-id=".length()));
                absorb = null;
                continue;
            }
            if ("--run-id".equals(token) && index + 1 < args.length) {
                runId = blankToNull(args[++index]);
                absorb = null;
                continue;
            }
            if (token.startsWith("--agent=") || token.startsWith("--agent-id=")) {
                agentId = blankToNull(token.substring(token.indexOf('=') + 1));
                absorb = null;
                continue;
            }
            if (("--agent".equals(token) || "--agent-id".equals(token)) && index + 1 < args.length) {
                agentId = blankToNull(args[++index]);
                absorb = null;
                continue;
            }
            if (token.startsWith("--group=")) {
                group = blankToNull(token.substring("--group=".length()));
                absorb = null;
                continue;
            }
            if ("--group".equals(token) && index + 1 < args.length) {
                group = blankToNull(args[++index]);
                absorb = null;
                continue;
            }
            if (token.startsWith("--sequence=")) {
                sequence = parseWholeNumber(token.substring("--sequence=".length()), "sequence");
                absorb = null;
                continue;
            }
            if ("--sequence".equals(token) && index + 1 < args.length) {
                sequence = parseWholeNumber(args[++index], "sequence");
                absorb = null;
                continue;
            }
            if (token.startsWith("--who=")) {
                who = blankToNull(token.substring("--who=".length()));
                absorb = who == null ? null : "who";
                continue;
            }
            if ("--who".equals(token) && index + 1 < args.length) {
                who = blankToNull(args[++index]);
                absorb = who == null ? null : "who";
                continue;
            }
            if (token.startsWith("--why=")) {
                why = blankToNull(token.substring("--why=".length()));
                absorb = why == null ? null : "why";
                continue;
            }
            if ("--why".equals(token) && index + 1 < args.length) {
                why = blankToNull(args[++index]);
                absorb = why == null ? null : "why";
                continue;
            }
            if (token.startsWith("--learned=")) {
                learned = blankToNull(token.substring("--learned=".length()));
                absorb = learned == null ? null : "learned";
                continue;
            }
            if ("--learned".equals(token) && index + 1 < args.length) {
                learned = blankToNull(args[++index]);
                absorb = learned == null ? null : "learned";
                continue;
            }
            if (token.startsWith("--text=")) {
                text = blankToNull(token.substring("--text=".length()));
                absorb = text == null ? null : "text";
                continue;
            }
            if ("--text".equals(token) && index + 1 < args.length) {
                text = blankToNull(args[++index]);
                absorb = text == null ? null : "text";
                continue;
            }
            if (token.startsWith("--to=")) {
                inboxTo = blankToNull(token.substring("--to=".length()));
                absorb = null;
                continue;
            }
            if ("--to".equals(token) && index + 1 < args.length) {
                inboxTo = blankToNull(args[++index]);
                absorb = null;
                continue;
            }
            if (token.startsWith("--from=")) {
                inboxFrom = blankToNull(token.substring("--from=".length()));
                absorb = null;
                continue;
            }
            if ("--from".equals(token) && index + 1 < args.length) {
                inboxFrom = blankToNull(args[++index]);
                absorb = null;
                continue;
            }
            if (token.startsWith("--limit=")) {
                limit = parseWholeNumber(token.substring("--limit=".length()), "limit");
                absorb = null;
                continue;
            }
            if ("--limit".equals(token) && index + 1 < args.length) {
                limit = parseWholeNumber(args[++index], "limit");
                absorb = null;
                continue;
            }
            if ("--write".equals(token)) {
                inboxWrite = true;
                absorb = null;
                continue;
            }
            if ("--list".equals(token)) {
                inboxList = true;
                absorb = null;
                continue;
            }
            if ("--take".equals(token)) {
                inboxTake = true;
                absorb = null;
                continue;
            }
            if (isFlag(token)) {
                rest.add(token);
                continue;
            }
            // Maven exec splits unquoted free-text flags into extra tokens.
            // The selector set most recently absorbs them.
            if (absorb != null && !looksLikeProject(token)) {
                switch (absorb) {
                    case "example" -> example = example + " " + token;
                    case "name" -> name = name + " " + token;
                    case "who" -> who = who + " " + token;
                    case "why" -> why = why + " " + token;
                    case "learned" -> learned = learned + " " + token;
                    case "text" -> text = text + " " + token;
                    default -> rest.add(token);
                }
                continue;
            }
            if (project == null && looksLikeProject(token)) {
                project = Path.of(token).toAbsolutePath().normalize();
                continue;
            }
            rest.add(token);
        }
        if (project == null) project = cwd;
        Coordination coordination = new Coordination(
                runId, agentId, group, sequence, who, why, learned, text,
                inboxTo, inboxFrom, inboxWrite, inboxList, inboxTake, limit
        );

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
            if (example != null && !example.isBlank()) {
                forwarded.add("--example");
                forwarded.add(example);
            }
            forwarded.addAll(rest);
            return new Parsed(
                    command, project, null, tags, name, example, retention, forwarded.toArray(String[]::new),
                    coordination
            );
        }
        return new Parsed(command, project, null, tags, name, example, retention, args.clone(), coordination);
    }

    private static Integer parseWholeNumber(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " requires a whole number.");
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + " must be a whole number: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String parseRetention(String value) {
        return ReportRetentionPolicy.parseExact(value).name().toLowerCase(Locale.ROOT);
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
