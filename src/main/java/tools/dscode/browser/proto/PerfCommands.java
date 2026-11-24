package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * E9: page metrics, Core Web Vitals, browser traces and heap tools. Vitals follow the published definitions
 * (web.dev/articles/lcp, /cls, /inp, /fcp, /ttfb) with pickleball's own PerformanceObserver script, no library.
 */
final class PerfCommands {
    static final String WORLD = "pkb-perf";

    /**
     * Installs observers once per document and keeps the running values on the world's own global:
     * LCP = the last largest-contentful-paint entry before input; CLS = the largest session window of layout shifts
     * without recent input (gap under 1 s, window under 5 s); INP = the worst interaction latency, or the 98th
     * percentile once there are 50 or more interactions; FCP = the first-contentful-paint entry; TTFB =
     * responseStart minus activationStart of the navigation entry. All in milliseconds except CLS.
     */
    static final String VITALS_JS = "(()=>{if(globalThis.__pkbVitals)return;const v=globalThis.__pkbVitals={lcp:null,cls:0,inp:null,fcp:null,ttfb:null,url:location.href,interactions:0};"
            + "const po=(type,cb,o)=>{try{new PerformanceObserver(l=>l.getEntries().forEach(cb)).observe(Object.assign({type:type,buffered:true},o||{}))}catch(e){}};"
            + "let input=false;addEventListener('pointerdown',()=>{input=true},{capture:true,once:true});addEventListener('keydown',()=>{input=true},{capture:true,once:true});"
            + "po('largest-contentful-paint',e=>{if(!input)v.lcp=Math.round(e.renderTime||e.loadTime||e.startTime)});"
            + "let sv=0,se=[];po('layout-shift',e=>{if(e.hadRecentInput)return;const f=se[0],l=se[se.length-1];"
            + "if(se.length&&e.startTime-l.startTime<1000&&e.startTime-f.startTime<5000){sv+=e.value;se.push(e)}else{sv=e.value;se=[e]}if(sv>v.cls)v.cls=Math.round(sv*10000)/10000});"
            + "const ia={};po('event',e=>{if(!e.interactionId)return;ia[e.interactionId]=Math.max(ia[e.interactionId]||0,e.duration);"
            + "const d=Object.values(ia).sort((a,b)=>b-a);v.interactions=d.length;v.inp=Math.round(d[Math.min(d.length-1,Math.floor(d.length/50))])},{durationThreshold:16});"
            + "po('paint',e=>{if(e.name==='first-contentful-paint')v.fcp=Math.round(e.startTime)});"
            + "po('navigation',e=>{v.ttfb=Math.round(Math.max(0,e.responseStart-(e.activationStart||0)))});})()";
    static final String READ_JS = "(async()=>{" + VITALS_JS + ";await new Promise(r=>setTimeout(r,250));const v=globalThis.__pkbVitals;v.url=location.href;return JSON.stringify(v)})()";
    static final String METRICS_JS = "JSON.stringify((()=>{const n=performance.getEntriesByType('navigation')[0]||{};const m=performance.memory||{};"
            + "return {domContentLoadedMs:Math.round(n.domContentLoadedEventEnd||0),loadMs:Math.round(n.loadEventEnd||0),responseStartMs:Math.round(n.responseStart||0),"
            + "transferSize:n.transferSize||0,resources:performance.getEntriesByType('resource').length,usedJSHeapSize:m.usedJSHeapSize||null,totalJSHeapSize:m.totalJSHeapSize||null}})())";

    private PerfCommands() {
    }

    static void register() {
        ProtoHost.register("perf", PerfCommands::perf);
        ProtoHost.register("memory", PerfCommands::memory);
    }

    static ObjectNode perf(ProtoHost host, Map<String, String> f) throws Exception {
        if (f.containsKey("metrics")) return metrics(host, f);
        if (f.containsKey("vitals")) return vitals(host, f);
        if (f.containsKey("trace-start")) return traceStart(host, f);
        if (f.containsKey("trace-stop")) return traceStop(host, f);
        throw new IllegalArgumentException("error: perf needs --metrics, --vitals, --trace-start or --trace-stop --out=<file>\nfix: perf --vitals");
    }

    static ObjectNode metrics(ProtoHost host, Map<String, String> f) {
        BrowserProtocols.Served<ObjectNode> served = host.protocols().serve("perf", f.get("via"), backend -> {
            ObjectNode out = ProtoJson.obj();
            if (backend == Backend.CDP) {
                CdpSession.Target t = ContextCommands.target(host, f);
                host.cdp().send(t.sessionId, "Performance.enable", Map.of());
                ObjectNode m = out.putObject("metrics");
                for (JsonNode metric : host.cdp().send(t.sessionId, "Performance.getMetrics", Map.of()).path("metrics")) {
                    m.put(metric.path("name").asText(), metric.path("value").asDouble());
                }
            } else {
                out.set("metrics", read(StorageCommands.eval(host, backend, f, "(" + "()=>" + METRICS_JS + ")", null)));
            }
            return out;
        });
        return served.value().put("op", "perf").put("mode", "metrics").put("via", served.via().id());
    }

    static JsonNode read(String json) {
        try {
            return ProtoJson.MAPPER.readTree(json);
        } catch (Exception bad) {
            throw new IllegalStateException("error: the page returned no metrics\nfix: open a page first");
        }
    }

    /** Installs the observer script for new documents once per backend, then reads the values in its world. */
    static ObjectNode vitals(ProtoHost host, Map<String, String> f) {
        if (!host.stateMap().containsKey("events.source")) EventCommands.start(host, Map.of());
        BrowserProtocols.Served<String> served = host.protocols().serve("perf", f.get("via"), backend -> switch (backend) {
            case CDP -> {
                CdpSession.Target t = ContextCommands.target(host, f);
                if (host.stateMap().putIfAbsent("perf.preload.cdp." + t.sessionId, true) == null) {
                    host.cdp().send(t.sessionId, "Page.addScriptToEvaluateOnNewDocument", Map.of("source", VITALS_JS, "worldName", WORLD));
                    timeline(host, t.sessionId);
                }
                String frame = host.cdp().send(t.sessionId, "Page.getFrameTree", Map.of()).path("frameTree").path("frame").path("id").asText();
                int world = host.cdp().send(t.sessionId, "Page.createIsolatedWorld", Map.of("frameId", frame, "worldName", WORLD)).path("executionContextId").asInt();
                yield host.cdp().send(t.sessionId, "Runtime.evaluate", Map.of("expression", READ_JS, "contextId", world, "awaitPromise", true, "returnByValue", true))
                        .path("result").path("value").asText();
            }
            case BIDI -> {
                String context = ContextCommands.bidiContext(host, f);
                if (host.stateMap().putIfAbsent("perf.preload.bidi", true) == null) {
                    host.bidiModules().addPreloadScript("() => " + VITALS_JS, null, null, WORLD);
                }
                yield host.bidiModules().evaluate(context, READ_JS, WORLD, true).path("result").path("value").asText();
            }
            case PLAYWRIGHT -> host.pw().call(c -> {
                Page page = PwBridge.page(c, f.get("page"));
                // Playwright has no isolated world for init scripts; the observer runs in the page world.
                if (host.stateMap().putIfAbsent("perf.preload.pw", true) == null) page.context().addInitScript(VITALS_JS);
                return String.valueOf(page.evaluate(READ_JS));
            });
            default -> throw new UnsupportedProtocolOperation("perf", backend.id(), null);
        });
        JsonNode v = read(served.value());
        String url = v.path("url").asText();
        for (String metric : List.of("lcp", "cls", "inp", "fcp", "ttfb")) {
            if (v.path(metric).isNull() || v.path(metric).isMissingNode()) continue;
            ObjectNode row = host.hub().row(served.via().id(), "perf", "vital").put("metric", metric).put("url", url);
            row.set("value", v.get(metric));
            host.hub().add(row);
        }
        ObjectNode out = ProtoJson.obj().put("op", "perf").put("mode", "vitals").put("via", served.via().id()).put("url", url);
        out.set("vitals", v);
        return out;
    }

    /** CDP PerformanceTimeline events (LCP and layout shifts as the browser reports them) become kind=perf rows. */
    static void timeline(ProtoHost host, String session) {
        try {
            host.cdp().send(session, "PerformanceTimeline.enable", Map.of("eventTypes", List.of("largest-contentful-paint", "layout-shift")));
        } catch (RuntimeException unsupported) {
            return;
        }
        if (host.stateMap().putIfAbsent("perf.timeline.listen", true) != null) return;
        host.cdp().channel().listen((method, params, s) -> {
            if (!"PerformanceTimeline.timelineEventAdded".equals(method)) return;
            JsonNode e = params.path("event");
            ObjectNode row = host.hub().row("cdp", "perf", "timeline").put("metric", e.path("type").asText()).put("frame", e.path("frameId").asText());
            if (e.has("lcpDetails")) row.put("value", Math.round(e.path("lcpDetails").path("renderTime").asDouble() * 1000) / 1000.0);
            if (e.has("layoutShiftDetails")) row.put("value", e.path("layoutShiftDetails").path("value").asDouble());
            host.hub().add(row);
        });
    }

    // ---- tracing ----

    static ObjectNode traceStart(ProtoHost host, Map<String, String> f) {
        boolean playwright = "playwright".equals(f.get("format"));
        if (playwright) {
            host.pw().call(c -> {
                BrowserContext context = PwBridge.page(c, f.get("page")).context();
                Tracing.StartOptions options = new Tracing.StartOptions().setScreenshots(true).setSnapshots(true);
                if (f.containsKey("aria-snapshots")) options.setAriaSnapshots(true);
                if (f.containsKey("screen-snapshots")) options.setScreenSnapshots(true);
                context.tracing().start(options);
                return null;
            });
            host.stateMap().put("perf.trace", "playwright");
            host.log("perf trace-start format=playwright");
            return ProtoJson.obj().put("op", "perf").put("mode", "trace-start").put("via", "playwright").put("format", "playwright");
        }
        if (host.stateMap().containsKey("perf.trace")) throw new IllegalStateException("error: a trace is already running\nfix: perf --trace-stop --out=trace.json");
        String categories = f.getOrDefault("categories", "devtools.timeline,v8.execute,disabled-by-default-devtools.timeline,blink.user_timing,loading,latencyInfo");
        host.cdp().browser("Tracing.start", Map.of("transferMode", "ReturnAsStream", "traceConfig", Map.of("includedCategories", List.of(categories.split(",")))));
        host.stateMap().put("perf.trace", "cdp");
        host.log("perf trace-start format=chrome");
        return ProtoJson.obj().put("op", "perf").put("mode", "trace-start").put("via", "cdp").put("format", "chrome").put("categories", categories);
    }

    static ObjectNode traceStop(ProtoHost host, Map<String, String> f) throws Exception {
        Object running = host.stateMap().remove("perf.trace");
        if (running == null) throw new IllegalStateException("error: no trace is running\nfix: perf --trace-start, then perf --trace-stop --out=trace.json");
        Path out = ContextCommands.out(host, f.get("out"), ".pickleball/runs/" + host.run() + "/trace-" + System.currentTimeMillis() + ("playwright".equals(running) ? ".zip" : ".json"));
        if ("playwright".equals(running)) {
            host.pw().call(c -> {
                PwBridge.page(c, f.get("page")).context().tracing().stop(new Tracing.StopOptions().setPath(out));
                return null;
            });
        } else {
            CompletableFuture<String> stream = new CompletableFuture<>();
            tools.dscode.browser.RpcChannel.Listener done = (method, params, s) -> {
                if ("Tracing.tracingComplete".equals(method)) stream.complete(params.path("stream").asText());
            };
            host.cdp().channel().listen(done);
            String handle;
            try {
                host.cdp().browser("Tracing.end", Map.of());
                handle = stream.get(60, TimeUnit.SECONDS);
            } finally {
                host.cdp().channel().unlisten(done);
            }
            try (OutputStream file = Files.newOutputStream(out)) {
                while (true) {
                    JsonNode chunk = host.cdp().browser("IO.read", Map.of("handle", handle, "size", 1 << 20));
                    String data = chunk.path("data").asText("");
                    file.write(chunk.path("base64Encoded").asBoolean(false) ? Base64.getDecoder().decode(data) : data.getBytes(StandardCharsets.UTF_8));
                    if (chunk.path("eof").asBoolean(false)) break;
                }
            } finally {
                host.cdp().browser("IO.close", Map.of("handle", handle));
            }
        }
        host.log("perf trace-stop bytes=" + Files.size(out));
        return ProtoJson.obj().put("op", "perf").put("mode", "trace-stop").put("format", "playwright".equals(running) ? "playwright" : "chrome")
                .put("out", host.project().relativize(out.toAbsolutePath().normalize()).toString()).put("bytes", Files.size(out));
    }

    // ---- memory ----

    static ObjectNode memory(ProtoHost host, Map<String, String> f) throws Exception {
        if (f.containsKey("gc")) {
            BrowserProtocols.Served<String> served = host.protocols().serve("memory", f.get("via"), backend -> switch (backend) {
                case PLAYWRIGHT -> host.pw().call(c -> {
                    PwBridge.page(c, f.get("page")).requestGC();
                    return "playwright";
                });
                case CDP -> {
                    host.cdp().send(ContextCommands.target(host, f).sessionId, "HeapProfiler.collectGarbage", Map.of());
                    yield "cdp";
                }
                default -> throw new UnsupportedProtocolOperation("memory", backend.id(), null);
            });
            host.log("memory gc via=" + served.value());
            return ProtoJson.obj().put("op", "memory").put("mode", "gc").put("via", served.value());
        }
        if (!f.containsKey("snapshot")) throw new IllegalArgumentException("error: memory needs --gc or --snapshot --out=<file.heapsnapshot>\nfix: memory --snapshot --out=heap.heapsnapshot --max-mb=256");
        long cap = Math.max(1, Long.parseLong(f.getOrDefault("max-mb", "512"))) * 1024 * 1024;
        Path out = ContextCommands.out(host, f.get("out"), ".pickleball/runs/" + host.run() + "/heap-" + System.currentTimeMillis() + ".heapsnapshot");
        CdpSession.Target t = ContextCommands.target(host, f);
        AtomicLong written = new AtomicLong();
        AtomicBoolean truncated = new AtomicBoolean();
        try (OutputStream file = Files.newOutputStream(out)) {
            tools.dscode.browser.RpcChannel.Listener chunks = (method, params, s) -> {
                if (!"HeapProfiler.addHeapSnapshotChunk".equals(method) || !t.sessionId.equals(s)) return;
                byte[] bytes = params.path("chunk").asText("").getBytes(StandardCharsets.UTF_8);
                synchronized (file) {
                    if (truncated.get()) return;
                    long room = cap - written.get();
                    try {
                        if (bytes.length > room) {
                            file.write(bytes, 0, (int) Math.max(0, room));
                            written.addAndGet(Math.max(0, room));
                            truncated.set(true);
                        } else {
                            file.write(bytes);
                            written.addAndGet(bytes.length);
                        }
                    } catch (java.io.IOException failure) {
                        truncated.set(true);
                    }
                }
            };
            host.cdp().channel().listen(chunks);
            try {
                host.cdp().send(t.sessionId, "HeapProfiler.enable", Map.of());
                host.cdp().send(t.sessionId, "HeapProfiler.takeHeapSnapshot", Map.of("reportProgress", false));
                ContextCommands.sleep(200);
            } finally {
                host.cdp().channel().unlisten(chunks);
            }
        }
        host.log("memory snapshot bytes=" + written.get() + (truncated.get() ? " truncated" : ""));
        return ProtoJson.obj().put("op", "memory").put("mode", "snapshot").put("via", "cdp")
                .put("out", host.project().relativize(out.toAbsolutePath().normalize()).toString()).put("bytes", written.get())
                .put("truncated", truncated.get()).put("maxMb", cap / 1024 / 1024);
    }
}
