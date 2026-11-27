package tools.dscode.launcher;

import tools.dscode.control.protocol.VerbArgs;
import tools.dscode.engine.EngineLog;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One in-process entry for a CLI verb. The launcher and {@code POST /v1/verb} both call
 * {@link #run}. Parsing stays in {@link WorkbenchCommandLine} and {@code StoreCli}.
 * This class does not spawn a shell.
 *
 * <p>Six verbs stay open until the stream ends when {@code --follow} is set:
 * {@code browser-events}, {@code observe}, {@code log} tail, {@code domengine session log},
 * {@code chat}, and {@code record}. {@code --once} drops {@code --follow} before the
 * verb runs, so that call returns. There is no socket transport.</p>
 *
 * <p>After each verb, {@link CrossStore#journal} writes one session-journal row (verb and
 * args hash) unless {@code pkb_journal=off}. A journal failure does not change the exit code.</p>
 *
 * <p>Every call needs {@code --session=<id>} or the default session written by
 * {@code spine session new}, except the keys in {@link #SESSION_EXEMPT}. A missing file
 * exits 2 with {@code error: missing session} and {@code fix: spine session new}.
 * {@code spine session new} is the call that creates it. Dispatch does not create a session.
 * {@code session list}, {@code session info}, {@code session hold}, and {@code session release}
 * are the descriptor queries. The captured argv still starts with {@code session}, so those
 * four run here after the gate. A bare {@code session} stays the forwarded controller launch.
 * Dan, 2026-11-26 ({@code prompts/build-3.0.0-spine-R6.md} phase P0): {@code engine help},
 * {@code domengine version}, family help, and plain {@code export-guidance} are exempt.
 * {@code export-guidance --warm} is not.</p>
 */
public final class VerbDispatch {
    /**
     * Dan, 2026-11-26. {@code prompts/build-3.0.0-spine-R6.md} phase P0.
     * Exact command keys that skip the session gate. {@code spine session new} stays on
     * {@link #createsSession}. {@code export-guidance --warm} is absent: that path starts
     * a controller and still needs a session. Only the families named here, plus
     * {@code domengine version} and plain {@code export-guidance}. A verb that merely
     * contains {@code help} is not exempt.
     */
    public static final Set<String> SESSION_EXEMPT = sessionExemptKeys();

    private VerbDispatch() {
    }

    public static int run(String verb, Map<String, String> flags, PrintStream out, PrintStream err) {
        PrintStream stderr = err == null ? System.err : err;
        String[][] journaled = new String[1][];
        int code = execute(verb, flags, out, stderr, journaled);
        CrossStore.journal(journaled[0], code, stderr);
        return code;
    }

    /** The verb body. {@link #run} writes one cross-store journal row after it returns. */
    private static int execute(String verb, Map<String, String> flags, PrintStream out, PrintStream err,
                               String[][] journaled) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        String name = verb == null ? "" : verb.trim();
        Map<String, String> use = flags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(flags);
        String[] argv = argvFor(name, use);
        journaled[0] = argv;
        boolean mentioned = ArgsFiles.mentions(argv);
        try {
            ArgsFiles.Expansion expansion = ArgsFiles.expand(argv);
            argv = expansion.argv();
            journaled[0] = argv;
            if (expansion.fromFile()) mentioned = true;
        } catch (IllegalArgumentException failure) {
            Path project = projectOf(use, argv);
            EngineLog.log(project, "dispatch", name.isBlank() ? "-" : name, "file=" + (mentioned ? "yes" : "no"));
            stderr.println(failure.getMessage());
            return 2;
        }
        Path project = resolveProject(use, argv);
        boolean fromFile = mentioned || use.containsKey("args-file") || "yes".equalsIgnoreCase(use.get("file"));
        EngineLog.log(project, "dispatch", name.isBlank() ? "-" : name, "file=" + (fromFile ? "yes" : "no"));
        if (argv.length == 0 || argv[0] == null || argv[0].isBlank()) {
            stderr.println("error: missing verb");
            stderr.println("fix: pickleball help");
            return 2;
        }
        if (!sessionExempt(argv)) {
            try {
                tools.dscode.common.agentcontrol.SpineSession.required(project, sessionId(use, argv));
            } catch (IllegalArgumentException failure) {
                stderr.println(failure.getMessage());
                return 2;
            }
        }
        String family = argv[0].trim();
        if ("store".equals(family)) return tools.dscode.store.StoreCli.execute(argv, stdout, stderr);
        if ("engine".equals(family)) return tools.dscode.engine.EngineCli.execute(argv, stdout, stderr);
        if ("domengine".equals(family)) return PickleballWorkbenchLauncher.runDomEngine(argv, stdout, stderr);
        if ("dashboard".equals(family)) return tools.dscode.store.dashboard.DashboardCli.execute(argv, stdout, stderr);
        if ("cross".equals(family)) return CrossStore.execute(argv, stdout, stderr);
        if ("spine".equals(family)) {
            if (spineSessionCommand(argv)) return WorkbenchAgentCommands.run(argv, stdout, stderr);
            return SpineCli.execute(argv, stdout, stderr);
        }
        if ("bidi".equals(family)) return BidiSessionForward.run(project, argv, stdout, stderr);
        if ("browser".equals(family)) return tools.dscode.browser.BrowserPorts.execute(argv, stdout, stderr);
        if ("log".equals(family) || "log-tail".equals(family)) {
            return tools.dscode.common.composite.DomLogCli.execute(argv, stdout, stderr);
        }
        if (WorkbenchCommandLine.isAgentCoreCommand(family)) {
            return WorkbenchAgentCommands.run(argv, stdout, stderr);
        }
        if (WorkbenchCommandLine.isSessionClientCommand(family)) {
            return WorkbenchSessionCommands.run(argv, stdout, stderr);
        }
        if (sessionDescriptorQuery(argv)) {
            return WorkbenchAgentCommands.run(argv, stdout, stderr);
        }
        stderr.println("error: unknown verb " + family);
        stderr.println("fix: pickleball help");
        return 2;
    }

    /** Argv after {@code --once} drops {@code --follow} on a stream verb. Visible to tests. */
    static String[] argvFor(String verb, Map<String, String> flags) {
        Map<String, String> use = flags == null ? new LinkedHashMap<>() : new LinkedHashMap<>(flags);
        boolean once = truthy(use.get("once"));
        boolean stream = isStream(verb, use);
        if (once && stream) {
            use.remove("follow");
            use.remove("once");
        }
        if (use.containsKey(VerbArgs.ARGV)) {
            String[] argv = VerbArgs.decode(use.get(VerbArgs.ARGV));
            return once && stream ? stripHold(argv) : argv;
        }
        return VerbArgs.toArgv(verb, use);
    }

    static boolean isStream(String verb, Map<String, String> flags) {
        if (!followed(flags)) return false;
        String name = verb == null ? "" : verb.trim();
        if ("browser-events".equals(name) || "observe".equals(name) || "chat".equals(name)
                || "record".equals(name) || "log-tail".equals(name)) {
            return true;
        }
        List<String> positionals = positionals(flags);
        if ("log".equals(name)) return positionals.contains("tail");
        if ("domengine".equals(name)) {
            int session = positionals.indexOf("session");
            return session >= 0 && session + 1 < positionals.size() && "log".equals(positionals.get(session + 1));
        }
        return false;
    }

    private static boolean followed(Map<String, String> flags) {
        if (flags == null) return false;
        if (truthy(flags.get("follow"))) return true;
        String args = flags.get(VerbArgs.ARGS);
        if (args != null && (args.contains("--follow\n") || args.endsWith("--follow") || args.contains("--follow="))) {
            return true;
        }
        if (!flags.containsKey(VerbArgs.ARGV)) return false;
        for (String token : VerbArgs.decode(flags.get(VerbArgs.ARGV))) {
            if (token != null && (token.equals("--follow") || token.startsWith("--follow="))) return true;
        }
        return false;
    }

    private static List<String> positionals(Map<String, String> flags) {
        List<String> tokens = new ArrayList<>();
        if (flags == null) return tokens;
        if (flags.containsKey(VerbArgs.ARGV)) {
            String[] argv = VerbArgs.decode(flags.get(VerbArgs.ARGV));
            for (int index = 1; index < argv.length; index++) {
                String token = argv[index];
                if (token == null || token.startsWith("--")) continue;
                tokens.add(token);
            }
            return tokens;
        }
        String args = flags.get(VerbArgs.ARGS);
        if (args == null || args.isEmpty()) return tokens;
        for (String token : args.split("\n", -1)) {
            if (!token.isEmpty() && !token.startsWith("--")) tokens.add(token);
        }
        return tokens;
    }

    private static String[] stripHold(String[] argv) {
        List<String> kept = new ArrayList<>();
        if (argv != null) {
            for (String token : argv) {
                if (token == null) continue;
                if ("--follow".equals(token) || token.startsWith("--follow=")) continue;
                if ("--once".equals(token) || token.startsWith("--once=")) continue;
                kept.add(token);
            }
        }
        return kept.toArray(String[]::new);
    }

    private static boolean truthy(String value) {
        return value != null && ("true".equalsIgnoreCase(value.trim()) || value.isBlank()
                || "1".equals(value.trim()) || "yes".equalsIgnoreCase(value.trim()));
    }

    /** {@code --project} wins. A workbench positional path is the project when that flag is absent. */
    private static Path resolveProject(Map<String, String> flags, String[] argv) {
        if (namedProject(flags, argv)) return projectOf(flags, argv);
        Path positional = WorkbenchCommandLine.positionalProject(argv);
        if (positional != null) return positional;
        return projectOf(flags, argv);
    }

    private static boolean namedProject(Map<String, String> flags, String[] argv) {
        String named = flags == null ? null : flags.get("project");
        if (named != null && !named.isBlank()) return true;
        if (argv == null) return false;
        for (int index = 0; index < argv.length; index++) {
            String token = argv[index];
            if (token == null) continue;
            if (token.startsWith("--project=") || "--project".equals(token)) return true;
        }
        return false;
    }

    /** Blank means the default session. An explicit id must name a session file. */
    private static String sessionId(Map<String, String> flags, String[] argv) {
        String named = flags == null ? null : flags.get("session");
        if (named != null && !named.isBlank()) return named.trim();
        if (argv == null) return null;
        for (int index = 0; index < argv.length; index++) {
            String token = argv[index];
            if (token == null) continue;
            if (token.startsWith("--session=")) {
                String value = token.substring("--session=".length()).trim();
                return value.isEmpty() ? null : value;
            }
            if ("--session".equals(token) && index + 1 < argv.length && argv[index + 1] != null
                    && !argv[index + 1].startsWith("--")) {
                String value = argv[index + 1].trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    /**
     * {@code session list|info|hold|release} reads or updates the descriptor.
     * {@link WorkbenchAgentCommands} parses the original argv, which still starts
     * with {@code session}. A bare {@code session} is not this query.
     */
    private static boolean sessionDescriptorQuery(String[] argv) {
        if (argv == null || argv.length < 2 || argv[0] == null || !"session".equals(argv[0].trim())) return false;
        String verb = argv[1] == null ? "" : argv[1].trim();
        return "list".equals(verb) || "info".equals(verb) || "hold".equals(verb) || "release".equals(verb);
    }

    /** The first positional after {@code spine} is {@code session}. Warm verbs stay on {@link SpineCli}. */
    private static boolean spineSessionCommand(String[] argv) {
        if (argv == null || argv.length < 2 || argv[0] == null || !"spine".equals(argv[0].trim())) return false;
        for (int index = 1; index < argv.length; index++) {
            String token = argv[index];
            if (token == null || token.isBlank() || token.startsWith("--")) continue;
            return "session".equals(token);
        }
        return false;
    }

    /**
     * {@code spine session new}, or one exact key in {@link #SESSION_EXEMPT}.
     * {@code --warm} blocks the exemption so {@code export-guidance --warm} still needs a session.
     */
    private static boolean sessionExempt(String[] argv) {
        if (createsSession(argv)) return true;
        if (argv == null) return false;
        for (String token : argv) {
            if (token == null) continue;
            if ("--warm".equals(token) || token.startsWith("--warm=")) return false;
        }
        String key = exemptKey(argv);
        return key != null && SESSION_EXEMPT.contains(key);
    }

    /** Positionals joined, with {@code --help} or {@code -h} kept. Paths and other flags drop out. */
    private static String exemptKey(String[] argv) {
        List<String> positionals = new ArrayList<>();
        boolean helpFlag = false;
        boolean shortHelp = false;
        for (String token : argv) {
            if (token == null || token.isBlank()) continue;
            if ("--help".equals(token)) {
                helpFlag = true;
                continue;
            }
            if ("-h".equals(token)) {
                shortHelp = true;
                continue;
            }
            if (token.startsWith("--")) continue;
            if (token.indexOf('/') >= 0 || token.indexOf('\\') >= 0 || token.indexOf(':') >= 0) continue;
            positionals.add(token);
        }
        if (positionals.isEmpty()) return null;
        if ("export-guidance".equals(positionals.get(0))) return "export-guidance";
        String key = String.join(" ", positionals);
        if (helpFlag && !key.endsWith(" help") && !key.endsWith(" --help")) return key + " --help";
        if (shortHelp && !key.endsWith(" -h") && !key.endsWith(" help")) return key + " -h";
        return key;
    }

    /**
     * The allow-list. Forms are {@code <family> help}, {@code <family> --help},
     * {@code <family> -h}, {@code help <family>}, {@code --help <family>}, and {@code -h <family>}.
     */
    private static Set<String> sessionExemptKeys() {
        List<String> families = List.of(
                "store", "engine", "domengine", "dashboard", "cross",
                "spine", "bidi", "browser", "log", "log-tail");
        Set<String> keys = new LinkedHashSet<>();
        for (String family : families) {
            keys.add(family + " help");
            keys.add(family + " --help");
            keys.add(family + " -h");
            keys.add("help " + family);
            keys.add("--help " + family);
            keys.add("-h " + family);
        }
        keys.add("domengine version");
        keys.add("export-guidance");
        return Set.copyOf(keys);
    }

    /** {@code spine session new} is the call that creates the session this gate requires. */
    private static boolean createsSession(String[] argv) {
        if (argv == null || argv.length < 3 || argv[0] == null || !"spine".equals(argv[0].trim())) return false;
        boolean session = false;
        for (int index = 1; index < argv.length; index++) {
            String token = argv[index];
            if (token == null || token.isBlank() || token.startsWith("--")) continue;
            if (!session) {
                session = "session".equals(token);
                continue;
            }
            return "new".equals(token);
        }
        return false;
    }

    private static Path projectOf(Map<String, String> flags, String[] argv) {
        String named = flags == null ? null : flags.get("project");
        if (named != null && !named.isBlank()) return Path.of(named).toAbsolutePath().normalize();
        if (argv != null) {
            for (int index = 0; index < argv.length; index++) {
                String token = argv[index];
                if (token == null) continue;
                if (token.startsWith("--project=")) {
                    return Path.of(token.substring("--project=".length())).toAbsolutePath().normalize();
                }
                if ("--project".equals(token) && index + 1 < argv.length && argv[index + 1] != null) {
                    return Path.of(argv[index + 1]).toAbsolutePath().normalize();
                }
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }
}
