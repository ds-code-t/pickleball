package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.proto.ProtoClient;
import tools.dscode.control.protocol.SecretRedactor;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CLI front of the protocol host (E1–E11). Every verb here forwards its flags to the project's protocol host, which
 * picks the backend with {@code --via=auto|playwright|bidi|cdp|classic} and replies with {@code via=}. The host is
 * started on first use against {@code --target=browser:cdp=}, {@code --cdp-endpoint=}, {@code --bidi-endpoint=},
 * {@code pkb_cdp_endpoint}/{@code pkb_bidi_endpoint}, or this project's live browser.
 */
final class ProtoVerbs {
    /** CLI verbs that forward to a host command of the same name. */
    static final Set<String> VERBS = Set.of("browser-events");

    private ProtoVerbs() {
    }

    static boolean handles(String command) {
        return VERBS.contains(command);
    }

    static int run(String[] args, Path project, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? "" : args[0];
        Map<String, String> flags = PwCommand.flags(args);
        List<String> positional = PwCommand.positional(args);
        if (!positional.isEmpty()) flags.put("_args", String.join(" ", positional));
        try {
            if ("browser-events".equals(command)) return events(project, flags, out, err);
            return print(call(project, flags, command, flags), out, err);
        } catch (IllegalStateException | IllegalArgumentException failure) {
            return fail(err, failure.getMessage());
        }
    }

    static ObjectNode call(Path project, Map<String, String> flags, String verb, Map<String, String> payload) {
        String cdp = null;
        if (flags.containsKey("target") || flags.containsKey("cdp-endpoint")) cdp = PwCommand.endpoint(project, flags);
        if (cdp == null) cdp = BrowserProtocols.setting("pkb_cdp_endpoint");
        String bidi = flags.getOrDefault("bidi-endpoint", BrowserProtocols.setting("pkb_bidi_endpoint"));
        if (cdp == null && bidi == null && ProtoClient.running(project) == null) cdp = PwCommand.liveEndpoint(project);
        String run = flags.getOrDefault("run-id", System.getenv("PKB_RUN_ID"));
        Map<String, String> sent = new LinkedHashMap<>(payload);
        for (String endpointFlag : List.of("target", "cdp-endpoint", "bidi-endpoint")) sent.remove(endpointFlag);
        return ProtoClient.call(project, cdp, bidi, run, verb, sent);
    }

    private static int events(Path project, Map<String, String> flags, PrintStream out, PrintStream err) {
        if (flags.containsKey("stop")) {
            boolean stopped = ProtoClient.stop(project);
            out.println("{\"ok\":true,\"op\":\"browser-events\",\"stopped\":" + stopped + "}");
            return 0;
        }
        if (flags.containsKey("status")) return print(call(project, flags, "host.status", flags), out, err);
        if (flags.containsKey("start")) return print(call(project, flags, "events.start", flags), out, err);
        if (flags.containsKey("step") || flags.containsKey("scenario")) return print(call(project, flags, "events.context", flags), out, err);
        if (!flags.containsKey("follow")) return print(call(project, flags, "events.read", flags), out, err);
        // --follow: NDJSON rows as they arrive, then a cursor line. Runs in this CLI process only (never the start-host worker).
        long total = Math.min(120_000, Math.max(0, (long) EventLimits.parse(flags.get("wait-ms"), 10_000)));
        int limit = EventLimits.parse(flags.get("limit"), 0);
        long deadline = System.currentTimeMillis() + total;
        String since = flags.get("since");
        int printed = 0;
        String via = "none";
        do {
            Map<String, String> read = new LinkedHashMap<>(flags);
            read.remove("follow");
            if (since != null) read.put("since", since);
            read.put("wait-ms", Long.toString(Math.max(0, Math.min(5_000, deadline - System.currentTimeMillis()))));
            if (limit > 0) read.put("limit", Integer.toString(limit - printed));
            ObjectNode reply = call(project, flags, "events.read", read);
            if (!reply.path("ok").asBoolean(false)) return print(reply, out, err);
            via = reply.path("via").asText(via);
            for (JsonNode row : reply.path("rows")) {
                out.println(SecretRedactor.scrub(row.toString()));
                printed++;
            }
            since = Long.toString(reply.path("cursor").asLong());
            if (limit > 0 && printed >= limit) break;
        } while (System.currentTimeMillis() < deadline);
        out.println("{\"ok\":true,\"op\":\"browser-events\",\"via\":\"" + via + "\",\"cursor\":" + since + ",\"printed\":" + printed + "}");
        return 0;
    }

    static int print(ObjectNode reply, PrintStream out, PrintStream err) {
        if (!reply.path("ok").asBoolean(false)) {
            err.println("error: " + reply.path("error").asText("protocol command failed"));
            err.println("fix: " + reply.path("fix").asText("see help"));
            return 1;
        }
        out.println(SecretRedactor.scrub(reply.toString()));
        return 0;
    }

    private static int fail(PrintStream err, String message) {
        String text = message == null ? "error: protocol command failed" : message;
        int fix = text.indexOf("\nfix: ");
        err.println(text.startsWith("error: ") ? (fix < 0 ? text : text.substring(0, fix)) : "error: " + (fix < 0 ? text : text.substring(0, fix)));
        err.println(fix < 0 ? "fix: see help for this verb" : text.substring(fix + 1));
        return 1;
    }

    /** Small integer parsing for limits and waits. */
    static final class EventLimits {
        static int parse(String value, int fallback) {
            try {
                return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
            } catch (NumberFormatException bad) {
                return fallback;
            }
        }
    }
}
