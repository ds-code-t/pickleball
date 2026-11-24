package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.UnsupportedProtocolOperation;
import tools.dscode.optional.OptionalAddons;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E12: {@code audit --axe} runs the user-installed axe-core (see {@link OptionalAddons}) in an isolated world and
 * turns violations into {@code kind=audit subkind=a11y} rows plus a JSON file. The axe file is read from the user cache
 * and injected unmodified; pickleball never bundles it.
 */
final class AuditCommands {
    static final String WORLD = "pkb-axe";

    private AuditCommands() {
    }

    static void register() {
        ProtoHost.register("audit", AuditCommands::audit);
    }

    static ObjectNode audit(ProtoHost host, Map<String, String> f) throws Exception {
        if (!f.containsKey("axe")) throw new IllegalArgumentException("error: audit needs --axe (in the host) or --lighthouse\nfix: audit --axe");
        Path file = OptionalAddons.require("axe");
        String source = Files.readString(file);
        String scope = f.get("scope");
        String ref = f.get("ref");
        Map<String, Object> options = new LinkedHashMap<>();
        if (f.get("rules") != null) options.put("runOnly", Map.of("type", "rule", "values", java.util.List.of(f.get("rules").split(","))));
        String context = ref != null ? "document.querySelector('[data-pkb-ref=\"'+" + ProtoJson.MAPPER.writeValueAsString(ref) + "+'\"]')||document"
                : scope != null ? "document.querySelector(" + ProtoJson.MAPPER.writeValueAsString(scope) + ")||document" : "document";
        String run = source + "\n;(async()=>{const r=await axe.run(" + context + "," + ProtoJson.MAPPER.writeValueAsString(options)
                + ");return JSON.stringify({url:location.href,violations:r.violations||[]})})()";
        BrowserProtocols.Served<String> served = host.protocols().serve("audit", f.get("via"), backend -> switch (backend) {
            case CDP -> {
                CdpSession.Target t = ContextCommands.target(host, f);
                String frame = host.cdp().send(t.sessionId, "Page.getFrameTree", Map.of()).path("frameTree").path("frame").path("id").asText();
                int world = host.cdp().send(t.sessionId, "Page.createIsolatedWorld", Map.of("frameId", frame, "worldName", WORLD)).path("executionContextId").asInt();
                JsonNode r = host.cdp().send(t.sessionId, "Runtime.evaluate", Map.of("expression", run, "contextId", world, "awaitPromise", true, "returnByValue", true));
                if (r.has("exceptionDetails")) {
                    throw new IllegalStateException("error: axe failed: " + r.path("exceptionDetails").path("exception").path("description").asText(r.path("exceptionDetails").path("text").asText())
                            + "\nfix: optional-install axe --confirm (reinstall), or narrow with --scope=<css>");
                }
                yield r.path("result").path("value").asText();
            }
            case PLAYWRIGHT -> host.pw().call(c -> String.valueOf(PwBridge.page(c, f.get("page")).evaluate(run)));
            default -> throw new UnsupportedProtocolOperation("audit", backend.id(), null);
        });
        JsonNode result = ProtoJson.MAPPER.readTree(served.value());
        Path out = ContextCommands.out(host, f.get("out"), ".pickleball/audit/axe-" + System.currentTimeMillis() + ".json");
        Files.writeString(out, ProtoJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        ArrayNode summary = ProtoJson.MAPPER.createArrayNode();
        for (JsonNode v : result.path("violations")) {
            String impact = v.path("impact").asText("minor");
            ObjectNode row = host.hub().row("runtime", "audit", "a11y").put("method", v.path("id").asText()).put("text", v.path("help").asText())
                    .put("level", "critical".equals(impact) || "serious".equals(impact) ? "error" : "warning").put("url", result.path("url").asText())
                    .put("count", v.path("nodes").size());
            host.hub().add(row);
            summary.addObject().put("id", v.path("id").asText()).put("impact", impact).put("help", v.path("help").asText()).put("nodes", v.path("nodes").size());
        }
        host.log("audit axe via=" + served.via().id() + " violations=" + summary.size());
        ObjectNode reply = ProtoJson.obj().put("op", "audit").put("mode", "axe").put("via", served.via().id()).put("axe", OptionalAddons.installed("axe"))
                .put("count", summary.size()).put("out", out.toString());
        reply.set("violations", summary);
        return reply;
    }
}
