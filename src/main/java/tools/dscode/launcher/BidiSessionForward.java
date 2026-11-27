package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.browser.SeleniumBidiCommands;
import tools.dscode.common.agentfast.AgentForward;
import tools.dscode.control.protocol.LiveSessionAttach;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * {@code bidi script} and {@code bidi intercept} run in the JVM that holds the
 * Selenium driver. The CLI uses the same controller hop and live-bridge post as
 * the other agent verbs. {@code bidi grid-attach} and {@code bidi help} stay here.
 */
public final class BidiSessionForward {
    private static final ObjectMapper JSON = new ObjectMapper();

    private BidiSessionForward() {
    }

    /** Hop to the controller when it is up, then {@link #deliver}. */
    public static int run(Path project, String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (forwards(args) && AgentHop.shouldHop("bidi", args)) {
            try {
                OptionalInt hopped = PickleballWorkbenchLauncher.controllerHop(args);
                if (hopped.isPresent()) return hopped.getAsInt();
            } catch (Exception failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                stderr.println("error: " + message);
                stderr.println("fix: controller-start");
                return 1;
            }
        }
        return deliver(project, args, stdout, stderr);
    }

    /**
     * Post to the live bridge when one is up. A failed post is the result.
     * No bridge runs the verb in this process.
     */
    public static int deliver(Path project, String[] args, PrintStream out, PrintStream err) {
        PrintStream stdout = out == null ? System.out : out;
        PrintStream stderr = err == null ? System.err : err;
        if (!forwards(args)) return SeleniumBidiCommands.execute(args, stdout, stderr);
        Path root = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        Optional<LiveSessionAttach.Attached> live = LiveSessionAttach.current(root);
        if (live.isEmpty()) return SeleniumBidiCommands.execute(args, stdout, stderr);
        List<String> extra = new ArrayList<>();
        for (int index = 1; index < args.length; index++) {
            if (args[index] != null) extra.add(args[index]);
        }
        AgentForward.Posted posted = AgentForward.post(
                live.get().bridge(),
                "bidi",
                Map.of(),
                extra,
                root,
                live.get().bridge().sessionId(),
                ""
        );
        if (posted.error() != null && !posted.error().isBlank()) {
            stderr.println(posted.error());
            return posted.exit() == 0 ? 1 : posted.exit();
        }
        return printPosted(posted, stdout, stderr);
    }

    static boolean forwards(String[] args) {
        if (args == null || args.length < 2 || args[1] == null) return false;
        String verb = args[1].trim();
        return "script".equals(verb) || "intercept".equals(verb);
    }

    private static int printPosted(AgentForward.Posted posted, PrintStream out, PrintStream err) {
        String stdout = "";
        String stderr = "";
        try {
            String json = posted.bodyJson() == null || posted.bodyJson().isBlank() ? "{}" : posted.bodyJson();
            JsonNode node = JSON.readTree(json);
            stdout = node.path("stdout").asText("");
            stderr = node.path("stderr").asText("");
            if (posted.exit() != 0 && stderr.isBlank()) {
                String error = node.path("error").asText("");
                if (!error.isBlank()) stderr = error.startsWith("error:") ? error + "\n" : "error: " + error + "\n";
            }
        } catch (Exception failure) {
            stdout = posted.bodyJson() == null ? "" : posted.bodyJson();
        }
        if (!stdout.isEmpty()) out.print(stdout.endsWith("\n") ? stdout : stdout + "\n");
        if (!stderr.isEmpty()) err.print(stderr.endsWith("\n") ? stderr : stderr + "\n");
        return posted.exit();
    }
}
