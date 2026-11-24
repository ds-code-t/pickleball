package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import tools.dscode.browser.BidiModules;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.DomMutations;
import tools.dscode.browser.RpcChannel;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E2b host commands: {@code dom.watch} feeds pushed DOM change batches into the event stream as
 * {@code kind=dom subkind=mutation}, on the backend the fallback order picks. Push channel per backend:
 * Playwright {@code BrowserContext.exposeBinding} + {@code addInitScript}; BiDi {@code script.addPreloadScript}
 * with a {@code channel} argument and {@code script.message}; CDP {@code Runtime.addBinding} +
 * {@code Page.addScriptToEvaluateOnNewDocument}. All three run the same {@code dom-mutations.js} observer.
 */
final class DomCommands {
    private DomCommands() {
    }

    static void register() {
        ProtoHost.register("dom.watch", DomCommands::watch);
        ProtoHost.register("dom.status", (host, f) -> status(host));
    }

    static ObjectNode status(ProtoHost host) {
        Object via = host.stateMap().get("dom.via");
        return ProtoJson.obj().put("op", "dom").put("watching", via != null).put("via", via == null ? "none" : String.valueOf(via))
                .put("mode", via == null ? "poll" : "push").put("cursor", host.hub().cursor());
    }

    static synchronized ObjectNode watch(ProtoHost host, Map<String, String> f) {
        Object existing = host.stateMap().get("dom.via");
        if (existing != null) {
            return status(host).put("started", false);
        }
        DomMutations.Options options = DomMutations.Options.of(Map.of(
                "selector", f.getOrDefault("selector", ""),
                "kinds", f.getOrDefault("dom-kinds", f.getOrDefault("kinds", "")).replace("dom", "").replaceAll("^,|,$", ""),
                "attributes", f.getOrDefault("attributes", ""),
                "text", f.getOrDefault("contains", ""),
                "debounce", f.getOrDefault("debounce", "50"),
                "own", f.getOrDefault("own", "")));
        String id = DomMutations.nextId();
        BrowserProtocols.Served<String> served = host.protocols().serve("dom-mutation", f.get("via"), backend -> {
            switch (backend) {
                case PLAYWRIGHT -> playwright(host, options, id);
                case BIDI -> bidi(host, options, id);
                case CDP -> cdp(host, options, id);
                default -> throw new UnsupportedProtocolOperation("dom-mutation", backend.id(), null);
            }
            return backend.id();
        });
        host.stateMap().put("dom.via", served.value());
        host.log("dom.watch via=" + served.value() + " selector=" + options.selector());
        return status(host).put("started", true).put("id", id).put("kinds", String.join(",", options.kinds()));
    }

    static void emit(BrowserEventHub hub, String source, String context, String payload, String id) {
        ObjectNode batch = DomMutations.parse(payload);
        if (batch == null || !id.equals(batch.path("id").asText())) return;
        ObjectNode row = hub.row(source, "dom", "mutation");
        if (context != null) row.put("context", context);
        row.put("url", batch.path("url").asText("")).put("text", DomMutations.summary(batch));
        ObjectNode dom = row.putObject("dom");
        for (String key : List.of("childList", "attributes", "text", "added", "removed", "records")) dom.put(key, batch.path(key).asInt(0));
        dom.set("targets", batch.path("targets"));
        if (batch.path("attributeNames").size() > 0) dom.set("attributeNames", batch.path("attributeNames"));
        long lag = batch.path("at").asLong(0) - batch.path("firstAt").asLong(0);
        if (lag >= 0) row.put("durationMs", lag);
        hub.add(row);
    }

    private static void cdp(ProtoHost host, DomMutations.Options options, String id) {
        CdpSession cdp = host.cdp();
        String binding = "pkbDomWatch_" + id;
        String expression = DomMutations.bindingExpression(options, id, binding);
        cdp.channel().listen((method, params, sessionId) -> {
            if (!"Runtime.bindingCalled".equals(method) || !binding.equals(params.path("name").asText())) return;
            CdpSession.Target target = cdp.target(sessionId);
            emit(host.hub(), "cdp", target == null ? null : target.targetId, params.path("payload").asText(""), id);
        });
        cdp.onTarget(target -> {
            if (!"page".equals(target.type)) return;
            cdp.send(target.sessionId, "Runtime.enable", Map.of());
            cdp.send(target.sessionId, "Runtime.addBinding", Map.of("name", binding));
            cdp.send(target.sessionId, "Page.enable", Map.of());
            cdp.send(target.sessionId, "Page.addScriptToEvaluateOnNewDocument",
                    Map.of("source", "document.addEventListener('DOMContentLoaded',function(){" + expression + "});"));
            cdp.send(target.sessionId, "Runtime.evaluate", Map.of("expression", expression));
        });
    }

    private static void bidi(ProtoHost host, DomMutations.Options options, String id) {
        RpcChannel channel = host.bidi();
        BidiModules bidi = host.bidiModules();
        String channelId = "pkb-dom-" + id;
        String fn = "(send)=>{var go=function(){(" + DomMutations.function() + ")(send," + DomMutations.config(options, id) + ");};"
                + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',go);else go();}";
        List<Object> arguments = List.of(BidiModules.p("type", "channel", "value", BidiModules.p("channel", channelId)));
        channel.listen((method, params, sessionId) -> {
            if (!"script.message".equals(method) || !channelId.equals(params.path("channel").asText())) return;
            JsonNode data = params.path("data");
            String payload = data.path("value").isTextual() ? data.path("value").asText() : data.toString();
            emit(host.hub(), "bidi", params.path("source").path("context").asText(null), payload, id);
        });
        bidi.subscribe(List.of("script.message"), null);
        JsonNode preload = bidi.addPreloadScript(fn, arguments, null, null);
        String script = preload.path("script").asText("");
        if (!script.isBlank()) host.onStop(() -> {
            try {
                bidi.removePreloadScript(script);
            } catch (RuntimeException ignored) {
                // The browser is gone.
            }
        });
        for (String context : topContexts(bidi)) bidi.callFunction(context, fn, arguments, null);
    }

    static List<String> topContexts(BidiModules bidi) {
        List<String> out = new ArrayList<>();
        for (JsonNode context : bidi.getTree().path("contexts")) out.add(context.path("context").asText());
        return out;
    }

    private static void playwright(ProtoHost host, DomMutations.Options options, String id) {
        String binding = "pkbDomWatch_" + id;
        String expression = DomMutations.bindingExpression(options, id, binding);
        host.pw().call(connection -> {
            for (BrowserContext context : connection.browser().contexts()) {
                context.exposeBinding(binding, (source, args) -> {
                    String payload = args.length == 0 ? "" : String.valueOf(args[0]);
                    emit(host.hub(), "playwright", null, payload, id);
                    return null;
                });
                context.addInitScript("document.addEventListener('DOMContentLoaded',function(){" + expression + "});");
                for (Page page : context.pages()) page.evaluate(expression);
            }
            return null;
        });
        // Binding callbacks are dispatched only while the bridge pumps Playwright's connection.
        host.pw().listening(true);
    }
}
