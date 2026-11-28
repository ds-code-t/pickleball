package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import tools.dscode.browser.BidiModules;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.RpcChannel;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E7: browser contexts and tabs, page history, print to PDF, storage (cookies, local, session, IndexedDB, OPFS),
 * storage state files and downloads. Backends: Playwright (when it serves the session), BiDi, CDP.
 */
final class ContextCommands {
    static final String REDACTED = "[redacted]";

    private ContextCommands() {
    }

    static void register() {
        ProtoHost.register("context-new", ContextCommands::contextNew);
        ProtoHost.register("context-list", ContextCommands::contextList);
        ProtoHost.register("context-close", ContextCommands::contextClose);
        ProtoHost.register("tab-new", ContextCommands::tabNew);
        ProtoHost.register("tab-activate", ContextCommands::tabActivate);
        ProtoHost.register("page-history", ContextCommands::history);
        ProtoHost.register("print", ContextCommands::print);
        ProtoHost.register("storage", StorageCommands::storage);
        ProtoHost.register("storage-state", StorageCommands::storageState);
        ProtoHost.register("downloads", ContextCommands::downloads);
    }

    /** The page target for --tab=<id> or --page=<url substring>, else the first page. */
    static CdpSession.Target target(ProtoHost host, Map<String, String> f) {
        String tab = f.get("tab");
        if (tab != null) {
            for (CdpSession.Target t : RouteCommands.pages(host)) if (t.targetId.equals(tab)) return t;
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                for (CdpSession.Target t : host.cdp().pages()) if (t.targetId.equals(tab)) return t;
                sleep(50);
            }
            throw new IllegalArgumentException("error: no tab " + tab + "\nfix: context-list shows the tab ids");
        }
        return host.cdp().page(f.get("page"));
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static String bidiContext(ProtoHost host, Map<String, String> f) {
        if (f.get("tab") != null) return f.get("tab");
        return host.bidiContext(f.get("page"));
    }

    static Path out(ProtoHost host, String file, String fallback) throws Exception {
        String name = file == null || file.isBlank() ? fallback : file;
        Path path = host.project().resolve(name).normalize();
        Files.createDirectories(path.toAbsolutePath().getParent());
        return path;
    }

    // ---- contexts ----

    static ObjectNode contextNew(ProtoHost host, Map<String, String> f) throws Exception {
        String state = f.get("storage-state");
        JsonNode loaded = state == null ? null : ProtoJson.MAPPER.readTree(host.project().resolve(state.startsWith("@") ? state.substring(1) : state).toFile());
        BrowserProtocols.Served<String> served = host.protocols().serve("contexts", f.get("via"), backend -> {
            switch (backend) {
                case PLAYWRIGHT -> {
                    Map<String, BrowserContext> made = host.state("contexts.pw", ConcurrentHashMap::new);
                    return host.pw().call(c -> {
                        com.microsoft.playwright.Browser.NewContextOptions o = new com.microsoft.playwright.Browser.NewContextOptions();
                        if (state != null) o.setStorageStatePath(host.project().resolve(state.startsWith("@") ? state.substring(1) : state));
                        BrowserContext context = c.browser().newContext(o);
                        String id = "pw-context-" + (made.size() + 1);
                        made.put(id, context);
                        return "playwright:" + id;
                    });
                }
                case BIDI -> {
                    String id = host.bidiModules().createUserContext().path("userContext").asText();
                    if (loaded != null) StorageCommands.loadBidi(host, loaded, id);
                    return "bidi:" + id;
                }
                case CDP -> {
                    String id = host.cdp().browser("Target.createBrowserContext", Map.of("disposeOnDetach", false)).path("browserContextId").asText();
                    if (loaded != null) StorageCommands.loadCdp(host, loaded, id);
                    return "cdp:" + id;
                }
                default -> throw new UnsupportedProtocolOperation("contexts", backend.id(), null);
            }
        });
        String id = served.value().substring(served.value().indexOf(':') + 1);
        List<String> made = host.state("contexts.made", java.util.concurrent.CopyOnWriteArrayList::new);
        made.add(served.via().id() + ":" + id);
        if (host.stateMap().putIfAbsent("contexts.cleanup", true) == null) {
            host.onStop(() -> {
                for (String entry : made) {
                    try {
                        contextClose(host, Map.of("id", entry.substring(entry.indexOf(':') + 1), "via", entry.substring(0, entry.indexOf(':'))));
                    } catch (Exception ignored) {
                        // Gone.
                    }
                }
            });
        }
        host.log("context-new " + id + " via=" + served.via().id() + (state == null ? "" : " storage-state loaded"));
        return ProtoJson.obj().put("op", "context-new").put("via", served.via().id()).put("id", id).put("storageState", state != null);
    }

    static ObjectNode contextList(ProtoHost host, Map<String, String> f) {
        BrowserProtocols.Served<ObjectNode> served = host.protocols().serve("contexts", f.get("via"), backend -> {
            ObjectNode out = ProtoJson.obj().put("op", "context-list");
            ArrayNode contexts = out.putArray("contexts");
            switch (backend) {
                case BIDI -> {
                    for (JsonNode uc : host.bidiModules().getUserContexts().path("userContexts")) {
                        ObjectNode c = contexts.addObject().put("id", uc.path("userContext").asText());
                        ArrayNode tabs = c.putArray("tabs");
                        for (JsonNode bc : host.bidiModules().getTree().path("contexts")) {
                            if (uc.path("userContext").asText().equals(bc.path("userContext").asText())) {
                                tabs.addObject().put("id", bc.path("context").asText()).put("url", bc.path("url").asText());
                            }
                        }
                    }
                }
                case CDP -> {
                    List<String> ids = new ArrayList<>();
                    ids.add("default");
                    for (JsonNode id : host.cdp().browser("Target.getBrowserContexts", Map.of()).path("browserContextIds")) ids.add(id.asText());
                    JsonNode targets = host.cdp().browser("Target.getTargets", Map.of()).path("targetInfos");
                    String defaultId = null;
                    for (JsonNode t : targets) {
                        if (!"page".equals(t.path("type").asText())) continue;
                        if (!ids.contains(t.path("browserContextId").asText())) defaultId = t.path("browserContextId").asText();
                    }
                    for (String id : ids) {
                        String real = "default".equals(id) ? defaultId : id;
                        ObjectNode c = contexts.addObject().put("id", id);
                        if ("default".equals(id) && real != null) c.put("browserContextId", real);
                        ArrayNode tabs = c.putArray("tabs");
                        for (JsonNode t : targets) {
                            if ("page".equals(t.path("type").asText()) && t.path("browserContextId").asText().equals(real)) {
                                tabs.addObject().put("id", t.path("targetId").asText()).put("url", t.path("url").asText()).put("title", t.path("title").asText());
                            }
                        }
                    }
                }
                case PLAYWRIGHT -> host.pw().call(c -> {
                    int n = 0;
                    for (BrowserContext context : c.browser().contexts()) {
                        ObjectNode entry = contexts.addObject().put("id", "pw-" + n++);
                        ArrayNode tabs = entry.putArray("tabs");
                        for (Page page : context.pages()) tabs.addObject().put("url", page.url());
                    }
                    return null;
                });
                default -> throw new UnsupportedProtocolOperation("contexts", backend.id(), null);
            }
            return out;
        });
        return served.value().put("via", served.via().id());
    }

    static ObjectNode contextClose(ProtoHost host, Map<String, String> f) {
        String id = f.getOrDefault("id", "");
        if (id.isBlank() || "default".equals(id)) throw new IllegalArgumentException("error: context-close needs --id=<id> (not the default context)\nfix: context-list shows the ids");
        BrowserProtocols.Served<String> served = host.protocols().serve("contexts", f.get("via"), backend -> {
            switch (backend) {
                case PLAYWRIGHT -> {
                    Map<String, BrowserContext> made = host.state("contexts.pw", ConcurrentHashMap::new);
                    BrowserContext context = made.remove(id);
                    if (context == null) throw new IllegalArgumentException("error: no Playwright context " + id + "\nfix: context-list");
                    host.pw().call(c -> {
                        context.close();
                        return null;
                    });
                }
                case BIDI -> host.bidiModules().removeUserContext(id);
                case CDP -> host.cdp().browser("Target.disposeBrowserContext", Map.of("browserContextId", id));
                default -> throw new UnsupportedProtocolOperation("contexts", backend.id(), null);
            }
            return backend.id();
        });
        host.state("contexts.made", java.util.concurrent.CopyOnWriteArrayList<String>::new).removeIf(e -> e.endsWith(":" + id));
        return ProtoJson.obj().put("op", "context-close").put("via", served.value()).put("id", id);
    }

    // ---- tabs and history ----

    static ObjectNode tabNew(ProtoHost host, Map<String, String> f) {
        String url = f.getOrDefault("url", "about:blank");
        String context = f.get("context");
        boolean activate = activateRequested(f.get("activate"));
        String previous = "";
        if (!activate && host.hasCdp()) {
            try {
                CdpSession.Target page = host.cdp().page(f.get("page"));
                if (page != null && page.targetId != null) previous = page.targetId;
            } catch (RuntimeException ignored) {
                previous = "";
            }
        }
        final String remembered = previous;
        BrowserProtocols.Served<String> served = host.protocols().serve("tabs", f.get("via"), backend -> {
            switch (backend) {
                case BIDI -> {
                    String id = host.bidiModules().create("tab", context).path("context").asText();
                    if (!"about:blank".equals(url)) host.bidiModules().navigate(id, url, "complete");
                    return id;
                }
                case CDP -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("url", url);
                    if (context != null && !"default".equals(context)) p.put("browserContextId", context);
                    String id;
                    if (!activate) p.put("background", Boolean.TRUE);
                    try {
                        id = host.cdp().browser("Target.createTarget", p).path("targetId").asText();
                    } catch (RuntimeException rejected) {
                        if (activate || !p.containsKey("background")) throw rejected;
                        p.remove("background");
                        id = host.cdp().browser("Target.createTarget", p).path("targetId").asText();
                    }
                    if (activate) {
                        try {
                            host.cdp().browser("Target.activateTarget", Map.of("targetId", id));
                        } catch (RuntimeException ignored) {
                            // The new tab exists even when activate is refused.
                        }
                        host.cdp().rememberProtocol(id);
                        host.cdp().rememberVisible(id);
                    } else if (!remembered.isBlank()) {
                        try {
                            host.cdp().browser("Target.activateTarget", Map.of("targetId", remembered));
                        } catch (RuntimeException ignored) {
                            // background:true already left the previous tab in front.
                        }
                        host.cdp().rememberProtocol(remembered);
                        host.cdp().rememberVisible(remembered);
                    }
                    return id;
                }
                case PLAYWRIGHT -> {
                    return host.pw().call(c -> {
                        Map<String, BrowserContext> made = host.state("contexts.pw", ConcurrentHashMap::new);
                        BrowserContext ctx = context != null && made.containsKey(context) ? made.get(context) : c.browser().contexts().get(0);
                        Page page = ctx.newPage();
                        if (!"about:blank".equals(url)) page.navigate(url);
                        return "pw-page:" + System.identityHashCode(page);
                    });
                }
                default -> throw new UnsupportedProtocolOperation("tabs", backend.id(), null);
            }
        });
        String protocolTarget = served.value();
        if (host.cdpAttached()) {
            String preferred = host.cdp().protocolPageId();
            if (preferred != null && !preferred.isBlank()) protocolTarget = preferred;
        }
        return ProtoJson.obj().put("op", "tab-new").put("via", served.via().id()).put("id", served.value()).put("url", url)
                .put("context", context == null ? "default" : context)
                .put("activate", activate)
                .put("protocolTarget", protocolTarget);
    }

    /** Absent, blank, and anything other than true/1/yes leave the new tab in the background. */
    static boolean activateRequested(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(value) || "1".equals(value) || "yes".equals(value);
    }

    /** The tab whose URL contains {@code page}. A miss names the missing substring. */
    static String tabForPage(java.util.List<CdpSession.Target> pages, String page) {
        if (page == null || page.isBlank()) {
            throw new IllegalArgumentException("error: tab-activate needs --id=<tab id> or --page=<url substring>\nfix: context-list shows the tab ids and urls");
        }
        if (pages != null) {
            for (CdpSession.Target target : pages) {
                String url = target.url == null ? "" : target.url;
                if (url.contains(page) && target.targetId != null && !target.targetId.isBlank()) return target.targetId;
            }
        }
        throw new IllegalArgumentException("error: no tab url contains \"" + page + "\"\nfix: context-list shows the tab urls");
    }

    static ObjectNode tabActivate(ProtoHost host, Map<String, String> f) {
        String raw = f.getOrDefault("id", "");
        if (raw.isBlank() && f.get("page") != null && !f.get("page").isBlank()) {
            raw = tabForPage(host.cdp().pages(), f.get("page"));
        }
        if (raw.isBlank()) throw new IllegalArgumentException("error: tab-activate needs --id=<tab id> or --page=<url substring>\nfix: context-list shows the tab ids and urls");
        String id = raw;
        BrowserProtocols.Served<String> served = host.protocols().serve("tabs", f.get("via"), backend -> {
            switch (backend) {
                case BIDI -> host.bidiModules().activate(id);
                case CDP -> {
                    host.cdp().browser("Target.activateTarget", Map.of("targetId", id));
                    host.cdp().rememberProtocol(id);
                    host.cdp().rememberVisible(id);
                }
                default -> throw new UnsupportedProtocolOperation("tabs", backend.id(), null);
            }
            return backend.id();
        });
        return ProtoJson.obj().put("op", "tab-activate").put("via", served.value()).put("id", id);
    }

    static ObjectNode history(ProtoHost host, Map<String, String> f) {
        String action = f.containsKey("back") ? "back" : f.containsKey("forward") ? "forward" : f.containsKey("reload") ? "reload" : null;
        if (action == null) throw new IllegalArgumentException("error: page-history needs --back, --forward or --reload[=hard]\nfix: page-history --back");
        boolean hard = "hard".equals(f.get("reload"));
        BrowserProtocols.Served<String> served = host.protocols().serve("history", f.get("via"), backend -> {
            switch (backend) {
                case BIDI -> {
                    String context = bidiContext(host, f);
                    if ("reload".equals(action)) host.bidiModules().reload(context, hard);
                    else host.bidiModules().traverseHistory(context, "back".equals(action) ? -1 : 1);
                }
                case CDP -> {
                    CdpSession.Target t = target(host, f);
                    if ("reload".equals(action)) host.cdp().send(t.sessionId, "Page.reload", Map.of("ignoreCache", hard));
                    else {
                        JsonNode history = host.cdp().send(t.sessionId, "Page.getNavigationHistory", Map.of());
                        int index = history.path("currentIndex").asInt() + ("back".equals(action) ? -1 : 1);
                        JsonNode entries = history.path("entries");
                        if (index < 0 || index >= entries.size()) {
                            throw new IllegalStateException("error: no history entry to go " + action + "\nfix: navigate first");
                        }
                        host.cdp().send(t.sessionId, "Page.navigateToHistoryEntry", Map.of("entryId", entries.get(index).path("id").asInt()));
                    }
                }
                case PLAYWRIGHT -> host.pw().call(c -> {
                    Page page = PwBridge.page(c, f.get("page"));
                    switch (action) {
                        case "back" -> page.goBack();
                        case "forward" -> page.goForward();
                        default -> page.reload();
                    }
                    return null;
                });
                default -> throw new UnsupportedProtocolOperation("history", backend.id(), null);
            }
            return backend.id();
        });
        return ProtoJson.obj().put("op", "page-history").put("via", served.value()).put("action", action + (hard ? "=hard" : ""));
    }

    static ObjectNode print(ProtoHost host, Map<String, String> f) throws Exception {
        Path out = out(host, f.get("out"), ".pickleball/runs/" + host.run() + "/print-" + System.currentTimeMillis() + ".pdf");
        BrowserProtocols.Served<byte[]> served = host.protocols().serve("print", f.get("via"), backend -> switch (backend) {
            case PLAYWRIGHT -> host.pw().call(c -> PwBridge.page(c, f.get("page")).pdf());
            case BIDI -> Base64.getDecoder().decode(host.bidiModules().print(bidiContext(host, f)).path("data").asText());
            case CDP -> Base64.getDecoder().decode(host.cdp().send(target(host, f).sessionId, "Page.printToPDF", Map.of("printBackground", true)).path("data").asText());
            default -> throw new UnsupportedProtocolOperation("print", backend.id(), null);
        });
        Files.write(out, served.value());
        return ProtoJson.obj().put("op", "print").put("via", served.via().id()).put("out", out.toString()).put("bytes", served.value().length);
    }

    // ---- downloads ----

    static final class Downloads {
        volatile Path dir;
        volatile boolean deny;
        final Map<String, ObjectNode> seen = new ConcurrentHashMap<>();
        volatile String via;
    }

    static Downloads downloadState(ProtoHost host) {
        return host.state("downloads", Downloads::new);
    }

    static ObjectNode downloads(ProtoHost host, Map<String, String> f) throws Exception {
        Downloads d = downloadState(host);
        if (f.containsKey("list")) return list(d);
        if (f.containsKey("wait")) {
            long deadline = System.currentTimeMillis() + EventCommands.parseInt(f.get("timeout"), 15_000);
            int before = (int) d.seen.values().stream().filter(r -> "completed".equals(r.path("state").asText())).count();
            int known = EventCommands.parseInt(f.get("count"), before == 0 ? 1 : before);
            while (System.currentTimeMillis() < deadline) {
                long done = d.seen.values().stream().filter(r -> "completed".equals(r.path("state").asText())).count();
                if (done >= known) return list(d).put("waited", true);
                sleep(100);
            }
            throw new IllegalStateException("error: no download finished in time\nfix: downloads --list, or raise --timeout");
        }
        String rel = f.getOrDefault("dir", ".pickleball/runs/" + callerRun(f, host.run()) + "/downloads");
        Path dir = host.project().resolve(rel).normalize();
        if (!dir.startsWith(host.project().normalize())) throw new IllegalArgumentException("error: --dir must stay inside the project\nfix: downloads --dir=downloads");
        Files.createDirectories(dir);
        d.dir = dir;
        d.deny = f.containsKey("deny");
        BrowserProtocols.Served<String> served = host.protocols().serve("downloads", f.get("via"), backend -> {
            switch (backend) {
                case CDP -> {
                    host.cdp().browser("Browser.setDownloadBehavior", d.deny ? Map.of("behavior", "deny", "eventsEnabled", true)
                            : Map.of("behavior", "allow", "downloadPath", dir.toAbsolutePath().toString(), "eventsEnabled", true));
                    if (host.stateMap().putIfAbsent("downloads.cdp", true) == null) {
                        RpcChannel.Listener l = (method, params, session) -> {
                            if ("Browser.downloadWillBegin".equals(method)) {
                                ObjectNode r = downloadRow(host, d, "cdp", "began", params.path("guid").asText(), params.path("url").asText(),
                                        params.path("suggestedFilename").asText());
                                r.put("state", "inProgress");
                            } else if ("Browser.downloadProgress".equals(method)) {
                                String state = params.path("state").asText();
                                ObjectNode known = d.seen.get(params.path("guid").asText());
                                if (known != null && !"inProgress".equals(state)) {
                                    known.put("state", state).put("bytes", params.path("receivedBytes").asLong());
                                    host.hub().add(host.hub().row("cdp", "download", state).put("url", known.path("url").asText())
                                            .put("text", known.path("file").asText()).put("requestId", params.path("guid").asText()));
                                }
                            }
                        };
                        host.cdp().channel().listen(l);
                    }
                }
                case BIDI -> {
                    host.bidiModules().setDownloadBehavior(dir.toAbsolutePath().toString(), d.deny);
                    if (host.stateMap().putIfAbsent("downloads.bidi", true) == null) {
                        host.bidiModules().subscribe(List.of("browsingContext.downloadWillBegin", "browsingContext.downloadEnd"), null);
                        host.bidi().listen((method, params, session) -> {
                            if ("browsingContext.downloadWillBegin".equals(method)) {
                                downloadRow(host, d, "bidi", "began", params.path("navigation").asText(), params.path("url").asText(),
                                        params.path("suggestedFilename").asText()).put("state", "inProgress");
                            } else if ("browsingContext.downloadEnd".equals(method)) {
                                ObjectNode known = d.seen.get(params.path("navigation").asText());
                                String state = params.path("status").asText("complete").startsWith("complete") ? "completed" : "canceled";
                                if (known != null) {
                                    known.put("state", state);
                                    if (params.has("filepath")) known.put("file", params.path("filepath").asText());
                                    host.hub().add(host.hub().row("bidi", "download", state).put("url", known.path("url").asText()).put("text", known.path("file").asText()));
                                }
                            }
                        });
                    }
                }
                case PLAYWRIGHT -> {
                    if (d.deny) throw new IllegalStateException("error: unsupported: downloads --deny on playwright\nfix: use --via=cdp or --via=bidi");
                    host.pw().call(c -> {
                        for (BrowserContext context : c.browser().contexts()) {
                            for (Page page : context.pages()) watch(host, d, page);
                            context.onPage(page -> watch(host, d, page));
                        }
                        return null;
                    });
                    host.pw().listening(true);
                }
                default -> throw new UnsupportedProtocolOperation("downloads", backend.id(), null);
            }
            return backend.id();
        });
        d.via = served.value();
        return ProtoJson.obj().put("op", "downloads").put("via", served.value()).put("dir", dir.toString()).put("deny", d.deny);
    }

    private static void watch(ProtoHost host, Downloads d, Page page) {
        page.onDownload(download -> {
            String id = "pw-dl:" + System.identityHashCode(download);
            ObjectNode r = downloadRow(host, d, "playwright", "began", id, download.url(), download.suggestedFilename());
            Path file = d.dir.resolve(download.suggestedFilename());
            download.saveAs(file);
            r.put("state", "completed").put("file", file.toString());
            host.hub().add(host.hub().row("playwright", "download", "completed").put("url", download.url()).put("text", file.toString()));
        });
    }

    private static ObjectNode downloadRow(ProtoHost host, Downloads d, String source, String subkind, String id, String url, String name) {
        Path file = d.dir == null ? Path.of(name) : d.dir.resolve(name);
        ObjectNode known = ProtoJson.obj().put("id", id).put("url", url).put("file", file.toString()).put("source", source);
        d.seen.put(id, known);
        host.hub().add(host.hub().row(source, "download", subkind).put("url", url).put("text", file.toString()).put("requestId", id));
        return known;
    }

    private static ObjectNode list(Downloads d) {
        ObjectNode out = ProtoJson.obj().put("op", "downloads").put("via", d.via == null ? "none" : d.via);
        ArrayNode items = out.putArray("downloads");
        d.seen.values().forEach(items::add);
        return out;
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static Map<String, Object> p(Object... pairs) {
        return BidiModules.p(pairs);
    }

    static boolean isBackend(BrowserProtocols.Served<?> served, Backend backend) {
        return served.via() == backend;
    }

    /** HCR-14: the calling run id ({@code _run} from the CLI, or {@code --run-id}), else the host's own run. */
    static String callerRun(Map<String, String> f, String hostRun) {
        for (String key : List.of("_run", "run-id")) {
            String value = f == null ? null : f.get(key);
            if (value != null && value.trim().matches("[A-Za-z0-9._-]{1,64}")) return value.trim();
        }
        return hostRun;
    }
}
