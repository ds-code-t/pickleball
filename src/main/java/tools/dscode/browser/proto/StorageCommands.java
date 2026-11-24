package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.options.Cookie;
import tools.dscode.browser.BidiModules;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E7 storage: {@code storage --get|--set|--clear [--kind=cookies,local,session,idb,opfs] [--origin=] [--reveal]} and
 * {@code storage-state --save|--load}. Cookie values are redacted unless {@code --reveal}; storage-state files are
 * owner-only and never logged.
 */
final class StorageCommands {
    static final List<String> KINDS = List.of("cookies", "local", "session", "idb", "opfs");

    static final String GET_JS = "(async (kinds)=>{const out={};"
            + "if(kinds.includes('local'))out.local=Object.fromEntries(Object.entries(localStorage));"
            + "if(kinds.includes('session'))out.session=Object.fromEntries(Object.entries(sessionStorage));"
            + "if(kinds.includes('idb')){out.idb={};for(const d of await indexedDB.databases()){"
            + "const db=await new Promise((res,rej)=>{const r=indexedDB.open(d.name);r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)});"
            + "const stores={};for(const s of Array.from(db.objectStoreNames)){stores[s]=await new Promise(res=>{const all={};"
            + "const c=db.transaction(s,'readonly').objectStore(s).openCursor();c.onsuccess=()=>{const cur=c.result;if(cur){all[String(cur.key)]=cur.value;cur.continue()}else res(all)}})}"
            + "db.close();out.idb[d.name]=stores}}"
            + "if(kinds.includes('opfs')){out.opfs={};try{const root=await navigator.storage.getDirectory();"
            + "for await(const [name,h] of root.entries()){if(h.kind==='file')out.opfs[name]=await (await h.getFile()).text()}}catch(e){out.opfsError=String(e)}}"
            + "return JSON.stringify(out)})";

    static final String SET_JS = "(async (data)=>{"
            + "if(data.local)for(const [k,v] of Object.entries(data.local))localStorage.setItem(k,String(v));"
            + "if(data.session)for(const [k,v] of Object.entries(data.session))sessionStorage.setItem(k,String(v));"
            + "if(data.idb)for(const [name,stores] of Object.entries(data.idb)){"
            + "const v0=await new Promise((res,rej)=>{const r=indexedDB.open(name);r.onsuccess=()=>{const v=r.result.version;r.result.close();res(v)};r.onerror=()=>rej(r.error)});"
            + "const db=await new Promise((res,rej)=>{const r=indexedDB.open(name,v0+1);r.onupgradeneeded=()=>{for(const s of Object.keys(stores))if(!r.result.objectStoreNames.contains(s))r.result.createObjectStore(s)};"
            + "r.onsuccess=()=>res(r.result);r.onerror=()=>rej(r.error)});"
            + "for(const [s,entries] of Object.entries(stores)){await new Promise((res,rej)=>{const tx=db.transaction(s,'readwrite');"
            + "for(const [k,v] of Object.entries(entries))tx.objectStore(s).put(v,k);tx.oncomplete=()=>res();tx.onerror=()=>rej(tx.error)})}db.close()}"
            + "if(data.opfs){const root=await navigator.storage.getDirectory();for(const [name,text] of Object.entries(data.opfs)){"
            + "const h=await root.getFileHandle(name,{create:true});const w=await h.createWritable();await w.write(String(text));await w.close()}}"
            + "return 'ok'})";

    static final String CLEAR_JS = "(async (kinds)=>{"
            + "if(kinds.includes('local'))localStorage.clear();if(kinds.includes('session'))sessionStorage.clear();"
            + "if(kinds.includes('idb'))for(const d of await indexedDB.databases())await new Promise(res=>{const r=indexedDB.deleteDatabase(d.name);r.onsuccess=r.onerror=r.onblocked=()=>res()});"
            + "if(kinds.includes('opfs')){const root=await navigator.storage.getDirectory();for await(const [name] of root.entries())await root.removeEntry(name,{recursive:true})}"
            + "return 'ok'})";

    private StorageCommands() {
    }

    static List<String> kinds(Map<String, String> f) {
        List<String> kinds = EventCommands.list(f.getOrDefault("kind", String.join(",", KINDS)));
        for (String k : kinds) {
            if (!KINDS.contains(k)) throw new IllegalArgumentException("error: unknown storage kind " + k + "\nfix: --kind is any of " + String.join(",", KINDS));
        }
        return kinds;
    }

    static String json(Object value) {
        try {
            return ProtoJson.MAPPER.writeValueAsString(value);
        } catch (Exception bad) {
            throw new IllegalStateException(bad);
        }
    }

    /** Runs a page script ({@code fn(arg)}) on the page for --tab/--page/--origin and returns its string result. */
    static String eval(ProtoHost host, Backend backend, Map<String, String> f, String fn, Object arg) {
        String expr = fn + "(" + json(arg) + ")";
        String origin = f.get("origin");
        Map<String, String> pick = new LinkedHashMap<>(f);
        if (origin != null && !pick.containsKey("page") && !pick.containsKey("tab")) pick.put("page", origin);
        switch (backend) {
            case CDP -> {
                CdpSession.Target t = ContextCommands.target(host, pick);
                JsonNode r = host.cdp().send(t.sessionId, "Runtime.evaluate", Map.of("expression", expr, "awaitPromise", true, "returnByValue", true));
                if (r.has("exceptionDetails")) {
                    throw new IllegalStateException("error: page script failed: " + r.path("exceptionDetails").path("exception").path("description").asText(r.path("exceptionDetails").path("text").asText())
                            + "\nfix: open a page on that origin (tab-new --url=<origin>) and retry");
                }
                return r.path("result").path("value").asText();
            }
            case BIDI -> {
                JsonNode r = host.bidiModules().evaluate(ContextCommands.bidiContext(host, pick), expr, null, true);
                if ("exception".equals(r.path("type").asText())) throw new IllegalStateException("error: page script failed: " + r.path("exceptionDetails").path("text").asText() + "\nfix: open a page on that origin");
                return r.path("result").path("value").asText();
            }
            case PLAYWRIGHT -> {
                return host.pw().call(c -> String.valueOf(PwBridge.page(c, pick.get("page")).evaluate(expr)));
            }
            default -> throw new UnsupportedProtocolOperation("storage", backend.id(), null);
        }
    }

    static String host(String origin) {
        try {
            return URI.create(origin).getHost();
        } catch (RuntimeException bad) {
            return origin;
        }
    }

    static ObjectNode storage(ProtoHost host, Map<String, String> f) throws Exception {
        String mode = f.containsKey("get") ? "get" : f.containsKey("set") ? "set" : f.containsKey("clear") ? "clear" : null;
        if (mode == null) throw new IllegalArgumentException("error: storage needs --get, --set=<json|@file> or --clear\nfix: storage --get --kind=cookies,local");
        List<String> kinds = kinds(f);
        boolean reveal = f.containsKey("reveal");
        JsonNode data = null;
        if ("set".equals(mode)) {
            String raw = f.get("set");
            if (raw.startsWith("@")) raw = Files.readString(host.project().resolve(raw.substring(1)).normalize());
            data = ProtoJson.MAPPER.readTree(raw);
        }
        JsonNode input = data;
        BrowserProtocols.Served<ObjectNode> served = host.protocols().serve("storage", f.get("via"), backend -> {
            ObjectNode out = ProtoJson.obj().put("op", "storage").put("mode", mode);
            if (kinds.contains("cookies")) cookies(host, backend, mode, input == null ? null : input.path("cookies"), f.get("origin"), reveal, out, f.get("context"));
            List<String> page = new ArrayList<>(kinds);
            page.remove("cookies");
            if (!page.isEmpty()) {
                switch (mode) {
                    case "get" -> {
                        try {
                            JsonNode got = ProtoJson.MAPPER.readTree(eval(host, backend, f, GET_JS, page));
                            got.fields().forEachRemaining(e -> out.set(e.getKey(), e.getValue()));
                        } catch (java.io.IOException bad) {
                            throw new IllegalStateException(bad);
                        }
                    }
                    case "set" -> {
                        ObjectNode filtered = ProtoJson.obj();
                        for (String k : page) if (input.has(k)) filtered.set(k, input.get(k));
                        eval(host, backend, f, SET_JS, filtered);
                        out.put("set", String.join(",", iterable(filtered)));
                    }
                    default -> {
                        eval(host, backend, f, CLEAR_JS, page);
                        out.put("cleared", String.join(",", page));
                    }
                }
            }
            return out;
        });
        if (reveal) host.log("storage " + mode + " --reveal (values not logged) via=" + served.via().id());
        else host.log("storage " + mode + " " + kinds + " via=" + served.via().id());
        return served.value().put("via", served.via().id()).put("revealed", reveal);
    }

    private static List<String> iterable(ObjectNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static void cookies(ProtoHost host, Backend backend, String mode, JsonNode input, String origin, boolean reveal, ObjectNode out, String context) {
        String domain = origin == null ? null : host(origin);
        switch (mode) {
            case "get" -> {
                ArrayNode list = out.putArray("cookies");
                for (JsonNode c : readCookies(host, backend, context)) {
                    if (domain != null && !c.path("domain").asText().replaceFirst("^\\.", "").endsWith(domain) && !domain.endsWith(c.path("domain").asText().replaceFirst("^\\.", ""))) continue;
                    ObjectNode copy = c.deepCopy();
                    if (!reveal) copy.put("value", ContextCommands.REDACTED);
                    list.add(copy);
                }
            }
            case "set" -> {
                if (input == null || !input.isArray()) return;
                writeCookies(host, backend, input, context);
                out.put("cookiesSet", input.size());
            }
            default -> {
                switch (backend) {
                    case CDP -> host.cdp().browser("Storage.clearCookies", Map.of());
                    case BIDI -> host.bidiModules().deleteCookies(domain == null ? null : BidiModules.p("domain", domain), null);
                    case PLAYWRIGHT -> host.pw().call(c -> {
                        for (BrowserContext bc : c.browser().contexts()) bc.clearCookies();
                        return null;
                    });
                    default -> throw new UnsupportedProtocolOperation("storage", backend.id(), null);
                }
                out.put("cookiesCleared", true);
            }
        }
    }

    /** Cookies in Playwright's storage-state shape (name, value, domain, path, expires, httpOnly, secure, sameSite). */
    static List<ObjectNode> readCookies(ProtoHost host, Backend backend) {
        return readCookies(host, backend, null);
    }

    /** {@code context}: a context id from context-new (CDP browserContextId or BiDi userContext); null is the default. */
    static List<ObjectNode> readCookies(ProtoHost host, Backend backend, String context) {
        List<ObjectNode> out = new ArrayList<>();
        switch (backend) {
            case CDP -> {
                for (JsonNode c : host.cdp().browser("Storage.getCookies", context == null ? Map.of() : Map.of("browserContextId", context)).path("cookies")) {
                    out.add(ProtoJson.obj().put("name", c.path("name").asText()).put("value", c.path("value").asText()).put("domain", c.path("domain").asText())
                            .put("path", c.path("path").asText("/")).put("expires", c.path("expires").asDouble(-1)).put("httpOnly", c.path("httpOnly").asBoolean())
                            .put("secure", c.path("secure").asBoolean()).put("sameSite", c.path("sameSite").asText("Lax")));
                }
            }
            case BIDI -> {
                for (JsonNode c : host.bidiModules().getCookies(null, context == null ? null : BidiModules.p("type", "storageKey", "userContext", context)).path("cookies")) {
                    String same = c.path("sameSite").asText("lax");
                    out.add(ProtoJson.obj().put("name", c.path("name").asText()).put("value", c.path("value").path("value").asText()).put("domain", c.path("domain").asText())
                            .put("path", c.path("path").asText("/")).put("expires", c.has("expiry") ? c.path("expiry").asDouble() : -1).put("httpOnly", c.path("httpOnly").asBoolean())
                            .put("secure", c.path("secure").asBoolean()).put("sameSite", same.isEmpty() ? "Lax" : Character.toUpperCase(same.charAt(0)) + same.substring(1)));
                }
            }
            case PLAYWRIGHT -> host.pw().call(c -> {
                for (Cookie k : c.browser().contexts().get(0).cookies()) {
                    out.add(ProtoJson.obj().put("name", k.name).put("value", k.value).put("domain", k.domain).put("path", k.path).put("expires", k.expires == null ? -1 : k.expires)
                            .put("httpOnly", Boolean.TRUE.equals(k.httpOnly)).put("secure", Boolean.TRUE.equals(k.secure)).put("sameSite", k.sameSite == null ? "Lax" : k.sameSite.name()));
                }
                return null;
            });
            default -> throw new UnsupportedProtocolOperation("storage", backend.id(), null);
        }
        return out;
    }

    static void writeCookies(ProtoHost host, Backend backend, JsonNode cookies, String context) {
        switch (backend) {
            case CDP -> {
                List<Map<String, Object>> list = new ArrayList<>();
                for (JsonNode c : cookies) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", c.path("name").asText());
                    m.put("value", c.path("value").asText());
                    if (c.has("url")) m.put("url", c.path("url").asText());
                    else {
                        m.put("domain", c.path("domain").asText());
                        m.put("path", c.path("path").asText("/"));
                    }
                    if (c.path("expires").asDouble(-1) > 0) m.put("expires", c.path("expires").asDouble());
                    if (c.path("httpOnly").asBoolean()) m.put("httpOnly", true);
                    if (c.path("secure").asBoolean()) m.put("secure", true);
                    list.add(m);
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("cookies", list);
                if (context != null) p.put("browserContextId", context);
                host.cdp().browser("Storage.setCookies", p);
            }
            case BIDI -> {
                for (JsonNode c : cookies) {
                    String domain = c.has("url") ? host(c.path("url").asText()) : c.path("domain").asText();
                    Map<String, Object> cookie = BidiModules.p("name", c.path("name").asText(), "value", BidiModules.p("type", "string", "value", c.path("value").asText()),
                            "domain", domain, "path", c.path("path").asText("/"));
                    host.bidiModules().setCookie(cookie, context == null ? null : BidiModules.p("type", "storageKey", "userContext", context));
                }
            }
            case PLAYWRIGHT -> host.pw().call(c -> {
                List<Cookie> list = new ArrayList<>();
                for (JsonNode k : cookies) {
                    Cookie cookie = new Cookie(k.path("name").asText(), k.path("value").asText());
                    if (k.has("url")) cookie.setUrl(k.path("url").asText());
                    else cookie.setDomain(k.path("domain").asText()).setPath(k.path("path").asText("/"));
                    list.add(cookie);
                }
                c.browser().contexts().get(0).addCookies(list);
                return null;
            });
            default -> throw new UnsupportedProtocolOperation("storage", backend.id(), null);
        }
    }

    // ---- storage-state ----

    static ObjectNode storageState(ProtoHost host, Map<String, String> f) throws Exception {
        String save = f.get("save");
        String load = f.get("load");
        if ((save == null) == (load == null)) throw new IllegalArgumentException("error: storage-state needs --save=<file> or --load=<file>\nfix: storage-state --save=state.json");
        Path file = host.project().resolve(save != null ? save : load).normalize();
        BrowserProtocols.Served<Integer> served = host.protocols().serve("storage-state", f.get("via"), backend -> {
            try {
                if (save != null) {
                    Files.createDirectories(file.toAbsolutePath().getParent());
                    if (backend == Backend.PLAYWRIGHT) {
                        host.pw().call(c -> c.browser().contexts().get(0).storageState(new BrowserContext.StorageStateOptions().setPath(file)));
                        ProtoHost.ownerOnly(file);
                        return ProtoJson.MAPPER.readTree(file.toFile()).path("cookies").size();
                    }
                    ObjectNode state = ProtoJson.obj();
                    ArrayNode cookies = state.putArray("cookies");
                    readCookies(host, backend).forEach(cookies::add);
                    ArrayNode origins = state.putArray("origins");
                    for (String origin : openOrigins(host, backend)) {
                        Map<String, String> pick = Map.of("page", origin);
                        JsonNode got = ProtoJson.MAPPER.readTree(eval(host, backend, pick, GET_JS, List.of("local")));
                        ObjectNode o = origins.addObject().put("origin", origin);
                        ArrayNode ls = o.putArray("localStorage");
                        got.path("local").fields().forEachRemaining(e -> ls.addObject().put("name", e.getKey()).put("value", e.getValue().asText()));
                    }
                    Files.writeString(file, state.toString());
                    ProtoHost.ownerOnly(file);
                    return cookies.size();
                }
                JsonNode state = ProtoJson.MAPPER.readTree(file.toFile());
                if (backend == Backend.PLAYWRIGHT) {
                    host.pw().call(c -> {
                        c.browser().contexts().get(0).setStorageState(file);
                        return null;
                    });
                } else if (backend == Backend.CDP) loadCdp(host, state, null);
                else loadBidi(host, state, null);
                return state.path("cookies").size();
            } catch (java.io.IOException io) {
                throw new IllegalStateException("error: storage-state " + (save != null ? "save" : "load") + " failed: " + io.getMessage() + "\nfix: check the file path");
            }
        });
        host.log("storage-state " + (save != null ? "save" : "load") + " via=" + served.via().id());
        return ProtoJson.obj().put("op", "storage-state").put("via", served.via().id()).put(save != null ? "saved" : "loaded", file.toString())
                .put("cookies", served.value()).put("note", "storage-state files hold live session secrets; keep them out of version control");
    }

    static List<String> openOrigins(ProtoHost host, Backend backend) {
        List<String> out = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        if (backend == Backend.CDP) for (CdpSession.Target t : RouteCommands.pages(host)) urls.add(t.url);
        else for (JsonNode c : host.bidiModules().getTree().path("contexts")) urls.add(c.path("url").asText());
        for (String url : urls) {
            if (url == null || !url.startsWith("http")) continue;
            URI uri = URI.create(url);
            String origin = uri.getScheme() + "://" + uri.getAuthority();
            if (!out.contains(origin)) out.add(origin);
        }
        return out;
    }

    /** Cookies into {@code context} (null = default), and localStorage through a short-lived tab per origin. */
    static void loadCdp(ProtoHost host, JsonNode state, String context) {
        if (state.path("cookies").size() > 0) writeCookies(host, Backend.CDP, state.path("cookies"), context);
        for (JsonNode origin : state.path("origins")) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("url", origin.path("origin").asText());
            if (context != null) p.put("browserContextId", context);
            p.put("background", true);
            String id = host.cdp().browser("Target.createTarget", p).path("targetId").asText();
            try {
                ObjectNode local = ProtoJson.obj();
                for (JsonNode kv : origin.path("localStorage")) local.put(kv.path("name").asText(), kv.path("value").asText());
                waitLoaded(host, id);
                eval(host, Backend.CDP, Map.of("tab", id), SET_JS, ProtoJson.obj().set("local", local));
            } finally {
                host.cdp().browser("Target.closeTarget", Map.of("targetId", id));
            }
        }
    }

    private static void waitLoaded(ProtoHost host, String targetId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (CdpSession.Target t : host.cdp().pages()) {
                if (t.targetId.equals(targetId)) {
                    JsonNode r = host.cdp().send(t.sessionId, "Runtime.evaluate", Map.of("expression", "document.readyState+'|'+location.href", "returnByValue", true));
                    String v = r.path("result").path("value").asText();
                    if (v.startsWith("complete|http")) return;
                }
            }
            ContextCommands.sleep(100);
        }
    }

    static void loadBidi(ProtoHost host, JsonNode state, String userContext) {
        if (state.path("cookies").size() > 0) writeCookies(host, Backend.BIDI, state.path("cookies"), userContext);
        for (JsonNode origin : state.path("origins")) {
            String id = host.bidiModules().create("tab", userContext).path("context").asText();
            try {
                host.bidiModules().navigate(id, origin.path("origin").asText(), "complete");
                ObjectNode local = ProtoJson.obj();
                for (JsonNode kv : origin.path("localStorage")) local.put(kv.path("name").asText(), kv.path("value").asText());
                host.bidiModules().evaluate(id, SET_JS + "(" + json(ProtoJson.obj().set("local", local)) + ")", null, true);
            } finally {
                host.bidiModules().close(id);
            }
        }
    }
}
