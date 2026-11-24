package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Screencast;
import com.microsoft.playwright.options.BindResult;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.RpcChannel;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E11: page screencasts (Playwright webm or frames, BiDi startScreencast where a browser has it, CDP JPEG frames to a
 * directory with no encoder) and share-browser (Playwright Browser.bind, local only).
 */
final class ScreencastCommands {
    private ScreencastCommands() {
    }

    static void register() {
        ProtoHost.register("screencast", ScreencastCommands::screencast);
        ProtoHost.register("share-browser", ScreencastCommands::share);
    }

    /** What a running screencast needs to stop and report. */
    static final class Running {
        String via;
        String form;
        Path path;
        final AtomicInteger frames = new AtomicInteger();
        Runnable stop = () -> { };
    }

    static ObjectNode screencast(ProtoHost host, Map<String, String> f) throws Exception {
        if (f.containsKey("stop")) return stop(host);
        if (!f.containsKey("start")) {
            throw new IllegalArgumentException("error: screencast needs --start [--out=<file.webm>|--frames-dir=<dir>] or --stop\nfix: screencast --start --frames-dir=frames");
        }
        if (host.stateMap().containsKey("screencast")) throw new IllegalStateException("error: a screencast is already running\nfix: screencast --stop");
        String outFile = f.get("out");
        boolean webm = outFile != null && !outFile.isBlank();
        if (webm && !outFile.endsWith(".webm")) throw new IllegalArgumentException("error: --out must be a .webm file\nfix: screencast --start --out=run.webm (or --frames-dir=<dir>)");
        Path target = webm ? ContextCommands.out(host, outFile, "screencast.webm")
                : ContextCommands.out(host, f.getOrDefault("frames-dir", ".pickleball/screencast/" + System.currentTimeMillis()) + "/x", "x").getParent();
        if (!webm) Files.createDirectories(target);
        int fps = EventCommands.parseInt(f.get("fps"), 0);
        int quality = EventCommands.parseInt(f.get("quality"), 80);
        BrowserProtocols.Served<Running> served = host.protocols().serve("screencast", webm ? InputCommands.playwright(f) : f.get("via"), backend -> {
            if (webm && backend != Backend.PLAYWRIGHT) {
                throw new UnsupportedProtocolOperation("screencast", backend.id(), "webm needs Playwright's encoder; use --frames-dir");
            }
            return switch (backend) {
                case PLAYWRIGHT -> playwright(host, f, target, webm, fps, quality);
                case BIDI -> bidi(host, f, target);
                case CDP -> cdp(host, f, target, quality);
                default -> throw new UnsupportedProtocolOperation("screencast", backend.id(), null);
            };
        });
        Running running = served.value();
        running.via = served.via().id();
        host.stateMap().put("screencast", running);
        host.log("screencast start via=" + running.via + " form=" + running.form + " path=" + host.project().relativize(running.path.toAbsolutePath()));
        return ProtoJson.obj().put("op", "screencast").put("mode", "start").put("via", running.via).put("form", running.form)
                .put("path", running.path.toString());
    }

    static Running playwright(ProtoHost host, Map<String, String> f, Path target, boolean webm, int fps, int quality) {
        Running running = new Running();
        running.form = webm ? "webm" : "frames";
        running.path = target;
        host.pw().call(c -> {
            Page page = PwBridge.page(c, f.get("page"));
            Screencast.StartOptions options = new Screencast.StartOptions();
            if (fps > 0) options.setFps(fps);
            if (webm) {
                options.setPath(target);
            } else {
                options.setQuality(quality);
                options.setOnFrame(frame -> write(target, running.frames.incrementAndGet(), frame.data()));
            }
            AutoCloseable recording;
            try {
                recording = page.screencast().start(options);
            } catch (RuntimeException failed) {
                try {
                    page.screencast().stop();
                } catch (RuntimeException notStarted) {
                    // Nothing to undo.
                }
                if (webm && String.valueOf(failed.getMessage()).contains("ffmpeg")) {
                    throw new IllegalStateException("error: webm recording needs Playwright's ffmpeg, which is not installed (pickleball never downloads it)\n"
                            + "fix: install it with the Playwright CLI (playwright install ffmpeg), or record frames with --frames-dir");
                }
                throw failed;
            }
            AutoCloseable actions = f.containsKey("show-actions") ? page.screencast().showActions() : null;
            running.stop = () -> host.pw().call(again -> {
                try {
                    if (actions != null) actions.close();
                    recording.close();
                } catch (Exception closed) {
                    page.screencast().stop();
                }
                return null;
            });
            return null;
        });
        return running;
    }

    static Running bidi(ProtoHost host, Map<String, String> f, Path target) {
        String context = ContextCommands.bidiContext(host, f);
        JsonNode started;
        try {
            started = host.bidi().call("browsingContext.startScreencast", Map.of("context", context));
        } catch (RuntimeException unsupported) {
            throw new UnsupportedProtocolOperation("screencast", "bidi", "browsingContext.startScreencast: " + firstLine(unsupported));
        }
        Running running = new Running();
        running.form = "frames";
        running.path = target;
        String id = started.path("screencast").asText(context);
        RpcChannel.Listener listener = (method, params, session) -> {
            if (method.endsWith("screencastFrame") && params.hasNonNull("data")) {
                write(target, running.frames.incrementAndGet(), Base64.getDecoder().decode(params.path("data").asText()));
            }
        };
        host.bidi().listen(listener);
        running.stop = () -> {
            try {
                host.bidi().call("browsingContext.stopScreencast", Map.of("screencast", id));
            } finally {
                host.bidi().unlisten(listener);
            }
        };
        return running;
    }

    static Running cdp(ProtoHost host, Map<String, String> f, Path target, int quality) {
        CdpSession.Target t = ContextCommands.target(host, f);
        Running running = new Running();
        running.form = "frames";
        running.path = target;
        RpcChannel.Listener listener = (method, params, sessionId) -> {
            if (!"Page.screencastFrame".equals(method) || !t.sessionId.equals(sessionId)) return;
            write(target, running.frames.incrementAndGet(), Base64.getDecoder().decode(params.path("data").asText()));
            host.cdp().send(t.sessionId, "Page.screencastFrameAck", Map.of("sessionId", params.path("sessionId").asInt()));
        };
        host.cdp().channel().listen(listener);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("format", "jpeg");
        params.put("quality", Math.max(1, Math.min(100, quality)));
        params.put("everyNthFrame", Math.max(1, EventCommands.parseInt(f.get("every-nth"), 1)));
        host.cdp().send(t.sessionId, "Page.startScreencast", params);
        running.stop = () -> {
            try {
                host.cdp().send(t.sessionId, "Page.stopScreencast", Map.of());
            } finally {
                host.cdp().channel().unlisten(listener);
            }
        };
        return running;
    }

    static ObjectNode stop(ProtoHost host) throws Exception {
        Object value = host.stateMap().remove("screencast");
        if (!(value instanceof Running running)) throw new IllegalStateException("error: no screencast is running\nfix: screencast --start --frames-dir=frames");
        running.stop.run();
        ObjectNode out = ProtoJson.obj().put("op", "screencast").put("mode", "stop").put("via", running.via).put("form", running.form)
                .put("path", running.path.toString());
        if ("webm".equals(running.form)) out.put("bytes", Files.exists(running.path) ? Files.size(running.path) : 0);
        else out.put("frames", running.frames.get());
        host.log("screencast stop via=" + running.via + " form=" + running.form + ("webm".equals(running.form) ? "" : " frames=" + running.frames.get()));
        return out;
    }

    static void write(Path dir, int n, byte[] data) {
        try {
            Files.write(dir.resolve(String.format("frame-%05d.jpg", n)), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String firstLine(Throwable t) {
        String m = String.valueOf(t.getMessage());
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }

    // ---- share-browser ----

    static ObjectNode share(ProtoHost host, Map<String, String> f) {
        if (f.containsKey("stop")) {
            if (host.stateMap().remove("share-browser") == null) throw new IllegalStateException("error: the browser is not shared\nfix: share-browser --name=<name>");
            host.pw().call(c -> {
                c.browser().unbind();
                return null;
            });
            host.log("share-browser stop");
            return ProtoJson.obj().put("op", "share-browser").put("mode", "stop").put("via", "playwright");
        }
        String name = f.get("name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("error: share-browser needs --name=<name>\nfix: share-browser --name=review");
        if (host.stateMap().containsKey("share-browser")) throw new IllegalStateException("error: the browser is already shared\nfix: share-browser --stop");
        int port = EventCommands.parseInt(f.get("port"), -1);
        BrowserProtocols.Served<String> served = host.protocols().serve("share-browser", InputCommands.playwright(f), backend -> {
            if (backend != Backend.PLAYWRIGHT) throw new UnsupportedProtocolOperation("share-browser", backend.id(), null);
            return host.pw().call(c -> {
                Browser.BindOptions options = new Browser.BindOptions().setWorkspaceDir(host.project().toAbsolutePath().toString());
                // Local only: a local pipe by default, or a WebSocket on the loopback address with --port.
                if (port >= 0) options.setHost("127.0.0.1").setPort(port);
                BindResult result = c.browser().bind(name, options);
                return result.endpoint;
            });
        });
        String endpoint = served.value();
        host.stateMap().put("share-browser", name);
        host.log("share-browser name=" + name + " endpoint=" + (endpoint.startsWith("ws") ? "ws://127.0.0.1:<port>/<id>" : "local pipe"));
        ObjectNode out = ProtoJson.obj().put("op", "share-browser").put("mode", "bind").put("via", served.via().id()).put("name", name)
                .put("endpoint", endpoint).put("connect", "playwright-cli attach " + name);
        ObjectNode mcp = out.putObject("mcpConfig").putObject("mcpServers").putObject("playwright");
        mcp.put("command", "npx");
        mcp.putArray("args").add("@playwright/mcp").add("--endpoint").add(endpoint);
        out.put("warning", "anything on this machine that can reach the endpoint can drive this browser; share-browser --stop ends it");
        return out;
    }
}
