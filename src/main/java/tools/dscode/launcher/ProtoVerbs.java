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
    static final Set<String> VERBS = Set.of("browser-events", "errors", "logpoint", "net", "net-body", "har",
            "net-route", "net-unroute", "net-block", "net-headers", "net-auth", "ws-route", "net-cache", "security", "csp", "emulate", "clock", "permissions",
            "context-new", "context-list", "context-close", "tab-new", "tab-activate", "page-history", "print", "storage", "storage-state",
            "downloads", "dialogs", "drop", "upload", "input", "find-role", "aria-snap", "aria-act", "webauthn", "extension", "webmcp", "perf", "memory");

    private ProtoVerbs() {
    }

    static boolean handles(String command) {
        return VERBS.contains(command);
    }

    static int run(String[] args, Path project, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? "" : args[0];
        Map<String, String> flags = PwCommand.flags(args);
        List<String> positional = PwCommand.positional(args, project);
        if (!positional.isEmpty()) flags.put("_args", String.join(" ", positional));
        try {
            if ("browser-events".equals(command)) return events(project, flags, out, err);
            if ("errors".equals(command)) {
                // E3: errors [--since] [--follow] is browser-events --kinds=error.
                flags.put("kinds", "error");
                return events(project, flags, out, err);
            }
            if ("net-auth".equals(command) && !flags.containsKey("clear")) {
                // E5: the secret ref is resolved here and sent only to the token-guarded loopback host; never printed.
                Map<String, String> payload = new LinkedHashMap<>(flags);
                payload.put("_credential", credential(flags.get("credential")));
                return print(call(project, flags, command, payload), out, err);
            }
            if ("perf".equals(command) && flags.containsKey("report")) {
                // E9: the Browser report section, read from the run's NDJSON spill; no browser needed.
                String run = flags.getOrDefault("run-id", flags.getOrDefault("run", "live"));
                Path folder = project.resolve(".pickleball").resolve("runs").resolve(run);
                int slowest = Integer.parseInt(flags.getOrDefault("slowest", "10"));
                Map<String, Object> summary = tools.dscode.browser.BrowserReport.summarize(folder, slowest);
                String spillRun = null;
                if (summary.isEmpty()) {
                    // HCR-14: the event host spills under the run id that started it; read that spill and say so.
                    String hostRun = hostRun(project);
                    if (hostRun != null && !hostRun.equals(run)) {
                        Map<String, Object> shared = tools.dscode.browser.BrowserReport.summarize(
                                project.resolve(".pickleball").resolve("runs").resolve(hostRun), slowest);
                        if (!shared.isEmpty()) {
                            summary = shared;
                            spillRun = hostRun;
                        }
                    }
                }
                if (summary.isEmpty()) return fail(err, "error: no browser-events spill in .pickleball/runs/" + run + "\nfix: run browser-events (or a scenario) first, or pass --run-id=<run>");
                java.nio.file.Files.createDirectories(folder.resolve("workbench"));
                java.nio.file.Files.writeString(folder.resolve("workbench").resolve("browser.html"), tools.dscode.browser.BrowserReport.html(summary));
                com.fasterxml.jackson.databind.node.ObjectNode reply = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(summary);
                reply.put("ok", true).put("op", "perf").put("mode", "report").put("html", ".pickleball/runs/" + run + "/workbench/browser.html");
                if (spillRun != null) {
                    reply.put("spillRun", spillRun);
                    reply.put("note", "the browser-events host spills under run " + spillRun + ", which started it; its rows were read");
                }
                out.println(reply);
                return 0;
            }
            if (command.startsWith("optional-") || "bridge".equals(command) || ("audit".equals(command) && flags.containsKey("lighthouse"))) {
                return optional(project, command, flags, positional, out, err);
            }
            if ("audit".equals(command)) {
                // E12: say how to install before starting a host.
                if (!flags.containsKey("axe")) return fail(err, "error: audit needs --axe or --lighthouse\nfix: audit --axe");
                tools.dscode.optional.OptionalAddons.require("axe");
            }
            if ("webauthn".equals(command) && flags.get("private-key") != null) {
                // E8: the private key ref is resolved here, sent only to the token-guarded loopback host, and never printed.
                Map<String, String> payload = new LinkedHashMap<>(flags);
                payload.remove("private-key");
                payload.put("_credential", secret(flags.get("private-key"), "webauthn --private-key=env:PASSKEY_PKCS8 (base64 PKCS#8)"));
                return print(call(project, flags, command, payload), out, err);
            }
            return print(call(project, flags, command, flags), out, err);
        } catch (IllegalStateException | IllegalArgumentException failure) {
            return fail(err, failure.getMessage());
        } catch (java.io.IOException failure) {
            return fail(err, "error: " + failure.getMessage() + "\nfix: check the run folder");
        }
    }

    /** E12: optional-install/-uninstall/-list, audit --lighthouse and bridge --devtools-mcp run in this process. */
    static int optional(Path project, String command, Map<String, String> flags, List<String> positional, PrintStream out, PrintStream err) throws java.io.IOException {
        String name = flags.getOrDefault("name", positional.isEmpty() ? null : positional.getFirst());
        switch (command) {
            case "optional-list" -> {
                out.println(tools.dscode.optional.OptionalAddons.list());
                return 0;
            }
            case "optional-install" -> {
                if (name == null) return fail(err, "error: optional-install needs a name\nfix: optional-install axe|lighthouse|devtools-mcp");
                ObjectNode reply = tools.dscode.optional.OptionalAddons.install(name, flags.get("version"), flags.get("integrity"), flags.containsKey("confirm"));
                out.println(reply);
                return 0;
            }
            case "optional-uninstall" -> {
                if (name == null) return fail(err, "error: optional-uninstall needs a name\nfix: optional-uninstall axe");
                out.println(tools.dscode.optional.OptionalAddons.uninstall(name));
                return 0;
            }
            case "bridge" -> {
                if (!flags.containsKey("devtools-mcp")) return fail(err, "error: bridge needs --devtools-mcp\nfix: bridge --devtools-mcp");
                if (flags.containsKey("stop")) {
                    out.println("{\"ok\":true,\"op\":\"bridge\",\"mode\":\"stop\",\"note\":\"the MCP client owns the chrome-devtools-mcp process; remove it from the client config or close the client\"}");
                    return 0;
                }
                java.nio.file.Path entry = tools.dscode.optional.OptionalAddons.require("devtools-mcp");
                String endpoint = flags.containsKey("endpoint") ? flags.get("endpoint") : PwCommand.endpoint(project, flags);
                if (!tools.dscode.optional.OptionalAddons.loopback(endpoint)) {
                    return fail(err, "error: bridge --devtools-mcp is localhost only\nfix: use a 127.0.0.1 or localhost endpoint");
                }
                List<String> args = tools.dscode.optional.OptionalAddons.devtoolsMcpArgs(tools.dscode.optional.OptionalAddons.node(), entry, endpoint);
                ObjectNode reply = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("ok", true).put("op", "bridge").put("mode", "devtools-mcp")
                        .put("version", tools.dscode.optional.OptionalAddons.installed("devtools-mcp"));
                ObjectNode server = reply.putObject("mcpConfig").putObject("mcpServers").putObject("chrome-devtools");
                server.put("command", args.getFirst());
                com.fasterxml.jackson.databind.node.ArrayNode list = server.putArray("args");
                args.subList(1, args.size()).forEach(list::add);
                server.putObject("env").put("CHROME_DEVTOOLS_MCP_NO_USAGE_STATISTICS", "1");
                reply.put("note", "the MCP client starts it over stdio with this config; CrUX lookups and usage statistics are off");
                out.println(SecretRedactor.scrub(reply.toString()));
                return 0;
            }
            default -> {
                return lighthouse(project, flags, out, err);
            }
        }
    }

    /** {@code audit --lighthouse}: the installed Lighthouse against this session's CDP port (Chromium only). */
    static int lighthouse(Path project, Map<String, String> flags, PrintStream out, PrintStream err) throws java.io.IOException {
        java.nio.file.Path cli = tools.dscode.optional.OptionalAddons.require("lighthouse");
        String endpoint = PwCommand.endpoint(project, flags);
        java.net.URI uri = java.net.URI.create(endpoint);
        if (!tools.dscode.optional.OptionalAddons.loopback(endpoint)) return fail(err, "error: audit --lighthouse is localhost only\nfix: use a local Chromium session");
        String url = flags.get("url");
        if (url == null) {
            JsonNode tabs = call(project, flags, "context-list", Map.of()).path("contexts").path(0).path("tabs");
            url = tabs.path(0).path("url").asText(null);
        }
        if (url == null || url.isBlank()) return fail(err, "error: no page to audit\nfix: audit --lighthouse --url=<page url>");
        java.nio.file.Path dir = project.resolve(flags.getOrDefault("out", ".pickleball/audit/lighthouse-" + System.currentTimeMillis()));
        java.nio.file.Files.createDirectories(dir);
        List<String> command = new java.util.ArrayList<>(List.of(tools.dscode.optional.OptionalAddons.node().toString(), cli.toString(), url,
                "--port=" + uri.getPort(), "--output=json", "--output=html", "--output-path=" + dir.resolve("lighthouse"), "--quiet",
                "--only-categories=" + flags.getOrDefault("categories", "performance,accessibility,best-practices,seo")));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String log = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) return fail(err, "error: lighthouse failed: " + log.strip().lines().findFirst().orElse("") + "\nfix: check the page loads, then retry");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return fail(err, "error: interrupted\nfix: retry");
        }
        ObjectNode reply = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("ok", true).put("op", "audit").put("mode", "lighthouse")
                .put("via", "cdp").put("url", url).put("out", dir.toString());
        out.println(SecretRedactor.scrub(reply.toString()));
        return 0;
    }

    /**
     * {@code --credential=env:NAME} (environment), {@code prop:NAME} (JVM system property), or {@code NAME} (a pkb
     * setting: system property, {@code pkb_runvars.NAME}, then environment). The value is {@code user:password}.
     */
    static String credential(String ref) {
        if (ref == null || ref.isBlank() || "true".equals(ref)) {
            throw new IllegalArgumentException("error: net-auth needs --credential=<secret-ref>\nfix: net-auth --credential=env:SITE_LOGIN (SITE_LOGIN=user:password)");
        }
        return secret(ref, "net-auth --credential=env:SITE_LOGIN (value user:password)");
    }

    /** Resolves a secret ref (env:, prop: or a pkb setting) and registers its value for redaction. */
    static String secret(String ref, String example) {
        if (ref == null || ref.isBlank() || "true".equals(ref)) {
            throw new IllegalArgumentException("error: needs a secret ref\nfix: " + example);
        }
        String value;
        if (ref.startsWith("env:")) value = System.getenv(ref.substring(4));
        else if (ref.startsWith("prop:")) value = System.getProperty(ref.substring(5));
        else value = BrowserProtocols.setting(ref);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("error: secret ref " + ref + " is not set\nfix: set it in the environment or as a JVM property (" + example + "); it is never printed");
        }
        SecretRedactor.note("password", value);
        return value;
    }

    /** The run id the running browser-events host was started with, or null. */
    static String hostRun(Path project) {
        try {
            Path file = tools.dscode.browser.proto.ProtoHost.hostFile(project);
            if (!java.nio.file.Files.isRegularFile(file)) return null;
            String value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.nio.file.Files.readString(file)).path("run").asText("");
            return value.matches("[A-Za-z0-9._-]{1,64}") ? value : null;
        } catch (java.io.IOException | RuntimeException failure) {
            return null;
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
        // HCR-14: the caller's run id, so per-run defaults (downloads dir) follow the caller, not the host's starter.
        if (run != null && !run.isBlank()) sent.putIfAbsent("_run", run.trim());
        return ProtoClient.call(project, cdp, bidi, run, verb, sent);
    }

    private static int events(Path project, Map<String, String> flags, PrintStream out, PrintStream err) {
        if (flags.containsKey("stop")) {
            boolean stopped = ProtoClient.stop(project);
            out.println("{\"ok\":true,\"op\":\"browser-events\",\"stopped\":" + stopped + "}");
            return 0;
        }
        if (flags.containsKey("status")) return print(call(project, flags, "host.status", flags), out, err);
        // E2b: --dom feeds pushed DOM change batches (kind=dom) into the stream; scope with --selector/--kinds/--debounce.
        if (flags.containsKey("dom")) return print(call(project, flags, "dom.watch", flags), out, err);
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
