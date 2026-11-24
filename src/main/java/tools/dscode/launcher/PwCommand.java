package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.ScreenshotType;
import com.microsoft.playwright.options.WaitUntilState;
import tools.dscode.browser.PlaywrightConnection;
import tools.dscode.control.protocol.SecretRedactor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * D14: Playwright features through pickleball verbs. {@code pw <area> <action> [--flags]} runs one step;
 * {@code pw run --steps=<file|json>} runs several on one connection, which is how state that lives in the Playwright
 * client (routes, tracing, dialog policy, a new emulated context, video) spans steps. {@code cdp --method= --params=}
 * sends one raw CDP command to the page. The browser is the one {@code --target=browser:cdp=<ws>},
 * {@code --cdp-endpoint=<channel|url>}, or {@code pkb_cdp_endpoint} names, else this project's live browser
 * ({@code .pickleball/live-browser/<id>/profile/DevToolsActivePort}). Playwright attaches over CDP only and never
 * launches or downloads a browser. Selectors are Playwright selectors ({@code css=}, {@code xpath=}, {@code text=},
 * {@code role=button[name="Save"]}, {@code >> nth=0}); the {@code xpath} a snap row carries works as {@code xpath=...}.
 */
final class PwCommand {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> AREAS = List.of("page", "context", "storage", "route", "har", "wait", "locator", "trace", "video",
            "screenshot", "download", "upload", "dialog", "emulate", "tabs", "frames", "keyboard", "mouse", "run");

    private PwCommand() {
    }

    /** The step state for one invocation: one connection, a current page, contexts and listeners this call made. */
    static final class State {
        final Path project;
        final PlaywrightConnection connection;
        Page page;
        final List<BrowserContext> made = new ArrayList<>();
        String dialogPolicy = "";
        String promptText = "";
        final List<String> dialogs = new ArrayList<>();
        Frame frame;

        State(Path project, PlaywrightConnection connection, Page page) {
            this.project = project;
            this.connection = connection;
            this.page = page;
        }

        Browser browser() {
            return connection.browser();
        }

        final java.util.function.Consumer<Dialog> handler = this::dialog;
        final List<Page> listening = new ArrayList<>();

        void use(Page next) {
            page = next;
            frame = null;
            if (!dialogPolicy.isEmpty()) listen(next);
        }

        /** Only a dialog step installs a handler, so other calls leave the page's dialogs alone. */
        void listen(Page next) {
            if (listening.contains(next)) return;
            next.onDialog(handler);
            listening.add(next);
        }

        void unlisten() {
            for (Page listened : listening) {
                try {
                    listened.offDialog(handler);
                } catch (RuntimeException ignored) {
                    // Page closed.
                }
            }
            listening.clear();
        }

        void dialog(Dialog dialog) {
            dialogs.add(dialog.type() + ":" + dialog.message());
            if ("accept".equals(dialogPolicy)) {
                if ("prompt".equals(dialog.type()) && !promptText.isEmpty()) dialog.accept(promptText);
                else dialog.accept();
            } else if ("dismiss".equals(dialogPolicy)) {
                dialog.dismiss();
            }
        }

        Locator locator(String selector) {
            return frame != null ? frame.locator(selector) : page.locator(selector);
        }
    }

    static int run(String[] args, Path project, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? "" : args[0];
        List<String> positional = positional(args);
        Map<String, String> flags = flags(args);
        String area = "cdp".equals(command) ? "cdp" : positional.isEmpty() ? "" : positional.get(0);
        String action = "cdp".equals(command) ? "send" : positional.size() > 1 ? positional.get(1) : "";
        if (!"cdp".equals(area) && !AREAS.contains(area)) {
            return fail(err, "pw needs an area: " + String.join(", ", AREAS), "pw page list, pw screenshot --out=page.png, or pw run --steps=steps.json");
        }
        String endpoint;
        try {
            endpoint = endpoint(project, flags);
        } catch (IllegalStateException failure) {
            String message = failure.getMessage();
            int fix = message.indexOf("\nfix: ");
            return fail(err, fix < 0 ? message.replaceFirst("^error: ", "") : message.substring(0, fix).replaceFirst("^error: ", ""),
                    fix < 0 ? "pass --cdp-endpoint=<url> or start a live browser" : message.substring(fix + 6));
        }
        State state;
        try {
            PlaywrightConnection connection = PlaywrightConnection.connect(endpoint);
            state = new State(project, connection, null);
            state.use(pick(connection, flags.get("page-url")));
        } catch (RuntimeException failure) {
            return fail(err, firstLine(failure.getMessage()).replaceFirst("^error: ", ""), "check the endpoint with doctor --attach; pickleball does not launch a Playwright browser");
        }
        log(project, area + " " + action, endpoint);
        ObjectNode body = JSON.createObjectNode().put("ok", true).put("op", "cdp".equals(area) ? "cdp" : "pw").put("area", area).put("action", action);
        try {
            if ("run".equals(area)) {
                ArrayNode results = body.putArray("steps");
                for (JsonNode step : steps(flags.get("steps"))) {
                    Map<String, String> stepFlags = new LinkedHashMap<>();
                    step.fields().forEachRemaining(entry -> {
                        if (!"area".equals(entry.getKey()) && !"action".equals(entry.getKey())) {
                            stepFlags.put(entry.getKey(), entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString());
                        }
                    });
                    ObjectNode result = results.addObject().put("area", step.path("area").asText()).put("action", step.path("action").asText());
                    step(state, step.path("area").asText(), step.path("action").asText(), stepFlags, result);
                }
            } else {
                step(state, area, action, flags, body);
            }
            if (!state.dialogs.isEmpty()) {
                ArrayNode seen = body.putArray("dialogs");
                state.dialogs.forEach(seen::add);
            }
            body.put("url", state.page.url());
            out.println(SecretRedactor.scrub(body.toString()));
            return 0;
        } catch (Unsupported unsupported) {
            return fail(err, unsupported.getMessage(), unsupported.fix);
        } catch (IllegalArgumentException invalid) {
            return fail(err, invalid.getMessage(), "see help pw");
        } catch (RuntimeException | IOException failure) {
            return fail(err, "pw " + area + " " + action + " failed: " + firstLine(failure.getMessage()), "check the selector, URL, or flags; see help pw");
        } finally {
            state.unlisten();
            for (BrowserContext context : state.made) {
                try {
                    if (!"keep".equals(flags.get("context"))) context.close();
                } catch (RuntimeException ignored) {
                    // Already closed.
                }
            }
        }
    }

    /** A feature that cannot work over connectOverCDP or with these flags. */
    static final class Unsupported extends RuntimeException {
        final String fix;

        Unsupported(String message, String fix) {
            super(message);
            this.fix = fix;
        }
    }

    static void step(State s, String area, String action, Map<String, String> f, ObjectNode out) throws IOException {
        switch (area) {
            case "page" -> page(s, action, f, out);
            case "context" -> context(s, action, f, out);
            case "storage" -> storage(s, action, f, out);
            case "route" -> route(s, action, f, out);
            case "har" -> har(s, action, f, out);
            case "wait" -> waitFor(s, f, out);
            case "locator" -> locator(s, action, f, out);
            case "trace" -> trace(s, action, f, out);
            case "video" -> video(s, f, out);
            case "screenshot" -> screenshot(s, f, out);
            case "download" -> download(s, f, out);
            case "upload" -> upload(s, f, out);
            case "dialog" -> dialog(s, action, f, out);
            case "emulate" -> emulate(s, f, out);
            case "tabs" -> tabs(s, action, f, out);
            case "frames" -> frames(s, action, f, out);
            case "keyboard" -> keyboard(s, action, f, out);
            case "mouse" -> mouse(s, action, f, out);
            case "cdp" -> cdp(s, f, out);
            default -> throw new IllegalArgumentException("unknown pw area " + area);
        }
    }

    private static void page(State s, String action, Map<String, String> f, ObjectNode out) {
        switch (action) {
            case "list" -> listPages(s, out);
            case "new" -> {
                BrowserContext context = s.page.context();
                Page next = context.newPage();
                s.use(next);
                if (f.containsKey("url")) next.navigate(f.get("url"));
                out.put("index", s.connection.pages().indexOf(next));
            }
            case "goto" -> {
                Response response = s.page.navigate(require(f, "url"));
                out.put("status", response == null ? 0 : response.status());
            }
            case "close" -> {
                int index = s.connection.pages().indexOf(s.page);
                s.page.close();
                out.put("closed", index);
                List<Page> left = s.connection.pages();
                if (!left.isEmpty()) s.use(left.get(0));
            }
            case "front" -> s.page.bringToFront();
            case "reload" -> s.page.reload();
            case "title" -> out.put("title", s.page.title());
            case "eval" -> out.set("value", JSON.valueToTree(s.page.evaluate(require(f, "expr"))));
            default -> throw new IllegalArgumentException("pw page needs list, new, goto, close, front, reload, title, or eval");
        }
    }

    private static void listPages(State s, ObjectNode out) {
        ArrayNode pages = out.putArray("pages");
        List<Page> all = s.connection.pages();
        for (int i = 0; i < all.size(); i++) {
            Page page = all.get(i);
            pages.addObject().put("index", i).put("url", page.url()).put("title", safeTitle(page)).put("current", page == s.page);
        }
    }

    private static String safeTitle(Page page) {
        try {
            return page.title();
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static void context(State s, String action, Map<String, String> f, ObjectNode out) {
        switch (action) {
            case "list" -> {
                ArrayNode contexts = out.putArray("contexts");
                List<BrowserContext> all = s.browser().contexts();
                for (int i = 0; i < all.size(); i++) contexts.addObject().put("index", i).put("pages", all.get(i).pages().size());
            }
            case "new" -> {
                BrowserContext context = newContext(s, f);
                Page page = context.newPage();
                s.use(page);
                if (f.containsKey("url")) page.navigate(f.get("url"));
                out.put("contexts", s.browser().contexts().size());
            }
            case "close" -> {
                BrowserContext context = s.page.context();
                if (context == s.browser().contexts().get(0)) {
                    throw new Unsupported("pw context close refuses the browser's default context", "close a context made with pw context new in the same pw run");
                }
                context.close();
                s.made.remove(context);
                s.use(s.connection.page(null));
            }
            default -> throw new IllegalArgumentException("pw context needs list, new, or close");
        }
    }

    static BrowserContext newContext(State s, Map<String, String> f) {
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        if (f.containsKey("device")) applyDevice(options, f.get("device"));
        if (f.containsKey("viewport")) {
            String[] size = f.get("viewport").toLowerCase(Locale.ROOT).split("x");
            options.setViewportSize(Integer.parseInt(size[0].trim()), Integer.parseInt(size[1].trim()));
        }
        if (f.containsKey("geolocation")) {
            String[] at = f.get("geolocation").split(",");
            options.setGeolocation(Double.parseDouble(at[0].trim()), Double.parseDouble(at[1].trim()));
        }
        if (f.containsKey("locale")) options.setLocale(f.get("locale"));
        if (f.containsKey("timezone")) options.setTimezoneId(f.get("timezone"));
        if (f.containsKey("color-scheme")) options.setColorScheme(ColorScheme.valueOf(f.get("color-scheme").toUpperCase(Locale.ROOT).replace('-', '_')));
        if (f.containsKey("offline")) options.setOffline(!"false".equalsIgnoreCase(f.get("offline")));
        if (f.containsKey("user-agent")) options.setUserAgent(f.get("user-agent"));
        if (f.containsKey("permissions")) options.setPermissions(List.of(f.get("permissions").split(",")));
        if (f.containsKey("storage")) options.setStorageStatePath(Path.of(f.get("storage")));
        if (f.containsKey("record-video")) options.setRecordVideoDir(Path.of(f.get("record-video")));
        if (f.containsKey("record-har")) options.setRecordHarPath(Path.of(f.get("record-har")));
        BrowserContext context = s.browser().newContext(options);
        s.made.add(context);
        return context;
    }

    /** Device descriptors come from the bundled playwright-core ({@code devices}), read with the bundled Node. */
    static void applyDevice(Browser.NewContextOptions options, String name) {
        JsonNode device = Devices.get(name);
        if (device == null) throw new IllegalArgumentException("unknown device " + name + "; names come from Playwright's device list, e.g. \"iPhone 13\" or \"Pixel 7\"");
        JsonNode viewport = device.path("viewport");
        options.setViewportSize(viewport.path("width").asInt(), viewport.path("height").asInt());
        options.setUserAgent(device.path("userAgent").asText());
        options.setDeviceScaleFactor(device.path("deviceScaleFactor").asDouble(1));
        options.setIsMobile(device.path("isMobile").asBoolean());
        options.setHasTouch(device.path("hasTouch").asBoolean());
    }

    private static void storage(State s, String action, Map<String, String> f, ObjectNode out) {
        switch (action) {
            case "save" -> {
                Path file = Path.of(require(f, "path"));
                s.page.context().storageState(new BrowserContext.StorageStateOptions().setPath(file));
                out.put("path", file.toString());
            }
            case "load" -> {
                Map<String, String> next = new LinkedHashMap<>(f);
                next.put("storage", require(f, "path"));
                BrowserContext context = newContext(s, next);
                Page page = context.newPage();
                s.use(page);
                if (f.containsKey("url")) page.navigate(f.get("url"));
                out.put("cookies", context.cookies().size());
            }
            default -> throw new IllegalArgumentException("pw storage needs save or load --path=<file>");
        }
    }

    private static void route(State s, String action, Map<String, String> f, ObjectNode out) {
        String pattern = require(f, "pattern");
        switch (action) {
            case "fulfill" -> s.page.route(pattern, route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                    .setStatus(Integer.parseInt(f.getOrDefault("code", "200")))
                    .setContentType(f.getOrDefault("content-type", "text/html"))
                    .setBody(f.getOrDefault("body", ""))));
            case "abort" -> s.page.route(pattern, route -> route.abort(f.getOrDefault("error", "failed")));
            case "continue" -> s.page.route(pattern, route -> {
                Map<String, String> headers = new LinkedHashMap<>(route.request().headers());
                if (f.containsKey("header")) {
                    String[] pair = f.get("header").split(":", 2);
                    headers.put(pair[0].trim(), pair.length > 1 ? pair[1].trim() : "");
                }
                route.resume(new com.microsoft.playwright.Route.ResumeOptions().setHeaders(headers));
            });
            case "clear" -> s.page.unroute(pattern);
            default -> throw new IllegalArgumentException("pw route needs fulfill, abort, continue, or clear --pattern=<glob>");
        }
        out.put("pattern", pattern);
        if (f.containsKey("url")) {
            Response response = s.page.navigate(f.get("url"));
            out.put("status", response == null ? 0 : response.status());
        }
    }

    private static void har(State s, String action, Map<String, String> f, ObjectNode out) {
        Path file = Path.of(require(f, "path"));
        switch (action) {
            case "record" -> {
                Map<String, String> next = new LinkedHashMap<>(f);
                next.put("record-har", file.toString());
                BrowserContext context = newContext(s, next);
                Page page = context.newPage();
                s.use(page);
                page.navigate(require(f, "url"));
                context.close();
                s.made.remove(context);
                s.use(s.connection.page(null));
                out.put("path", file.toString()).put("bytes", file.toFile().length());
            }
            case "replay" -> {
                s.page.context().routeFromHAR(file, new BrowserContext.RouteFromHAROptions().setNotFound(com.microsoft.playwright.options.HarNotFound.ABORT));
                if (f.containsKey("url")) s.page.navigate(f.get("url"));
                out.put("path", file.toString());
            }
            default -> throw new IllegalArgumentException("pw har needs record --path= --url= or replay --path=");
        }
    }

    private static void waitFor(State s, Map<String, String> f, ObjectNode out) {
        String kind = require(f, "for");
        double timeout = Double.parseDouble(f.getOrDefault("wait-ms", "15000"));
        String value = f.getOrDefault("match", "");
        long started = System.nanoTime();
        switch (kind) {
            case "load" -> s.page.waitForLoadState(LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(timeout));
            case "domcontentloaded" -> s.page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(timeout));
            case "networkidle" -> s.page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(timeout));
            case "url" -> s.page.waitForURL(value, new Page.WaitForURLOptions().setTimeout(timeout));
            case "selector" -> s.locator(value).first().waitFor(new Locator.WaitForOptions().setTimeout(timeout));
            case "response" -> {
                Response response = s.page.waitForResponse(value, new Page.WaitForResponseOptions().setTimeout(timeout), () -> trigger(s, f));
                out.put("status", response.status()).put("matched", response.url());
            }
            case "request" -> {
                Request request = s.page.waitForRequest(value, new Page.WaitForRequestOptions().setTimeout(timeout), () -> trigger(s, f));
                out.put("matched", request.url()).put("method", request.method());
            }
            default -> throw new IllegalArgumentException("pw wait --for= must be load, domcontentloaded, networkidle, url, response, request, or selector");
        }
        out.put("for", kind).put("waitedMs", (System.nanoTime() - started) / 1_000_000);
    }

    /** The action a response or request wait watches: --click=<selector> or --url=<address>. */
    private static void trigger(State s, Map<String, String> f) {
        if (f.containsKey("click")) s.locator(f.get("click")).first().click();
        else if (f.containsKey("url")) s.page.navigate(f.get("url"));
    }

    private static void locator(State s, String action, Map<String, String> f, ObjectNode out) {
        Locator locator = s.locator(require(f, "selector"));
        switch (action) {
            case "count" -> out.put("count", locator.count());
            case "click" -> locator.click();
            case "fill" -> locator.fill(f.getOrDefault("text", ""));
            case "text" -> out.put("text", locator.textContent());
            case "value" -> out.put("value", locator.inputValue());
            case "hover" -> locator.hover();
            case "check" -> locator.check();
            case "select" -> locator.selectOption(f.getOrDefault("text", ""));
            default -> throw new IllegalArgumentException("pw locator needs count, click, fill, text, value, hover, check, or select --selector=");
        }
        out.put("selector", f.get("selector"));
    }

    private static void trace(State s, String action, Map<String, String> f, ObjectNode out) {
        Tracing tracing = s.page.context().tracing();
        switch (action) {
            case "start" -> tracing.start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
            case "stop" -> {
                Path zip = Path.of(require(f, "out"));
                tracing.stop(new Tracing.StopOptions().setPath(zip));
                out.put("out", zip.toString()).put("bytes", zip.toFile().length());
            }
            default -> throw new IllegalArgumentException("pw trace needs start, then stop --out=<zip>, in one pw run");
        }
    }

    private static void video(State s, Map<String, String> f, ObjectNode out) throws IOException {
        Path dir = Path.of(require(f, "dir"));
        Map<String, String> next = new LinkedHashMap<>(f);
        next.put("record-video", dir.toString());
        BrowserContext context;
        try {
            context = newContext(s, next);
        } catch (RuntimeException failure) {
            throw new Unsupported("video recording is not available over connectOverCDP here: " + firstLine(failure.getMessage()),
                    "record video with a new context in pw run, or use pw trace (screenshots) on an attached browser");
        }
        Page page;
        try {
            page = context.newPage();
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            BrowserAttach.log(s.project, "video-unsupported", message.replaceAll("\\s+", " "));
            throw new Unsupported("video recording is not available over connectOverCDP here"
                    + (message.toLowerCase(Locale.ROOT).contains("ffmpeg") ? ": Playwright needs its ffmpeg build, which pickleball never downloads" : ": " + oneLine(message)),
                    "use pw trace start/stop (screenshots and DOM snapshots) or pw screenshot on an attached browser");
        }
        s.use(page);
        page.navigate(require(f, "url"));
        page.waitForTimeout(Double.parseDouble(f.getOrDefault("wait-ms", "500")));
        context.close();
        s.made.remove(context);
        s.use(s.connection.page(null));
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> webm = files.filter(p -> p.toString().endsWith(".webm")).toList();
            if (webm.isEmpty()) throw new Unsupported("no video was written over connectOverCDP", "use pw trace for a recording of an attached browser");
            out.put("video", webm.get(0).toString()).put("bytes", webm.get(0).toFile().length());
        }
    }

    private static void screenshot(State s, Map<String, String> f, ObjectNode out) {
        Path file = Path.of(require(f, "out"));
        byte[] bytes;
        if (f.containsKey("selector")) {
            bytes = s.locator(f.get("selector")).first().screenshot(new Locator.ScreenshotOptions().setPath(file));
        } else {
            bytes = s.page.screenshot(new Page.ScreenshotOptions().setPath(file).setType(ScreenshotType.PNG)
                    .setFullPage("true".equalsIgnoreCase(f.getOrDefault("full-page", "false"))));
        }
        out.put("out", file.toString()).put("bytes", bytes.length);
    }

    private static void download(State s, Map<String, String> f, ObjectNode out) {
        Path dir = Path.of(require(f, "dir"));
        Download download = s.page.waitForDownload(() -> s.locator(require(f, "click")).first().click());
        Path target = dir.resolve(download.suggestedFilename());
        download.saveAs(target);
        out.put("path", target.toString()).put("bytes", target.toFile().length()).put("suggested", download.suggestedFilename());
    }

    private static void upload(State s, Map<String, String> f, ObjectNode out) {
        String[] files = require(f, "path").split(",");
        Path[] paths = new Path[files.length];
        for (int i = 0; i < files.length; i++) paths[i] = Path.of(files[i].trim());
        s.locator(require(f, "selector")).first().setInputFiles(paths);
        out.put("files", files.length);
    }

    private static void dialog(State s, String action, Map<String, String> f, ObjectNode out) {
        if (!List.of("accept", "dismiss").contains(action)) throw new IllegalArgumentException("pw dialog needs accept or dismiss [--prompt-text=] [--click=<selector>]");
        s.dialogPolicy = action;
        s.promptText = f.getOrDefault("prompt-text", "");
        s.listen(s.page);
        out.put("policy", action);
        if (f.containsKey("click")) s.locator(f.get("click")).first().click();
    }

    private static void emulate(State s, Map<String, String> f, ObjectNode out) {
        BrowserContext context = newContext(s, f);
        Page page = context.newPage();
        s.use(page);
        if (f.containsKey("url")) page.navigate(f.get("url"));
        @SuppressWarnings("unchecked")
        Map<String, Object> seen = (Map<String, Object>) page.evaluate("() => ({width: innerWidth, height: innerHeight, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, "
                + "locale: navigator.language, userAgent: navigator.userAgent, online: navigator.onLine, dark: matchMedia('(prefers-color-scheme: dark)').matches})");
        out.set("observed", JSON.valueToTree(seen));
        out.put("note", "emulation applies to a new context in the attached browser; it lasts for this pw call or pw run");
    }

    private static void tabs(State s, String action, Map<String, String> f, ObjectNode out) {
        switch (action) {
            case "list" -> listPages(s, out);
            case "switch" -> {
                List<Page> all = s.connection.pages();
                int index = Integer.parseInt(require(f, "index"));
                if (index < 0 || index >= all.size()) throw new IllegalArgumentException("no tab " + index + " (" + all.size() + " open)");
                s.use(all.get(index));
                s.page.bringToFront();
                out.put("index", index);
            }
            default -> throw new IllegalArgumentException("pw tabs needs list or switch --index=<n>");
        }
    }

    private static void frames(State s, String action, Map<String, String> f, ObjectNode out) {
        switch (action) {
            case "list" -> {
                ArrayNode frames = out.putArray("frames");
                List<Frame> all = s.page.frames();
                for (int i = 0; i < all.size(); i++) frames.addObject().put("index", i).put("name", all.get(i).name()).put("url", all.get(i).url());
            }
            case "switch" -> {
                String name = require(f, "frame-name");
                Frame frame = s.page.frames().stream().filter(item -> name.equals(item.name()) || item.url().contains(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("no frame named or with a URL containing " + name));
                s.frame = frame;
                out.put("frame", frame.name()).put("frameUrl", frame.url());
                if (f.containsKey("selector")) out.put("text", frame.locator(f.get("selector")).first().textContent());
            }
            default -> throw new IllegalArgumentException("pw frames needs list or switch --frame-name=<name or url part>");
        }
    }

    private static void keyboard(State s, String action, Map<String, String> f, ObjectNode out) {
        if (f.containsKey("selector")) s.locator(f.get("selector")).first().focus();
        switch (action) {
            case "press" -> s.page.keyboard().press(require(f, "keys"));
            case "type" -> s.page.keyboard().type(require(f, "text"));
            case "down" -> s.page.keyboard().down(require(f, "keys"));
            case "up" -> s.page.keyboard().up(require(f, "keys"));
            default -> throw new IllegalArgumentException("pw keyboard needs press --keys=Control+A, type --text=, down, or up");
        }
    }

    private static void mouse(State s, String action, Map<String, String> f, ObjectNode out) {
        switch (action) {
            case "move" -> s.page.mouse().move(num(f, "x"), num(f, "y"));
            case "down" -> s.page.mouse().down(new com.microsoft.playwright.Mouse.DownOptions().setButton(button(f)));
            case "up" -> s.page.mouse().up(new com.microsoft.playwright.Mouse.UpOptions().setButton(button(f)));
            case "click" -> s.page.mouse().click(num(f, "x"), num(f, "y"));
            case "wheel" -> s.page.mouse().wheel(num(f, "dx"), num(f, "dy"));
            case "drag" -> {
                if (f.containsKey("from") && f.containsKey("to")) {
                    s.locator(f.get("from")).first().dragTo(s.locator(f.get("to")).first());
                } else {
                    s.page.mouse().move(num(f, "x"), num(f, "y"));
                    s.page.mouse().down();
                    s.page.mouse().move(num(f, "x2"), num(f, "y2"), new com.microsoft.playwright.Mouse.MoveOptions().setSteps(8));
                    s.page.mouse().up();
                }
            }
            default -> throw new IllegalArgumentException("pw mouse needs move, down, up, click, wheel, or drag (--from= --to= selectors, or --x --y --x2 --y2)");
        }
    }

    private static MouseButton button(Map<String, String> f) {
        return MouseButton.valueOf(f.getOrDefault("button", "left").toUpperCase(Locale.ROOT));
    }

    private static void cdp(State s, Map<String, String> f, ObjectNode out) throws IOException {
        String method = require(f, "method");
        CDPSession session = s.page.context().newCDPSession(s.page);
        try {
            String params = f.get("params");
            JsonObject result = params == null || params.isBlank() ? session.send(method) : session.send(method, JsonParser.parseString(params).getAsJsonObject());
            out.put("method", method);
            out.set("result", JSON.readTree(result.toString()));
        } finally {
            session.detach();
        }
    }

    static String endpoint(Path project, Map<String, String> f) {
        String target = f.get("target");
        if (target != null && target.startsWith("browser:cdp=")) return target.substring("browser:cdp=".length());
        String cdp = f.get("cdp-endpoint");
        if (cdp != null) {
            String channel = cdp.trim().toLowerCase(Locale.ROOT);
            String ws = BrowserAttach.CHANNELS.contains(channel)
                    ? BrowserAttach.activePort(BrowserAttach.userDataDir(channel, BrowserAttach.probes), BrowserAttach.probes)
                    : BrowserAttach.resolveExplicit(cdp, BrowserAttach.probes);
            if (ws == null) throw new IllegalStateException("error: no DevTools endpoint for " + BrowserAttach.redact(cdp) + "\nfix: " + BrowserAttach.CHANNEL_FIX + ", or pass an http/ws endpoint");
            return ws;
        }
        String configured = PlaywrightConnection.configuredEndpoint(null);
        if (configured != null) return configured;
        String live = liveEndpoint(project);
        if (live != null) return live;
        throw new IllegalStateException(PlaywrightConnection.NO_ENDPOINT);
    }

    /** This project's live browser, from its own profile's DevToolsActivePort (newest first). */
    static String liveEndpoint(Path project) {
        if (project == null) return null;
        Path root = project.resolve(".pickleball").resolve("live-browser");
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> dirs = Files.list(root)) {
            List<Path> ports = dirs.map(dir -> dir.resolve("profile").resolve("DevToolsActivePort")).filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
            for (Path port : ports) {
                String ws = BrowserAttach.activePort(port.getParent(), BrowserAttach.LIVE);
                if (ws != null) return ws;
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static Page pick(PlaywrightConnection connection, String url) {
        List<Page> pages = connection.pages();
        if (url != null) for (Page page : pages) if (page.url().contains(url)) return page;
        for (Page page : pages) if (!page.url().startsWith("devtools://") && !page.url().startsWith("chrome-extension://")) return page;
        return connection.page(null);
    }

    static List<JsonNode> steps(String value) throws IOException {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("pw run needs --steps=<file or JSON array>");
        String text = value.trim().startsWith("[") ? value : Files.readString(Path.of(value.trim()));
        JsonNode node = JSON.readTree(text);
        if (!node.isArray()) throw new IllegalArgumentException("pw run --steps must be a JSON array of {\"area\":...,\"action\":...}");
        List<JsonNode> out = new ArrayList<>();
        node.forEach(out::add);
        return out;
    }

    static List<String> positional(String[] args) {
        List<String> out = new ArrayList<>();
        for (int i = 2; i < args.length; i++) if (args[i] != null && !args[i].startsWith("--")) out.add(args[i].trim().toLowerCase(Locale.ROOT));
        return out;
    }

    static Map<String, String> flags(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String token : args) {
            if (token == null || !token.startsWith("--")) continue;
            int eq = token.indexOf('=');
            if (eq < 0) out.put(token.substring(2), "true");
            else out.put(token.substring(2, eq), token.substring(eq + 1));
        }
        return out;
    }

    private static String require(Map<String, String> f, String name) {
        String value = f.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing --" + name);
        return value;
    }

    private static double num(Map<String, String> f, String name) {
        return Double.parseDouble(require(f, name));
    }

    private static int fail(PrintStream err, String error, String fix) {
        err.println(SecretRedactor.scrub(WorkbenchCliHelp.structured(error, fix).stripTrailing()));
        return 1;
    }

    private static String oneLine(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() > 240 ? flat.substring(0, 240) : flat;
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    static void log(Path project, String what, String endpoint) {
        if (project == null) return;
        try {
            Path file = project.toAbsolutePath().normalize().resolve(".pickleball").resolve("logs").resolve("playwright.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + "\t" + what + "\t" + BrowserAttach.redact(endpoint) + "\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging never blocks a step.
        }
    }

    /** Playwright's device list from the bundled playwright-core, read once with the bundled Node. */
    static final class Devices {
        private static volatile JsonNode all;

        static JsonNode get(String name) {
            if (all == null) all = load();
            JsonNode device = all.get(name);
            return device == null || device.isMissingNode() ? null : device;
        }

        private static JsonNode load() {
            try {
                com.microsoft.playwright.impl.driver.Driver driver = com.microsoft.playwright.impl.driver.Driver.ensureDriverInstalled(PlaywrightConnection.DRIVER_ENV, false);
                List<String> base = driver.createProcessBuilder().command();
                Path packageDir = driver.driverDir().resolve("package");
                ProcessBuilder builder = new ProcessBuilder(base.get(0), "-e",
                        "process.stdout.write(JSON.stringify(require(process.argv[1]).devices))", packageDir.toString());
                builder.environment().putAll(PlaywrightConnection.DRIVER_ENV);
                Process process = builder.start();
                String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                process.waitFor();
                return JSON.readTree(text);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                return JSON.createObjectNode();
            }
        }
    }
}
