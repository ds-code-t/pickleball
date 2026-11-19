package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.action.Observe;
import dev.domengine.action.PageActions;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cdp.CdpException;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.LiveXPath;
import dev.domengine.query.Query;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.SessionEvents;
import dev.domengine.snapshot.CompositeSnapshots;
import dev.domengine.snapshot.DesktopSnapshotProducer;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.tree.DomNode;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.composite.ElementOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Browser and desktop verbs on the existing engine path:
 * {@link Query#run}, {@link PageActions}, {@link CompositeSnapshots}, and {@link DesktopTree}.
 */
public final class TargetVerb {
    private static final Set<String> EVENT_CONDITIONS = Set.of(
            "mutation", "network", "load", "navigation", "mask"
    );
    private static final Set<String> INTERACTIVE = Set.of(
            "a", "button", "input", "select", "textarea", "option"
    );
    private static final ConcurrentHashMap<String, Hit> HITS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> BASELINE = new ConcurrentHashMap<>();

    private TargetVerb() {
    }

    public record Outcome(int exit, Map<String, Object> body) {
    }

    /** CSS or text selector for a fresh {@code ref:}. Null when the ref is unknown or stale. */
    public static String anchorSelector(String raw) {
        String ref = raw == null ? "" : raw.trim();
        if (!ref.startsWith("ref:")) ref = "ref:" + ref;
        Hit hit = HITS.get(ref);
        if (hit == null || hit.selector == null || hit.selector.isBlank()) return null;
        if (stale(hit)) return null;
        return hit.selector;
    }

    public static Outcome run(Path project, String runId, String op, Map<String, String> args) {
        String verb = op == null ? "" : op;
        Map<String, String> values = args == null ? Map.of() : args;
        try {
            TargetSelector.Parsed target = TargetSelector.parse(values.get("target"));
            if ("ref".equals(target.kind()) && values.get("ref") == null) {
                values = new LinkedHashMap<>(values);
                values.put("ref", target.raw());
            }
            return switch (verb) {
                case "attach" -> attach(project, runId, target, values);
                case "snap" -> snap(project, runId, target, values);
                case "find" -> find(project, runId, target, values);
                case "act" -> act(project, runId, target, values);
                case "wait" -> wait(project, runId, target, values);
                case "watch" -> watch(project, runId, target, values);
                case "explain" -> explain(project, runId, target, values);
                case "capture" -> capture(project, runId, target, values);
                case "eval" -> eval(project, runId, target, values);
                default -> fail(verb, "unknown agent command: " + verb);
            };
        } catch (Miss failure) {
            return fail(verb, failure.error);
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "failed" : failure.getMessage();
            if (message.contains("not found")) return fail(verb, "not found");
            if (message.contains("timeout")) return fail(verb, "timeout");
            return fail(verb, message);
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            return fail(verb, message);
        }
    }

    private static Outcome attach(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        if ("desktop".equals(target.kind())) {
            DesktopTree.Session session = openDesktop(target, args.get("fixture"));
            Map<String, Object> body = base("attach", "desktop", "desktop", target);
            body.put("session", session.id());
            body.put("docVersion", "desktop:" + session.sequence());
            return ok(body);
        }
        Held page = openBrowser(target);
        try {
            String version = docVersion(page.session.connection());
            Map<String, Object> body = base("attach", "cdp", "browser", target);
            body.put("cdp", page.session.socket());
            body.put("docVersion", version);
            body.put("session", page.session.id);
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome find(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        String query = blank(args.get("query"));
        if ("desktop".equals(target.kind()) || ("ref".equals(target.kind()) && desktopHit(args.get("ref")))) {
            return findDesktop(runId, target, args, query);
        }
        Held page = openBrowser(resolved(target, args));
        try {
            DomNode root = tree(page.session);
            List<DomNode> hits = locate(root, query, frameFlags(target));
            if (hits.isEmpty()) return fail("find", "not found");
            String version = docVersion(page.session.connection());
            List<Map<String, Object>> rows = publish(runId, "browser", page.session.id, version, hits, query);
            remember(runId, page.session);
            Map<String, Object> body = base("find", "cdp", "browser", target);
            body.put("docVersion", version);
            body.put("hits", rows);
            body.put("ref", rows.get(0).get("ref"));
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome findDesktop(String runId, TargetSelector.Parsed target, Map<String, String> args, String query) {
        DesktopTree.Session session = desktopSession(target, args);
        List<DomNode> hits = locate(session.root(), query, Map.of());
        if (hits.isEmpty()) return fail("find", "not found");
        String version = "desktop:" + session.sequence();
        List<Map<String, Object>> rows = publish(runId, "desktop", session.id(), version, hits, query);
        Map<String, Object> body = base("find", "desktop", "desktop", target);
        body.put("docVersion", version);
        body.put("hits", rows);
        body.put("ref", rows.get(0).get("ref"));
        body.put("session", session.id());
        return ok(body);
    }

    private static Outcome act(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        String ref = blank(args.get("ref"));
        if (ref.isBlank() && "ref".equals(target.kind())) ref = target.raw();
        Hit hit = ref.isBlank() ? null : requireHit(ref);
        if (hit != null && "desktop".equals(hit.kind)) return actDesktop(project, runId, hit, args);
        if ("desktop".equals(target.kind())) return actDesktop(project, runId, target, args);
        Held page = openBrowser(hit == null ? target : browserOf(hit, target));
        try {
            DomNode root = tree(page.session);
            DomNode node;
            String query = blank(args.get("query"));
            if (hit != null) {
                if (stale(hit, page.session.connection())) return fail("act", "stale_ref");
                node = findNode(root, "", hit.backend);
                if (node == null) return fail("act", "stale_ref");
                query = hit.query;
            } else {
                List<DomNode> hits = locate(root, query, frameFlags(target));
                if (hits.isEmpty()) return fail("act", "not found");
                if (hits.size() > 1) return fail("act", "ambiguous");
                node = hits.get(0);
            }
            String before = docVersion(page.session.connection());
            String titleBefore = title(page.session.connection());
            perform(project, runId, page.session, node, args, query, selector(node, query));
            String after = docVersion(page.session.connection());
            Map<String, Object> body = base("act", "cdp", "browser", target);
            body.put("docVersion", after);
            body.put("kind", blank(args.get("kind")).isBlank() ? "click" : blank(args.get("kind")));
            if ("diff".equals(blank(args.get("snap")))) {
                body.put("snap", diffOf(titleBefore, title(page.session.connection()), before, after));
            }
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome actDesktop(Path project, String runId, Hit hit, Map<String, String> args) {
        if (stale(hit)) return fail("act", "stale_ref");
        DesktopTree.Session session = DesktopTree.require(hit.sessionId);
        DomNode node = findNode(session.root(), "", hit.backend);
        if (node == null) return fail("act", "stale_ref");
        desktopAct(session, node, args);
        DesktopTree.Session after = DesktopTree.require(session.id());
        Map<String, Object> body = base("act", "desktop", "desktop", TargetSelector.parse("desktop:" + hit.sessionId));
        body.put("docVersion", "desktop:" + after.sequence());
        body.put("session", after.id());
        return ok(body);
    }

    private static Outcome actDesktop(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        DesktopTree.Session session = openDesktop(target, args.get("fixture"));
        String query = blank(args.get("query"));
        List<DomNode> hits = locate(session.root(), query, Map.of());
        if (hits.isEmpty()) return fail("act", "not found");
        if (hits.size() > 1) return fail("act", "ambiguous");
        desktopAct(session, hits.get(0), args);
        DesktopTree.Session after = DesktopTree.require(session.id());
        Map<String, Object> body = base("act", "desktop", "desktop", target);
        body.put("docVersion", "desktop:" + after.sequence());
        body.put("session", after.id());
        return ok(body);
    }

    private static void desktopAct(DesktopTree.Session session, DomNode node, Map<String, String> args) {
        String kind = blank(args.get("kind"));
        if (kind.isBlank()) kind = "click";
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("text", blank(args.get("value")));
        switch (kind) {
            case "type" -> DesktopTree.type(session.id(), flagsWithNode(session, node, flags));
            case "click", "hover", "press", "select" -> DesktopTree.click(session, node);
            default -> throw new Miss("unknown kind " + kind);
        }
    }

    private static Map<String, String> flagsWithNode(DesktopTree.Session session, DomNode node, Map<String, String> flags) {
        flags.put("node-id", Long.toString(node.backendNodeId));
        return flags;
    }

    private static Outcome snap(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        String mode = blank(args.get("mode"));
        if (mode.isBlank()) mode = "compact";
        if ("desktop".equals(target.kind())) return snapDesktop(project, runId, target, args, mode);
        Held page = openBrowser(resolved(target, args));
        try {
            ObjectNode produced = CompositeSnapshots.browser(page.session, SnapshotOptions.fromFlags(Map.of()));
            DomNode root = tree(page.session);
            String version = docVersion(page.session.connection());
            List<Map<String, Object>> rows = publish(runId, "browser", page.session.id, version, interactive(root), "");
            String picture = picture(page.session.connection(), rows);
            Map<String, Object> body = base("snap", "cdp", "browser", target);
            body.put("mode", mode);
            body.put("docVersion", version);
            body.put("doc_version", produced.path("doc_version").asText(version));
            body.put("hits", rows);
            if (!rows.isEmpty()) body.put("ref", rows.get(0).get("ref"));
            if ("full".equals(mode)) body.put("full", writeFull(project, runId, produced));
            else body.put("lines", lines(rows));
            if ("diff".equals(mode)) body.put("diff", diffAgainst(runId, picture, version));
            else BASELINE.put(baselineKey(runId), picture + "\n" + version);
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome snapDesktop(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args, String mode) {
        DesktopTree.Session session = desktopSession(target, args);
        String version = "desktop:" + session.sequence();
        List<Map<String, Object>> rows = publish(runId, "desktop", session.id(), version, interactive(session.root()), "");
        Map<String, Object> body = base("snap", "desktop", "desktop", target);
        body.put("mode", mode);
        body.put("docVersion", version);
        body.put("hits", rows);
        body.put("lines", lines(rows));
        if ("diff".equals(mode)) body.put("diff", diffAgainst(runId, String.join("\n", lines(rows)), version));
        return ok(body);
    }

    private static Outcome wait(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        String condition = blank(args.get("condition"));
        String mode = blank(args.get("mode"));
        if (mode.isBlank()) mode = EVENT_CONDITIONS.contains(condition) ? "event" : "poll";
        long timeout = timeoutMs(args);
        if ("desktop".equals(target.kind())) {
            DesktopTree.Session session = openDesktop(target, args.get("fixture"));
            String looked = condition.startsWith("text=") ? condition.substring("text=".length()) : blank(args.get("query"));
            if (!looked.isBlank() && locate(session.root(), looked, Map.of()).isEmpty()) return fail("wait", "not found");
            Map<String, Object> body = base("wait", "desktop", "desktop", target);
            body.put("mode", mode);
            body.put("condition", condition);
            body.put("docVersion", "desktop:" + session.sequence());
            return ok(body);
        }
        Held page = openBrowser(resolved(target, args));
        try {
            boolean met;
            if ("event".equals(mode)) met = waitEvent(project, runId, page.session.connection(), condition, timeout);
            else met = waitPoll(page.session.connection(), condition, blank(args.get("query")), timeout);
            if (!met) return fail("wait", "timeout");
            Map<String, Object> body = base("wait", "cdp", "browser", target);
            body.put("mode", mode);
            body.put("condition", condition);
            body.put("docVersion", docVersion(page.session.connection()));
            body.put("title", title(page.session.connection()));
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome watch(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        Held page = openBrowser(resolved(target, args));
        try {
            long duration = timeoutMs(args);
            if (args.get("duration") != null && !args.get("duration").isBlank()) duration = Long.parseLong(args.get("duration").trim());
            String kinds = blank(args.get("kinds"));
            if (kinds.isBlank()) kinds = blank(args.get("condition"));
            List<ObjectNode> events = page.session.events().read(
                    new SessionEvents.Filter(kinds, "", null, null, 50), Math.min(duration, 1000));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ObjectNode event : events) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("kind", event.path("kind").asText(event.path("method").asText("")));
                row.put("text", event.path("text").asText(""));
                rows.add(row);
            }
            Map<String, Object> body = base("watch", "cdp", "browser", target);
            body.put("mode", "event");
            body.put("events", rows);
            body.put("docVersion", docVersion(page.session.connection()));
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome explain(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        if ("desktop".equals(target.kind())) {
            DesktopTree.Session session = openDesktop(target, args.get("fixture"));
            List<DomNode> hits = locate(session.root(), blank(args.get("query")), Map.of());
            if (hits.isEmpty()) return fail("explain", "not found");
            Map<String, Object> body = base("explain", "desktop", "desktop", target);
            body.put("report", report(hits.get(0)));
            return ok(body);
        }
        Held page = openBrowser(resolved(target, args));
        try {
            String ref = blank(args.get("ref"));
            DomNode node;
            if (!ref.isBlank()) {
                Hit hit = requireHit(ref);
                if (stale(hit, page.session.connection())) return fail("explain", "stale_ref");
                node = findNode(tree(page.session), "", hit.backend);
            } else {
                List<DomNode> hits = locate(tree(page.session), blank(args.get("query")), frameFlags(target));
                node = hits.isEmpty() ? null : hits.get(0);
            }
            if (node == null) return fail("explain", "not found");
            Map<String, Object> body = base("explain", "cdp", "browser", target);
            body.put("report", report(node));
            body.put("docVersion", docVersion(page.session.connection()));
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome capture(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        String out = blank(args.get("out"));
        if (out.isBlank()) return fail("capture", "capture requires --out");
        Path file = Path.of(out);
        if (!file.isAbsolute() && project != null) file = project.resolve(out);
        if ("desktop".equals(target.kind())) {
            DesktopTree.Session session = openDesktop(target, args.get("fixture"));
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("out-dir", file.getParent() == null ? "." : file.getParent().toString());
            if (session.live()) {
                DesktopSnapshotProducer.produce(session, flags);
                Path written = file.getParent() == null ? Path.of("screenshot.png") : file.getParent().resolve("screenshot.png");
                try {
                    if (!written.equals(file) && Files.isRegularFile(written)) {
                        Files.move(written, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (java.io.IOException failure) {
                    return fail("capture", "capture: " + failure.getMessage());
                }
            } else {
                return fail("capture", "no desktop target " + session.id());
            }
            Map<String, Object> body = base("capture", "desktop", "desktop", target);
            body.put("path", file.toString());
            body.put("docVersion", "desktop:" + session.sequence());
            return ok(body);
        }
        Held page = openBrowser(resolved(target, args));
        try {
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put("out", file.toString());
            Observe.screenshot(page.session, flags);
            Map<String, Object> body = base("capture", "cdp", "browser", target);
            body.put("path", file.toString());
            body.put("docVersion", docVersion(page.session.connection()));
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome eval(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        if ("desktop".equals(target.kind())) return fail("eval", "eval is browser only");
        String expr = blank(args.get("expr"));
        if (expr.isBlank()) return fail("eval", "eval requires --expr");
        String world = blank(args.get("world"));
        if (world.isBlank()) world = "main";
        if (!"main".equals(world) && !"pkb".equals(world)) return fail("eval", "world is pkb or main");
        Held page = openBrowser(resolved(target, args));
        try {
            ObjectNode params = Jsons.object();
            params.put("expression", expr);
            params.put("returnByValue", true);
            if ("pkb".equals(world)) {
                ObjectNode created = Jsons.object();
                created.put("worldName", "pkb-surface");
                created.put("grantUniveralAccess", true);
                JsonNode worldNode = page.session.connection().call("Page.createIsolatedWorld", created, Duration.ofSeconds(5));
                int context = worldNode.path("executionContextId").asInt(0);
                if (context > 0) params.put("contextId", context);
            }
            JsonNode value = page.session.connection().call("Runtime.evaluate", params, Duration.ofSeconds(10));
            if (value.has("exceptionDetails")) return fail("eval", "eval failed");
            Map<String, Object> body = base("eval", "cdp", "browser", target);
            body.put("world", world);
            body.put("value", value.path("result").path("value").asText(""));
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static void perform(
            Path project,
            String runId,
            BrowserSession session,
            DomNode node,
            Map<String, String> args,
            String query,
            String selector
    ) {
        String kind = blank(args.get("kind"));
        if (kind.isBlank()) kind = "click";
        String verb = switch (kind) {
            case "type" -> "type";
            case "hover" -> "hover";
            case "press" -> "key";
            case "select", "click" -> "click";
            default -> throw new Miss("unknown kind " + kind);
        };
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("node-id", Long.toString(node.backendNodeId));
        if ("type".equals(verb)) flags.put("text", blank(args.get("value")));
        if ("key".equals(verb)) flags.put("key", blank(args.get("value")));
        boolean disabled = "off".equalsIgnoreCase(blank(args.get("cdp-input")));
        if (disabled) PageActions.useCdpInput(false);
        try {
            LiveXPath.use(session.connection());
            try {
                PageActions.act(session, verb, flags);
            } finally {
                LiveXPath.use(null);
            }
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            if (!disabled && !message.contains("cdp input") && !message.contains("Input.")) throw failure;
            FallbackLog.note(project, runId, "act", "cdp", "js", message.isBlank() ? "cdp input disabled" : message);
            if (!jsClick(query, selector)) throw failure;
        } finally {
            PageActions.useCdpInput(true);
        }
    }

    private static boolean jsClick(String query, String selector) {
        WebDriver driver = LiveBrowserHost.page();
        if (driver == null) return false;
        String script = "var q=arguments[0]; var sel=arguments[1];"
                + "var el=sel?document.querySelector(sel):null;"
                + "if(!el){var all=document.querySelectorAll('button,a,input,select,textarea,option,[role=button]');"
                + "for(var i=0;i<all.length;i++){var t=(all[i].innerText||all[i].value||'').replace(/\\s+/g,' ').trim();"
                + "if(t===q||all[i].id===q){el=all[i];break;}}}"
                + "if(!el) return false; el.setAttribute('data-pkb-synthetic','1'); el.click(); return true;";
        Object value = ElementOperations.script(driver, script, query == null ? "" : query, selector == null ? "" : selector);
        return Boolean.TRUE.equals(value);
    }

    private static boolean waitPoll(CdpConnection cdp, String condition, String query, long timeout) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        while (true) {
            if (conditionMet(cdp, condition, query)) return true;
            if (System.nanoTime() >= deadline) return false;
            sleep(50);
        }
    }

    private static boolean conditionMet(CdpConnection cdp, String condition, String query) {
        if (condition.startsWith("title=")) return condition.substring("title=".length()).equals(title(cdp));
        String text = condition.startsWith("text=") ? condition.substring("text=".length()) : query;
        if (text == null || text.isBlank()) text = condition;
        if (text.isBlank() || EVENT_CONDITIONS.contains(text)) return !title(cdp).isBlank();
        try {
            return !locate(tree(cdp), text, Map.of()).isEmpty();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean waitEvent(Path project, String runId, CdpConnection cdp, String condition, long timeout) {
        ObjectNode params = Jsons.object();
        params.put("awaitPromise", true);
        params.put("returnByValue", true);
        params.put("expression", ""
                + "(async function(){"
                + "var ui=window.__pkbUi;"
                + "if(ui&&typeof ui.watchUntil==='function'){"
                + "var hit=await ui.watchUntil(" + jsString(condition) + "," + timeout + ");"
                + "return 'watchUntil:'+JSON.stringify(hit);"
                + "}"
                + "return await new Promise(function(resolve){"
                + "var timer=setTimeout(function(){resolve('timeout');}," + timeout + ");"
                + "var obs=new MutationObserver(function(){clearTimeout(timer);obs.disconnect();resolve('mutation');});"
                + "obs.observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});"
                + "});"
                + "})()");
        JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofMillis(Math.max(1000, timeout + 2000)));
        String text = value.path("result").path("value").asText("");
        if (!text.contains("watchUntil") && text.contains("mutation") && !text.contains("timeout")) {
            FallbackLog.note(project, runId, "wait", "watcher", "mutation-observer", "pkb watchUntil was not on the page");
        }
        return !text.contains("timeout") && !text.isBlank();
    }

    private static boolean stale(Hit hit) {
        if (!"desktop".equals(hit.kind)) return false;
        DesktopTree.Session session = DesktopTree.open(hit.sessionId);
        if (session == null) return true;
        return !hit.docVersion.equals("desktop:" + session.sequence());
    }

    private static boolean stale(Hit hit, CdpConnection cdp) {
        if ("desktop".equals(hit.kind)) return stale(hit);
        return !hit.docVersion.equals(docVersion(cdp));
    }

    private static Hit requireHit(String ref) {
        String key = ref.startsWith("ref:") ? ref : "ref:" + ref;
        Hit hit = HITS.get(key);
        if (hit == null) throw new Miss("unknown target");
        return hit;
    }

    private static boolean desktopHit(String ref) {
        if (ref == null || ref.isBlank()) return false;
        Hit hit = HITS.get(ref.startsWith("ref:") ? ref : "ref:" + ref);
        return hit != null && "desktop".equals(hit.kind);
    }

    private static TargetSelector.Parsed resolved(TargetSelector.Parsed target, Map<String, String> args) {
        String ref = blank(args.get("ref"));
        if (!"ref".equals(target.kind()) && ref.isBlank()) return target;
        return target;
    }

    private static TargetSelector.Parsed browserOf(Hit hit, TargetSelector.Parsed target) {
        if (target != null && "browser".equals(target.kind())) return target;
        return TargetSelector.parse("browser:auto");
    }

    private static Held openBrowser(TargetSelector.Parsed target) {
        if (target == null || "none".equals(target.kind()) || target.raw().isBlank()) throw new Miss("no live browser");
        if ("desktop".equals(target.kind())) throw new Miss("no live browser");
        if ("ref".equals(target.kind())) return openBrowser(TargetSelector.parse("browser:auto"));
        if (!target.session().isBlank()) {
            BrowserSession host = LiveBrowserHost.chrome();
            if (host == null || (!target.session().equals(host.id) && !target.session().equals(LiveBrowserHost.sessionId()))) {
                throw new Miss("unknown target");
            }
            requireTab(host, target.tab());
            return new Held(host, false);
        }
        if (target.auto() || target.raw().isBlank()) {
            BrowserSession host = LiveBrowserHost.chrome();
            if (host == null) throw new Miss("no live browser");
            requireTab(host, target.tab());
            return new Held(host, false);
        }
        if (!target.cdp().isBlank()) {
            String pageId = pageId(target.tab());
            if (pageId.isBlank() && target.cdp().contains("/devtools/browser/")) {
                BrowserSession host = LiveBrowserHost.chrome();
                if (host != null && sameBrowser(host, target.cdp())) pageId = LiveBrowserHost.pageTargetId();
            }
            try {
                BrowserSession attached = BrowserSession.attach(
                        "cdp" + Long.toUnsignedString(System.nanoTime()),
                        target.cdp(),
                        false,
                        List.of(),
                        pageId.isBlank() ? null : pageId
                );
                requireTab(attached, target.tab());
                return new Held(attached, true);
            } catch (CdpException failure) {
                String message = failure.getMessage() == null ? "" : failure.getMessage();
                if (message.contains("no page target")) throw new Miss("unknown target");
                throw failure;
            }
        }
        throw new Miss("unknown target");
    }

    private static void requireTab(BrowserSession session, String tab) {
        if (tab == null || tab.isBlank()) return;
        if (tab.startsWith("title~")) {
            String title = title(session.connection());
            if (!title.toLowerCase(Locale.ROOT).contains(tab.substring("title~".length()).toLowerCase(Locale.ROOT))) {
                throw new Miss("unknown target");
            }
            return;
        }
        if (tab.startsWith("url~")) {
            String href = href(session.connection());
            if (!href.toLowerCase(Locale.ROOT).contains(tab.substring("url~".length()).toLowerCase(Locale.ROOT))) {
                throw new Miss("unknown target");
            }
            return;
        }
        String id = pageTarget(session);
        if (!tab.equals(id) && (session.socket() == null || !session.socket().contains("/devtools/page/" + tab))) {
            throw new Miss("unknown target");
        }
    }

    private static String pageId(String tab) {
        if (tab == null || tab.isBlank() || tab.startsWith("title~") || tab.startsWith("url~")) return "";
        return tab;
    }

    private static boolean sameBrowser(BrowserSession host, String cdp) {
        String left = host.cdp == null ? "" : host.cdp;
        int slash = left.indexOf("://");
        String hostPort = slash < 0 ? left : left.substring(slash + 3);
        int path = hostPort.indexOf('/');
        if (path > 0) hostPort = hostPort.substring(0, path);
        return cdp.contains(hostPort);
    }

    private static DesktopTree.Session desktopSession(TargetSelector.Parsed target, Map<String, String> args) {
        if ("ref".equals(target.kind())) {
            Hit hit = requireHit(blank(args.get("ref")).isBlank() ? target.raw() : args.get("ref"));
            if (!"desktop".equals(hit.kind)) throw new Miss("unknown target");
            DesktopTree.Session session = DesktopTree.open(hit.sessionId);
            if (session == null) throw new Miss("unknown target");
            return session;
        }
        return openDesktop(target, args.get("fixture"));
    }

    private static DesktopTree.Session openDesktop(TargetSelector.Parsed target, String fixture) {
        String label = !target.app().isBlank() ? target.app() : (!target.pid().isBlank() ? target.pid() : target.window());
        if (label.isBlank()) throw new Miss("no desktop target");
        if (!target.pid().isBlank() && (fixture == null || fixture.isBlank())) {
            long pid;
            try {
                pid = Long.parseLong(target.pid());
            } catch (NumberFormatException failure) {
                throw new Miss("unknown target");
            }
            if (ProcessHandle.of(pid).isEmpty()) throw new Miss("unknown target");
        }
        String id = "desk-" + label;
        DesktopTree.Session existing = DesktopTree.open(id);
        if (existing != null) return existing;
        if (fixture != null && !fixture.isBlank()) {
            DesktopTree.Session session = DesktopTree.attach(id, Path.of(fixture));
            if (!names(session.root(), label)) {
                DesktopTree.close(id);
                throw new Miss("unknown target");
            }
            return session;
        }
        try {
            String window = !target.window().isBlank() ? target.window() : target.app();
            return DesktopTree.attachLive(id, window);
        } catch (RuntimeException failure) {
            throw new Miss("no desktop target " + label);
        }
    }

    private static boolean names(DomNode node, String label) {
        if (node == null || label == null || label.isBlank()) return false;
        String name = node.accessibleName == null ? "" : node.accessibleName;
        if (label.equals(name)) return true;
        for (DomNode child : node.children) if (names(child, label)) return true;
        return false;
    }

    private static List<DomNode> locate(DomNode root, String query, Map<String, String> extra) {
        if (root != null) root.linkParents();
        Map<String, String> flags = new LinkedHashMap<>();
        if (extra != null) flags.putAll(extra);
        if (query == null || query.isBlank()) flags.put("pipeline", "css=a, button, input, select, textarea");
        else flags.put("pipeline", "text=\"" + query.replace("\"", "") + "\"");
        List<DomNode> hits = new ArrayList<>();
        try {
            ObjectNode found = Query.run(root, flags);
            JsonNode nodes = found.get("nodes");
            if (nodes != null) {
                for (JsonNode node : nodes) {
                    DomNode match = findNode(root, node.path("handle").asText(""), 0);
                    if (match != null) hits.add(match);
                }
            }
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            if (!message.contains("not found") && !message.contains("query requires")) throw failure;
        }
        if (hits.isEmpty() && query != null && !query.isBlank()) hits.addAll(named(root, query));
        return deepest(hits);
    }

    private static List<DomNode> named(DomNode root, String query) {
        List<DomNode> hits = new ArrayList<>();
        walk(root, node -> {
            String name = node.accessibleName == null ? "" : node.accessibleName;
            String text = HtmlTree.textOf(node);
            if (query.equals(name) || query.equals(text) || query.equalsIgnoreCase(name)) hits.add(node);
        });
        return hits;
    }

    private static List<DomNode> interactive(DomNode root) {
        List<DomNode> hits = new ArrayList<>();
        walk(root, node -> {
            String tag = node.tag();
            String role = node.role == null ? "" : node.role.toLowerCase(Locale.ROOT);
            if (INTERACTIVE.contains(tag) || INTERACTIVE.contains(role) || "button".equals(role)) hits.add(node);
        });
        return hits;
    }

    private static List<DomNode> deepest(List<DomNode> nodes) {
        List<DomNode> kept = new ArrayList<>();
        for (DomNode node : nodes) {
            boolean ancestor = false;
            for (DomNode other : nodes) {
                if (other != node && contains(node, other)) {
                    ancestor = true;
                    break;
                }
            }
            if (!ancestor) kept.add(node);
        }
        return kept.isEmpty() ? nodes : kept;
    }

    private static boolean contains(DomNode parent, DomNode child) {
        for (DomNode one : parent.children) {
            if (one == child || contains(one, child)) return true;
        }
        return false;
    }

    private static DomNode findNode(DomNode node, String handle, long backend) {
        if (node == null) return null;
        if (!handle.isBlank() && handle.equals(node.id())) return node;
        if (backend > 0 && node.backendNodeId == backend) return node;
        for (DomNode child : node.children) {
            DomNode found = findNode(child, handle, backend);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = findNode(shadow, handle, backend);
            if (found != null) return found;
        }
        return null;
    }

    private static List<Map<String, Object>> publish(String runId, String kind, String sessionId, String version, List<DomNode> nodes, String query) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DomNode node : nodes) {
            String ref = "ref:" + version + ":" + node.backendNodeId;
            String selector = selector(node, query);
            HITS.put(ref, new Hit(kind, sessionId, node.backendNodeId, version, selector, query == null ? "" : query));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ref", ref);
            row.put("role", role(node));
            row.put("name", name(node));
            row.put("handle", node.backendNodeId);
            if (node.framePath != null && !node.framePath.isBlank()) row.put("frame", node.framePath);
            rows.add(row);
        }
        return rows;
    }

    private static String selector(DomNode node, String query) {
        if (node != null) {
            String id = node.attributes.get("id");
            if (id != null && !id.isBlank()) return "#" + id;
        }
        return query == null ? "" : query;
    }

    private static List<String> lines(List<Map<String, Object>> rows) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            lines.add("- " + row.get("role") + " \"" + row.get("name") + "\" [ref=" + row.get("ref") + "]");
        }
        return lines;
    }

    private static Map<String, Object> report(DomNode node) {
        Map<String, Object> report = new LinkedHashMap<>();
        boolean visible = node.hasBox && node.width > 0 && node.height > 0;
        boolean enabled = !"true".equalsIgnoreCase(node.attributes.getOrDefault("disabled", ""))
                && !"true".equalsIgnoreCase(node.attributes.getOrDefault("aria-disabled", ""));
        report.put("role", role(node));
        report.put("name", name(node));
        report.put("visible", visible);
        report.put("enabled", enabled);
        report.put("actionable", visible && enabled);
        report.put("reason", visible && enabled ? "visible and enabled" : (visible ? "disabled" : "not visible"));
        return report;
    }

    private static String role(DomNode node) {
        if (node.role != null && !node.role.isBlank()) return node.role;
        String tag = node.tag();
        return tag.isBlank() ? "element" : tag;
    }

    private static String name(DomNode node) {
        if (node.accessibleName != null && !node.accessibleName.isBlank()) return node.accessibleName;
        String text = HtmlTree.textOf(node);
        if (!text.isBlank()) return text.length() > 80 ? text.substring(0, 80) : text;
        String id = node.attributes.get("id");
        return id == null ? "" : id;
    }

    private static DomNode tree(BrowserSession session) {
        return tree(session.connection());
    }

    private static DomNode tree(CdpConnection cdp) {
        LiveXPath.use(cdp);
        try {
            DomNode root = dev.domengine.tree.LiveTree.read(cdp).root;
            if (root != null) root.linkParents();
            return root;
        } finally {
            LiveXPath.use(null);
        }
    }

    private static String docVersion(CdpConnection cdp) {
        ObjectNode params = Jsons.object();
        params.put("returnByValue", true);
        params.put("expression", "(function(){var root=document.documentElement;if(!root)return '0:0';"
                + "if(!window.__pkbDocWatch){window.__pkbDocCount=0;window.__pkbDocWatch=new MutationObserver(function(){window.__pkbDocCount++;});"
                + "window.__pkbDocWatch.observe(root,{subtree:true,childList:true,attributes:true,characterData:true});}"
                + "return location.href+':'+window.__pkbDocCount;})()");
        JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
        String text = value.path("result").path("value").asText("");
        return text.isBlank() ? "0:0" : text;
    }

    private static String title(CdpConnection cdp) {
        ObjectNode params = Jsons.object();
        params.put("returnByValue", true);
        params.put("expression", "document.title");
        JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
        return value.path("result").path("value").asText("");
    }

    private static String href(CdpConnection cdp) {
        ObjectNode params = Jsons.object();
        params.put("returnByValue", true);
        params.put("expression", "location.href");
        JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
        return value.path("result").path("value").asText("");
    }

    private static String pageTarget(BrowserSession session) {
        try {
            JsonNode own = session.connection().call("Target.getTargetInfo", Jsons.object(), Duration.ofSeconds(2));
            return own.path("targetInfo").path("targetId").asText("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String picture(CdpConnection cdp, List<Map<String, Object>> rows) {
        StringBuilder text = new StringBuilder(title(cdp));
        for (Map<String, Object> row : rows) text.append('\n').append(row.get("role")).append(' ').append(row.get("name"));
        return text.toString();
    }

    private static void remember(String runId, BrowserSession session) {
        try {
            BASELINE.put(baselineKey(runId), picture(session.connection(), List.of()) );
        } catch (RuntimeException ignored) {
            // A later snap still records its own baseline.
        }
    }

    private static List<String> diffAgainst(String runId, String picture, String version) {
        String previous = BASELINE.put(baselineKey(runId), picture + "\n" + version);
        List<String> diff = new ArrayList<>();
        if (previous == null) {
            diff.add("added " + picture);
            return diff;
        }
        if (!previous.equals(picture + "\n" + version) && !previous.equals(picture)) diff.add(previous + " -> " + picture);
        return diff;
    }

    private static Map<String, Object> diffOf(String titleBefore, String titleAfter, String before, String after) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("title", titleBefore + " -> " + titleAfter);
        diff.put("docVersion", before + " -> " + after);
        diff.put("changed", !titleBefore.equals(titleAfter) || !before.equals(after));
        return diff;
    }

    private static String writeFull(Path project, String runId, ObjectNode produced) {
        Path dir = (project == null ? Path.of(".") : project).resolve(".pickleball").resolve("agent-full");
        Path file = dir.resolve("snap-" + (runId == null || runId.isBlank() ? "run" : runId) + ".json");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, produced.toString());
        } catch (java.io.IOException failure) {
            throw new CdpException("snap full: " + failure.getMessage());
        }
        return file.toString();
    }

    private static Map<String, String> frameFlags(TargetSelector.Parsed target) {
        if (target.frame() == null || target.frame().isBlank()) return Map.of();
        return Map.of("frame", target.frame());
    }

    private static long timeoutMs(Map<String, String> args) {
        String raw = args.get("timeout");
        if (raw == null || raw.isBlank()) return 3000L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException failure) {
            throw new Miss("timeout is milliseconds");
        }
    }

    private static Map<String, Object> base(String op, String used, String kind, TargetSelector.Parsed target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("op", op);
        body.put("applied", true);
        body.put("used", used);
        body.put("kind", kind);
        body.put("chain", chain(op));
        if (target != null) {
            if (!target.frame().isBlank()) body.put("frame", target.frame());
            else if (!target.window().isBlank()) body.put("frame", target.window());
        }
        return body;
    }

    private static Outcome ok(Map<String, Object> body) {
        return new Outcome(0, body);
    }

    private static Outcome fail(String op, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("op", op == null ? "" : op);
        body.put("applied", false);
        body.put("error", error == null ? "failed" : error);
        return new Outcome(error != null && error.startsWith("unknown kind") ? 2 : 1, body);
    }

    private static String chain(String op) {
        return switch (op == null ? "" : op) {
            case "act" -> "act";
            case "wait", "watch" -> "wait";
            case "snap", "capture" -> "snapshot";
            default -> "locate";
        };
    }

    private static String baselineKey(String runId) {
        return runId == null ? "" : runId;
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }

    private static String jsString(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CdpException("timeout");
        }
    }

    private static void walk(DomNode node, java.util.function.Consumer<DomNode> visit) {
        if (node == null) return;
        if (node.nodeType == 1 || node.nodeType == 0) visit.accept(node);
        for (DomNode child : node.children) walk(child, visit);
        for (DomNode shadow : node.shadowRoots) walk(shadow, visit);
    }

    private record Hit(String kind, String sessionId, long backend, String docVersion, String selector, String query) {
    }

    private record Held(BrowserSession session, boolean closeAfter) {
        void release() {
            if (closeAfter && session != null) {
                try {
                    session.close();
                } catch (RuntimeException ignored) {
                    // The browser stays up. This socket is the extra attach.
                }
            }
        }
    }

    private static final class Miss extends RuntimeException {
        final String error;

        Miss(String error) {
            this.error = error == null ? "failed" : error;
        }
    }
}
