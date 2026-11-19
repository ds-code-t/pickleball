package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.action.Observe;
import dev.domengine.action.PageActions;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cdp.CdpException;
import dev.domengine.desktop.DesktopTree;
import dev.domengine.json.Jsons;
import dev.domengine.query.Displayed;
import dev.domengine.query.Frames;
import dev.domengine.query.HtmlTree;
import dev.domengine.query.LiveXPath;
import dev.domengine.query.Query;
import dev.domengine.query.RowStates;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.FrameCoverage;
import dev.domengine.session.SessionEvents;
import dev.domengine.snapshot.CompositeSnapshots;
import dev.domengine.snapshot.DesktopSnapshotProducer;
import dev.domengine.snapshot.SnapshotOptions;
import dev.domengine.tree.DomNode;
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
import java.util.concurrent.TimeUnit;

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
    private static final int HIT_CAP = 256;
    /** Least-recently used refs drop once the cap is passed. */
    private static final Map<String, Hit> HITS = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Hit> eldest) {
            return size() > HIT_CAP;
        }
    });
    private static final ConcurrentHashMap<String, String> BASELINE = new ConcurrentHashMap<>();
    /** Frame id chosen by {@code frame --enter} for this run. {@code frame --reset} clears it. */
    private static final ConcurrentHashMap<String, String> ENTERED = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> RUN = new ThreadLocal<>();

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

    /** Null when {@code target} is a browser or ref this process can open. Otherwise the error text. */
    public static String unavailable(String raw) {
        TargetSelector.Parsed target = TargetSelector.parse(raw);
        if (!"browser".equals(target.kind()) && !"ref".equals(target.kind())) return "unknown target";
        try {
            Held page = openBrowser("ref".equals(target.kind()) ? TargetSelector.parse("browser:auto") : target);
            page.release();
            return null;
        } catch (Miss miss) {
            return miss.error;
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            return message == null || message.isBlank() ? "unknown target" : message;
        }
    }

    public static Outcome run(Path project, String runId, String op, Map<String, String> args) {
        String verb = op == null ? "" : op;
        Map<String, String> values = args == null ? Map.of() : args;
        RUN.set(runId);
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
                case "frame" -> frame(project, runId, target, values);
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
        } finally {
            RUN.remove();
        }
    }

    private static Outcome attach(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        if ("desktop".equals(target.kind())) {
            DesktopTree.Session session = openDesktop(target, args.get("fixture"));
            Map<String, Object> body = base("attach", "desktop", "desktop", target);
            body.put("session", session.id());
            body.put("docVersion", "desktop:" + session.sequence());
            body.put("applied", session.live());
            return ok(body);
        }
        Held page = openBrowser(target);
        try {
            String version = docVersion(page.session.connection());
            Map<String, Object> body = base("attach", "cdp", "browser", target);
            body.put("cdp", page.session.socket());
            body.put("docVersion", version);
            body.put("session", page.session.id);
            stamp(body, page.session.connection(), null);
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
            Map<String, String> flags = scopedFrame(runId, target);
            List<DomNode> hits = args.get("scroll-search") == null
                    ? locate(tree(page.session), query, flags)
                    : scrollFind(page.session, query, flags);
            if (hits.isEmpty()) return fail("find", "not found");
            String version = docVersion(page.session.connection());
            List<Map<String, Object>> rows = publish(runId, "browser", page.session.id, version, hits, query);
            remember(runId, page.session);
            Map<String, Object> body = base("find", "cdp", "browser", target);
            body.put("docVersion", version);
            body.put("hits", rows);
            body.put("ref", rows.get(0).get("ref"));
            if (args.get("scroll-search") != null) body.put("scrollSearch", true);
            stamp(body, page.session.connection(), hits.get(0));
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
        body.put("applied", session.live());
        stampDesktop(body, session);
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
                List<DomNode> hits = locate(root, query, scopedFrame(runId, target));
                if (hits.isEmpty()) return fail("act", "not found");
                if (hits.size() > 1) return fail("act", "ambiguous");
                node = hits.get(0);
            }
            String before = docVersion(page.session.connection());
            String titleBefore = title(page.session.connection());
            Effect effect = perform(project, runId, page.session, node, args, query, selector(node, query));
            if (!effect.applied()) return fail("act", "not applied");
            String after = docVersion(page.session.connection());
            Map<String, Object> body = base("act", effect.used(), "browser", target);
            body.put("applied", true);
            body.put("docVersion", after);
            body.put("kind", blank(args.get("kind")).isBlank() ? "click" : blank(args.get("kind")));
            if ("diff".equals(blank(args.get("snap")))) {
                body.put("snap", diffOf(titleBefore, title(page.session.connection()), before, after));
            }
            stamp(body, page.session.connection(), node);
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
        boolean live = desktopAct(session, node, args);
        DesktopTree.Session after = DesktopTree.require(session.id());
        Map<String, Object> body = base("act", "desktop", "desktop", TargetSelector.parse("desktop:" + hit.sessionId));
        body.put("applied", live);
        body.put("docVersion", "desktop:" + after.sequence());
        body.put("session", after.id());
        stampDesktop(body, after);
        return ok(body);
    }

    private static Outcome actDesktop(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        DesktopTree.Session session = openDesktop(target, args.get("fixture"));
        String query = blank(args.get("query"));
        List<DomNode> hits = locate(session.root(), query, Map.of());
        if (hits.isEmpty()) return fail("act", "not found");
        if (hits.size() > 1) return fail("act", "ambiguous");
        boolean live = desktopAct(session, hits.get(0), args);
        DesktopTree.Session after = DesktopTree.require(session.id());
        Map<String, Object> body = base("act", "desktop", "desktop", target);
        body.put("applied", live);
        body.put("docVersion", "desktop:" + after.sequence());
        body.put("session", after.id());
        stampDesktop(body, after);
        return ok(body);
    }

    /** True only after a live platform action returns. A fixture bump is not applied. */
    private static boolean desktopAct(DesktopTree.Session session, DomNode node, Map<String, String> args) {
        String kind = blank(args.get("kind"));
        if (kind.isBlank()) kind = "click";
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("text", blank(args.get("value")));
        switch (kind) {
            case "type" -> DesktopTree.type(session.id(), flagsWithNode(session, node, flags));
            case "click", "hover", "press", "select" -> DesktopTree.click(session, node);
            default -> throw new Miss("unknown kind " + kind);
        }
        return session.live();
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
            List<DomNode> shown = interactive(root);
            RowStates.fill(page.session.connection(), optionNodes(shown));
            List<Map<String, Object>> rows = publish(runId, "browser", page.session.id, version, shown, "");
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
            body.put("virtualized", virtualized(page.session.connection()));
            stamp(body, page.session.connection(), root);
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
        body.put("applied", session.live());
        if ("diff".equals(mode)) body.put("diff", diffAgainst(runId, String.join("\n", lines(rows)), version));
        stampDesktop(body, session);
        return ok(body);
    }

    private static Outcome wait(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        String condition = blank(args.get("condition"));
        String mode = blank(args.get("mode"));
        if (mode.isBlank()) mode = EVENT_CONDITIONS.contains(condition) ? "event" : "poll";
        long timeout = timeoutMs(args);
        if ("desktop".equals(target.kind())) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
            DesktopTree.Session session = null;
            boolean met = false;
            String looked = condition.startsWith("text=") ? condition.substring("text=".length()) : blank(args.get("query"));
            while (true) {
                session = openDesktop(target, args.get("fixture"));
                if (session.live()) {
                    try {
                        DesktopTree.walk(session.id());
                        session = DesktopTree.require(session.id());
                    } catch (RuntimeException ignored) {
                        // The last captured tree is still the one we can search.
                    }
                }
                if (looked.isBlank() || !locate(session.root(), looked, Map.of()).isEmpty()) {
                    met = true;
                    break;
                }
                if (System.nanoTime() >= deadline) break;
                sleep(50);
            }
            if (!met) return fail("wait", "timeout");
            Map<String, Object> body = base("wait", "desktop", "desktop", target);
            body.put("applied", session.live());
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
            stamp(body, page.session.connection(), null);
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome watch(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        if ("desktop".equals(target.kind())) return fail("watch", "watch is browser only");
        Held page = openBrowser(resolved(target, args));
        try {
            long duration = timeoutMs(args);
            if (args.get("duration") != null && !args.get("duration").isBlank()) {
                try {
                    duration = Long.parseLong(args.get("duration").trim());
                } catch (NumberFormatException failure) {
                    return fail("watch", "duration is milliseconds");
                }
            }
            String kinds = blank(args.get("kinds"));
            if (kinds.isBlank()) kinds = blank(args.get("condition"));
            long since = System.currentTimeMillis();
            List<ObjectNode> events = page.session.events().read(
                    new SessionEvents.Filter(kinds, "", since, null, 50), duration);
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
            stamp(body, page.session.connection(), null);
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
            body.put("applied", session.live());
            body.put("report", report(hits.get(0), null));
            stampDesktop(body, session);
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
                List<DomNode> hits = locate(tree(page.session), blank(args.get("query")), scopedFrame(runId, target));
                node = hits.isEmpty() ? null : hits.get(0);
            }
            if (node == null) return fail("explain", "not found");
            Map<String, Object> body = base("explain", "cdp", "browser", target);
            body.put("report", report(node, page.session.connection()));
            body.put("docVersion", docVersion(page.session.connection()));
            stamp(body, page.session.connection(), node);
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
            stamp(body, page.session.connection(), null);
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
                int context = pkbContext(page.session.connection());
                if (context <= 0) return fail("eval", "eval failed");
                params.put("contextId", context);
            }
            JsonNode value = page.session.connection().call("Runtime.evaluate", params, Duration.ofSeconds(10));
            if (value.has("exceptionDetails")) return fail("eval", "eval failed");
            Map<String, Object> body = base("eval", "cdp", "browser", target);
            body.put("world", world);
            body.put("value", jsonText(value.path("result").path("value")));
            stamp(body, page.session.connection(), null);
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Effect perform(
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
        String value = blank(args.get("value"));
        String verb = switch (kind) {
            case "type" -> "type";
            case "hover" -> "hover";
            case "press" -> "key";
            case "select" -> "click";
            case "click" -> "click";
            default -> throw new Miss("unknown kind " + kind);
        };
        Map<String, String> flags = new LinkedHashMap<>();
        DomNode acted = node;
        if ("select".equals(kind)) {
            acted = optionFor(node, value);
            if (acted == null) throw new Miss("not found");
        }
        flags.put("node-id", Long.toString(acted.backendNodeId));
        if ("type".equals(verb)) flags.put("text", value);
        if ("key".equals(verb)) flags.put("key", value);
        boolean disabled = cdpInputOff();
        if (disabled) PageActions.useCdpInput(false);
        try {
            if ("click".equals(kind) && closedShadow(acted)) {
                Effect closed = clickClosed(session.connection(), acted);
                if (closed.applied()) return closed;
            }
            LiveXPath.use(session.connection());
            try {
                PageActions.act(session, verb, flags);
            } finally {
                LiveXPath.use(null);
            }
            if ("select".equals(kind)) return new Effect("cdp", valueMatches(value, controlValue(session.connection(), selectOf(acted))));
            if ("type".equals(kind)) return new Effect("cdp", valueMatches(value, controlValue(session.connection(), node)));
            return new Effect("cdp", true);
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            if (!disabled && !message.contains("cdp input") && !message.contains("Input.")) throw failure;
            FallbackLog.note(project, runId, "act", "cdp", "js", message.isBlank() ? "cdp input disabled" : message);
            return jsAct(session.connection(), kind, query, selector, value, node);
        } finally {
            PageActions.useCdpInput(true);
        }
    }

    /** JS runs on the session that was targeted, including a {@code browser:cdp=} page. */
    private static Effect jsAct(CdpConnection cdp, String kind, String query, String selector, String value, DomNode node) {
        String before = docVersion(cdp) + "\n" + title(cdp);
        String script = ""
                + "(function(){"
                + "var kind=" + jsString(kind) + ",q=" + jsString(query) + ",sel=" + jsString(selector) + ",value=" + jsString(value) + ";"
                + "function text(el){return ((el.innerText||el.value||el.getAttribute('aria-label')||el.getAttribute('placeholder')||el.id||'')+'').replace(/\\s+/g,' ').trim();}"
                + "var el=null;if(sel){try{el=document.querySelector(sel);}catch(e){el=null;}}"
                + "if(!el){var all=document.querySelectorAll('button,a,input,select,textarea,option,label,[role=button]');"
                + "for(var i=0;i<all.length;i++){if(text(all[i])===q||all[i].id===q){el=all[i];break;}}}"
                + "if(!el) return {ok:false};"
                + "if(el.tagName==='LABEL'&&el.htmlFor){var linked=document.getElementById(el.htmlFor); if(linked) el=linked;}"
                + "el.setAttribute('data-pkb-synthetic','1');"
                + "if(kind==='type'){el.focus(); el.value=value; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); return {ok:true,value:String(el.value)};}"
                + "if(kind==='select'){var box=el.tagName==='OPTION'?(el.closest('select')||el):el; if(!box||box.tagName!=='SELECT') return {ok:false};"
                + "var matched=false; for(var j=0;j<box.options.length;j++){var o=box.options[j]; var label=(o.label||o.text||'').replace(/\\s+/g,' ').trim();"
                + "if(o.value===value||label===value){o.selected=true; matched=true; break;}} if(!matched) return {ok:false};"
                + "box.dispatchEvent(new Event('input',{bubbles:true})); box.dispatchEvent(new Event('change',{bubbles:true}));"
                + "var chosen=box.options[box.selectedIndex]; var shown=chosen?((chosen.value||chosen.text||'')+'').replace(/\\s+/g,' ').trim():'';"
                + "return {ok:true,value:shown};}"
                + "el.click(); return {ok:true,title:document.title};"
                + "})()";
        ObjectNode params = Jsons.object();
        params.put("expression", script);
        params.put("returnByValue", true);
        JsonNode called = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(10));
        JsonNode payload = called.path("result").path("value");
        if (!payload.path("ok").asBoolean(false)) return new Effect("js", false);
        if ("type".equals(kind) || "select".equals(kind)) {
            String read = payload.path("value").asText("");
            if (!valueMatches(value, read) && node != null) read = controlValue(cdp, "select".equals(kind) ? selectOf(node) : node);
            return new Effect("js", valueMatches(value, read));
        }
        String after = docVersion(cdp) + "\n" + title(cdp);
        return new Effect("js", !before.equals(after));
    }

    private static boolean cdpInputOff() {
        return "off".equalsIgnoreCase(System.getProperty("pkb_cdp_input", ""));
    }

    private static DomNode optionFor(DomNode node, String value) {
        if (node == null) return null;
        if ("option".equals(node.tag())) return node;
        DomNode select = selectOf(node);
        if (select == null) return null;
        for (DomNode child : select.children) {
            if (!"option".equals(child.tag())) continue;
            String optionValue = child.attributes.getOrDefault("value", "");
            String text = HtmlTree.textOf(child).trim();
            String label = child.attributes.getOrDefault("label", "");
            if (value.equals(optionValue) || value.equals(text) || value.equals(label)) return child;
        }
        return null;
    }

    private static DomNode selectOf(DomNode node) {
        if (node == null) return null;
        if ("select".equals(node.tag())) return node;
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if ("select".equals(cursor.tag())) return cursor;
        }
        return node;
    }

    private static boolean valueMatches(String wanted, String got) {
        if (wanted == null) wanted = "";
        if (got == null) got = "";
        return wanted.equals(got) || wanted.equalsIgnoreCase(got.trim());
    }

    private static String controlValue(CdpConnection cdp, DomNode node) {
        if (cdp == null || node == null || node.backendNodeId <= 0) return "";
        try {
            ObjectNode params = Jsons.object();
            params.put("backendNodeId", node.backendNodeId);
            JsonNode resolved = cdp.call("DOM.resolveNode", params, Duration.ofSeconds(5));
            String objectId = resolved.path("object").path("objectId").asText("");
            if (objectId.isBlank()) return "";
            ObjectNode call = Jsons.object();
            call.put("objectId", objectId);
            call.put("functionDeclaration", "function(){ if(this.tagName==='SELECT'){ var o=this.options[this.selectedIndex]; return o?String(o.value||o.text||'').trim():''; } if('value' in this) return String(this.value); return ''; }");
            call.put("returnByValue", true);
            return cdp.call("Runtime.callFunctionOn", call, Duration.ofSeconds(5)).path("result").path("value").asText("");
        } catch (RuntimeException failure) {
            return "";
        }
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
        String primary = evaluateWatch(cdp, condition, timeout);
        if (primary.startsWith("watchUntil:")) {
            return !primary.contains("\"ok\":false") && !primary.contains("timeout");
        }
        FallbackLog.note(project, runId, "wait", "watcher", "mutation-observer", "pkb watchUntil was not on the page");
        ObjectNode params = Jsons.object();
        params.put("awaitPromise", true);
        params.put("returnByValue", true);
        params.put("expression", ""
                + "(async function(){return await new Promise(function(resolve){"
                + "var timer=setTimeout(function(){resolve('timeout');}," + timeout + ");"
                + "var obs=new MutationObserver(function(){clearTimeout(timer);obs.disconnect();resolve('mutation');});"
                + "obs.observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});"
                + "});})()");
        JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofMillis(Math.max(1000, timeout + 2000)));
        String text = value.path("result").path("value").asText("");
        return text.contains("mutation") && !text.contains("timeout");
    }

    /** Isolated-world watcher. Empty when that world cannot run {@code __pkbUi.watchUntil}. */
    private static String evaluateWatch(CdpConnection cdp, String condition, long timeout) {
        try {
            int context = pkbContext(cdp);
            if (context <= 0) return "";
            ObjectNode params = Jsons.object();
            params.put("awaitPromise", true);
            params.put("returnByValue", true);
            params.put("contextId", context);
            params.put("expression", ""
                    + "(async function(){"
                    + "var ui=globalThis.__pkbUi;"
                    + "if(!ui||typeof ui.watchUntil!=='function') return 'missing';"
                    + "var hit=await ui.watchUntil(" + jsString(condition) + "," + timeout + ");"
                    + "return 'watchUntil:'+JSON.stringify(hit);"
                    + "})()");
            JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofMillis(Math.max(1000, timeout + 2000)));
            if (value.has("exceptionDetails")) return "";
            return value.path("result").path("value").asText("");
        } catch (RuntimeException failure) {
            return "";
        }
    }

    /** Reuse the surface world on the main frame. {@code Page.createIsolatedWorld} returns the existing name. */
    private static int pkbContext(CdpConnection cdp) {
        String frame = mainFrameId(cdp);
        if (frame.isBlank()) return 0;
        ObjectNode created = Jsons.object();
        created.put("frameId", frame);
        created.put("worldName", "pkb-surface");
        created.put("grantUniveralAccess", true);
        JsonNode worldNode = cdp.call("Page.createIsolatedWorld", created, Duration.ofSeconds(5));
        return worldNode.path("executionContextId").asInt(0);
    }

    private static String mainFrameId(CdpConnection cdp) {
        JsonNode tree = cdp.call("Page.getFrameTree", Jsons.object(), Duration.ofSeconds(5));
        return tree.path("frameTree").path("frame").path("id").asText("");
    }

    private static String jsonText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual() || value.isNumber() || value.isBoolean()) return value.asText();
        return value.toString();
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
            if (host != null && (target.session().equals(host.id) || target.session().equals(LiveBrowserHost.sessionId()))) {
                requireTab(host, target.tab());
                return new Held(host, false);
            }
            Held worker = workerHeld();
            if (worker != null) {
                String run = RUN.get() == null ? "" : RUN.get();
                if (target.session().equals(worker.session.id) || target.session().equals(run)) {
                    requireTab(worker.session, target.tab());
                    return worker;
                }
                worker.release();
            }
            throw new Miss("unknown target");
        }
        if (target.auto() || target.raw().isBlank()) {
            BrowserSession host = LiveBrowserHost.chrome();
            if (host != null) {
                requireTab(host, target.tab());
                return new Held(host, false);
            }
            Held worker = workerHeld();
            if (worker == null) throw new Miss("no live browser");
            requireTab(worker.session, target.tab());
            return worker;
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
            DesktopTree.Session session = DesktopTree.attachLive(id, window);
            if (!target.pid().isBlank()) {
                if (!ownsPid(session.root(), target.pid())) {
                    DesktopTree.close(id);
                    throw new Miss("unknown target");
                }
                keepPid(session.root(), target.pid());
                if (session.root().children.isEmpty() && !target.pid().equals(session.root().attributes.get("pid"))) {
                    DesktopTree.close(id);
                    throw new Miss("unknown target");
                }
            }
            return session;
        } catch (Miss failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new Miss("no desktop target " + label);
        }
    }

    private static boolean ownsPid(DomNode node, String pid) {
        if (node == null || pid == null || pid.isBlank()) return false;
        if (pid.equals(node.attributes.get("pid"))) return true;
        for (DomNode child : node.children) if (ownsPid(child, pid)) return true;
        return false;
    }

    /** Keep the windows that belong to {@code pid}. The desktop root stays. */
    private static void keepPid(DomNode root, String pid) {
        if (root == null || pid.equals(root.attributes.get("pid"))) return;
        List<DomNode> kept = new ArrayList<>();
        for (DomNode child : root.children) if (ownsPid(child, pid)) kept.add(child);
        root.children.clear();
        root.children.addAll(kept);
    }

    /** The worker page in this JVM. Null when this process has no managed browser. */
    private static Held workerHeld() {
        org.openqa.selenium.WebDriver driver = tools.dscode.common.agentcontrol.AgentControl.scenarioDriver();
        if (driver instanceof LiveChromeDriver live) {
            BrowserSession session = live.browserSession();
            if (session != null && !session.isClosed()) return new Held(session, false);
        }
        if (!(driver instanceof org.openqa.selenium.HasCapabilities has)) return null;
        String address = tools.dscode.common.composite.CompositeRuntime.cdpAddress(has.getCapabilities());
        if (address == null || address.isBlank()) return null;
        String cdp = address.contains("://") ? address : "http://" + address;
        try {
            BrowserSession attached = BrowserSession.attach(
                    "worker" + Long.toUnsignedString(System.nanoTime()), cdp, false, List.of(), null);
            return new Held(attached, true);
        } catch (RuntimeException failure) {
            return null;
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
        DomNode search = root;
        String frame = flags.get("frame");
        if (frame != null && !frame.isBlank()) {
            try {
                search = Frames.scope(root, flags);
            } catch (CdpException failure) {
                String message = failure.getMessage() == null ? "" : failure.getMessage();
                if (message.contains("not found")) return List.of();
                throw failure;
            }
            // Query.run scopes again. The document is already the entered frame.
            flags.remove("frame");
        }
        if (query == null || query.isBlank()) flags.put("pipeline", "css=a, button, input, select, textarea");
        else flags.put("pipeline", "text=\"" + query.replace("\"", "") + "\"");
        List<DomNode> hits = new ArrayList<>();
        try {
            ObjectNode found = Query.run(search, flags);
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
        if (query != null && !query.isBlank()) hits.addAll(named(search, query));
        return deepest(controlsFor(search, hits));
    }

    /** A label's {@code for} points at the control. The query names that control. */
    private static List<DomNode> controlsFor(DomNode root, List<DomNode> hits) {
        Map<String, DomNode> byId = new LinkedHashMap<>();
        walk(root, node -> {
            String id = node.attributes.get("id");
            if (id != null && !id.isBlank() && !byId.containsKey(id)) byId.put(id, node);
        });
        List<DomNode> kept = new ArrayList<>();
        for (DomNode node : hits) {
            if (node != null && "label".equals(node.tag()) && node.attributes.containsKey("for")) {
                DomNode control = byId.get(node.attributes.get("for"));
                if (control != null) {
                    addHit(kept, control);
                    continue;
                }
            }
            addHit(kept, node);
        }
        return kept;
    }

    private static List<DomNode> named(DomNode root, String query) {
        Map<String, DomNode> byId = new LinkedHashMap<>();
        walk(root, node -> {
            String id = node.attributes.get("id");
            if (id != null && !id.isBlank() && !byId.containsKey(id)) byId.put(id, node);
        });
        List<DomNode> hits = new ArrayList<>();
        walk(root, node -> {
            String accessible = node.accessibleName == null ? "" : node.accessibleName;
            if (query.equals(accessible) || (!accessible.isBlank() && query.equalsIgnoreCase(accessible))) {
                addHit(hits, node);
                return;
            }
            String text = HtmlTree.textOf(node);
            if (query.equals(text)) {
                if ("label".equals(node.tag()) && node.attributes.containsKey("for")) {
                    DomNode control = byId.get(node.attributes.get("for"));
                    if (control != null) addHit(hits, control);
                    return;
                }
                if (ownedByControl(node, query)) return;
                addHit(hits, node);
                return;
            }
            if (query.equals(attr(node, "aria-label")) || equalsIgnore(query, attr(node, "aria-label"))) addHit(hits, node);
            else if (query.equals(attr(node, "id"))) addHit(hits, node);
            else if (query.equals(attr(node, "placeholder")) || equalsIgnore(query, attr(node, "placeholder"))) addHit(hits, node);
        });
        return hits;
    }

    /** Placeholder and aria-label text inside a control's shadow is the control's name, not a second hit. */
    private static boolean ownedByControl(DomNode node, String query) {
        for (DomNode cursor = node == null ? null : node.parent; cursor != null; cursor = cursor.parent) {
            if (query.equals(attr(cursor, "placeholder")) || equalsIgnore(query, attr(cursor, "placeholder"))) return true;
            if (query.equals(attr(cursor, "aria-label")) || equalsIgnore(query, attr(cursor, "aria-label"))) return true;
        }
        return false;
    }

    private static void addHit(List<DomNode> hits, DomNode node) {
        if (node != null && !hits.contains(node)) hits.add(node);
    }

    private static boolean equalsIgnore(String query, String value) {
        return value != null && !value.isBlank() && query.equalsIgnoreCase(value);
    }

    private static String attr(DomNode node, String name) {
        String value = node.attributes.get(name);
        return value == null ? "" : value.trim();
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
        for (DomNode shadow : parent.shadowRoots) {
            if (shadow == child || contains(shadow, child)) return true;
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
            if (node.frameId != null && !node.frameId.isBlank() && !"main".equals(node.frameId)) row.put("frame", node.frameId);
            else if (node.framePath != null && !node.framePath.isBlank()) row.put("frame", node.framePath);
            String shadow = shadowModeOf(node);
            if (!shadow.isBlank()) row.put("shadow", shadow);
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

    private static Map<String, Object> report(DomNode node, CdpConnection cdp) {
        if (cdp != null && node != null && node.nodeId > 0) {
            Displayed.prepare(cdp, List.of(node));
            RowStates.fill(cdp, List.of(node));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        boolean visible = Displayed.isDisplayed(node);
        boolean enabled = !disabled(node);
        report.put("role", role(node));
        report.put("name", name(node));
        report.put("visible", visible);
        report.put("enabled", enabled);
        report.put("actionable", visible && enabled);
        report.put("reason", visible && enabled ? "visible and enabled" : (visible ? "disabled" : "not visible"));
        return report;
    }

    /** Bare {@code disabled} counts. So does {@code aria-disabled} other than false or 0, and a disabled fieldset. */
    private static boolean disabled(DomNode node) {
        if (node == null) return true;
        if (node.attributes.containsKey("disabled")) return true;
        String aria = node.attributes.get("aria-disabled");
        if (aria != null && !aria.isBlank() && !"false".equalsIgnoreCase(aria) && !"0".equals(aria)) return true;
        if (node.liveState != null && node.liveState.path("disabled").asBoolean(false)) return true;
        for (DomNode parent = node.parent; parent != null; parent = parent.parent) {
            if (parent.attributes.containsKey("inert")) return true;
            if ("fieldset".equals(parent.tag()) && parent.attributes.containsKey("disabled")) return true;
        }
        return false;
    }

    private static String role(DomNode node) {
        if (node.role != null && !node.role.isBlank()) return node.role;
        String tag = node.tag();
        return tag.isBlank() ? "element" : tag;
    }

    private static String name(DomNode node) {
        if ("select".equals(node.tag())) {
            String label = labelOf(node);
            String selected = selectedText(node);
            if (!label.isBlank() && !selected.isBlank()) return label + " " + selected;
            if (!selected.isBlank()) return selected;
            if (!label.isBlank()) return label;
        }
        if (node.accessibleName != null && !node.accessibleName.isBlank()) return node.accessibleName;
        String text = HtmlTree.textOf(node);
        if (!text.isBlank()) return text.length() > 80 ? text.substring(0, 80) : text;
        String id = node.attributes.get("id");
        return id == null ? "" : id;
    }

    private static String labelOf(DomNode node) {
        String aria = attr(node, "aria-label");
        if (!aria.isBlank()) return aria;
        String id = attr(node, "id");
        if (id.isBlank()) return "";
        DomNode root = node;
        while (root.parent != null) root = root.parent;
        String[] found = {""};
        walk(root, candidate -> {
            if (!found[0].isEmpty() || !"label".equals(candidate.tag())) return;
            if (id.equals(candidate.attributes.get("for"))) found[0] = HtmlTree.textOf(candidate).trim();
        });
        return found[0];
    }

    private static String selectedText(DomNode node) {
        DomNode fallback = null;
        DomNode marked = null;
        for (DomNode child : node.children) {
            if (!"option".equals(child.tag())) continue;
            if (fallback == null) fallback = child;
            if (child.liveState != null && child.liveState.path("selected").asBoolean(false)) return optionLabel(child);
            if (marked == null && child.attributes.containsKey("selected")) marked = child;
        }
        if (marked != null) return optionLabel(marked);
        return fallback == null ? "" : optionLabel(fallback);
    }

    private static List<DomNode> optionNodes(List<DomNode> nodes) {
        List<DomNode> options = new ArrayList<>();
        if (nodes == null) return options;
        for (DomNode node : nodes) {
            if (node == null || !"select".equals(node.tag())) continue;
            for (DomNode child : node.children) {
                if ("option".equals(child.tag())) options.add(child);
            }
        }
        return options;
    }

    private static String optionLabel(DomNode option) {
        String text = HtmlTree.textOf(option).trim();
        if (!text.isBlank()) return text;
        String value = option.attributes.get("value");
        return value == null ? "" : value.trim();
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

    private static Outcome frame(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        if ("desktop".equals(target.kind())) {
            DesktopTree.Session session = openDesktop(target, args.get("fixture"));
            Map<String, Object> body = base("frame", "desktop", "desktop", target);
            body.put("applied", session.live());
            List<Map<String, Object>> frames = new ArrayList<>();
            if (session.root() != null) {
                for (DomNode child : session.root().children) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String name = child.accessibleName == null ? "" : child.accessibleName;
                    row.put("id", name);
                    row.put("role", child.role == null ? "" : child.role);
                    frames.add(row);
                }
            }
            body.put("frames", frames);
            stampDesktop(body, session);
            return ok(body);
        }
        Held page = openBrowser(resolved(target, args));
        try {
            CdpConnection cdp = page.session.connection();
            String key = runId == null ? "" : runId;
            if ("true".equals(args.get("reset"))) {
                ENTERED.remove(key);
                Map<String, Object> body = base("frame", "cdp", "browser", target);
                body.put("entered", "");
                stamp(body, cdp, null);
                return ok(body);
            }
            String enter = blank(args.get("enter"));
            if (!enter.isBlank()) {
                if (!frameExists(cdp, enter)) return fail("frame", "unknown frame");
                ENTERED.put(key, enter);
                Map<String, Object> body = base("frame", "cdp", "browser", target);
                body.put("entered", enter);
                body.put("frame", enter);
                return ok(body);
            }
            Map<String, Object> body = base("frame", "cdp", "browser", target);
            body.put("frames", listFrames(cdp));
            String entered = ENTERED.get(key);
            if (entered != null) body.put("entered", entered);
            stamp(body, cdp, null);
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static List<Map<String, Object>> listFrames(CdpConnection cdp) {
        FrameCoverage.harvest(cdp);
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode tree = cdp.call("Page.getFrameTree", Jsons.object(), Duration.ofSeconds(5));
        collectFrameRows(cdp, tree.path("frameTree"), "", rows);
        addCoveredFrames(cdp, rows);
        return rows;
    }

    private static void collectFrameRows(CdpConnection cdp, JsonNode node, String parent, List<Map<String, Object>> rows) {
        if (node == null || node.isMissingNode()) return;
        JsonNode frame = node.path("frame");
        String id = frame.path("id").asText("");
        if (!id.isBlank()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("parentId", parent);
            row.put("url", frame.path("url").asText(""));
            row.put("name", frame.path("name").asText(""));
            String session = FrameCoverage.sessionId(cdp, id);
            row.put("session", !session.isBlank());
            if (!session.isBlank()) row.put("boot", FrameCoverage.boot(cdp, session));
            rows.add(row);
        }
        JsonNode children = node.path("childFrames");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) collectFrameRows(cdp, child, id, rows);
        }
    }

    /** Auto-attached frames that the parent tree dropped after an out-of-process swap. */
    private static void addCoveredFrames(CdpConnection cdp, List<Map<String, Object>> rows) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> row : rows) seen.add(String.valueOf(row.get("id")));
        for (String id : FrameCoverage.frameIds(cdp)) {
            if (id.isBlank() || !seen.add(id)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("parentId", "");
            row.put("url", FrameCoverage.url(cdp, id));
            row.put("name", "");
            String session = FrameCoverage.sessionId(cdp, id);
            row.put("session", !session.isBlank());
            if (!session.isBlank()) row.put("boot", FrameCoverage.boot(cdp, session));
            rows.add(row);
        }
    }

    private static boolean frameExists(CdpConnection cdp, String id) {
        for (Map<String, Object> row : listFrames(cdp)) {
            if (id.equals(String.valueOf(row.get("id"))) || id.equals(String.valueOf(row.get("name")))) return true;
        }
        return findFrameOwner(tree(cdp), id);
    }

    private static boolean findFrameOwner(DomNode node, String id) {
        if (node == null) return false;
        if (node.iframe && (id.equals(node.attributes.get("id")) || id.equals(node.ownerFrameId) || id.equals(node.frameId))) {
            return true;
        }
        for (DomNode child : node.children) if (findFrameOwner(child, id)) return true;
        return false;
    }

    private static Map<String, String> scopedFrame(String runId, TargetSelector.Parsed target) {
        Map<String, String> flags = frameFlags(target);
        if (!flags.isEmpty()) return flags;
        String entered = ENTERED.get(runId == null ? "" : runId);
        if (entered == null || entered.isBlank()) return Map.of();
        return Map.of("frame", entered);
    }

    private static List<DomNode> scrollFind(BrowserSession session, String query, Map<String, String> flags) {
        List<DomNode> hits = locate(tree(session), query, flags);
        int stuck = 0;
        for (int step = 0; hits.isEmpty() && step < 40; step++) {
            if (!scrollOnce(session.connection())) {
                hits = locate(tree(session), query, flags);
                if (!hits.isEmpty() || ++stuck >= 3) break;
                continue;
            }
            stuck = 0;
            hits = locate(tree(session), query, flags);
        }
        if (!hits.isEmpty() && query != null && !query.isBlank()) {
            reveal(session.connection(), query);
            List<DomNode> painted = locate(tree(session), query, flags);
            if (!painted.isEmpty()) hits = painted;
        }
        return hits;
    }

    /**
     * Aligns the matched text with the top of its overflow box.
     * A virtualized row is often mounted on the clipped edge, where {@code innerText} omits it.
     * Putting that row at the top of the scrollport keeps it in the mount window and on screen.
     */
    private static void reveal(CdpConnection cdp, String query) {
        if (cdp == null || query == null || query.isBlank()) return;
        String quoted = Jsons.MAPPER.valueToTree(query).toString();
        ObjectNode params = Jsons.object();
        params.put("expression", "(function(){"
                + "var q=" + quoted + ";"
                + "function text(el){return ((el.textContent||'')+'').replace(/\\s+/g,' ').trim();}"
                + "var nodes=document.querySelectorAll('body *');var el=null;"
                + "for(var i=0;i<nodes.length;i++){if(text(nodes[i])===q) el=nodes[i];}"
                + "if(!el) return false;"
                + "var box=el.parentElement;"
                + "while(box){var s=getComputedStyle(box);var oy=s.overflowY;"
                + "if((oy==='auto'||oy==='scroll')&&box.scrollHeight>box.clientHeight+2) break;"
                + "box=box.parentElement;}"
                + "if(!box){el.scrollIntoView({block:'start',inline:'nearest'});return true;}"
                + "var row=el.getBoundingClientRect();var port=box.getBoundingClientRect();"
                + "box.scrollTop+=row.top-port.top;"
                + "return (document.body.innerText||'').indexOf(q)>=0;"
                + "})()");
        params.put("returnByValue", true);
        try {
            cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // The row is already in the tree. A later read reports whether it painted.
        }
    }

    private static boolean scrollOnce(CdpConnection cdp) {
        ObjectNode params = Jsons.object();
        params.put("expression", "(function(){"
                + "function scrollable(el){var s=getComputedStyle(el);var oy=s.overflowY;"
                + "return (oy==='auto'||oy==='scroll')&&el.scrollHeight>el.clientHeight+2;}"
                + "var nodes=document.querySelectorAll('*');var box=null;"
                + "for(var i=0;i<nodes.length;i++){if(scrollable(nodes[i])){box=nodes[i];break;}}"
                + "if(!box) box=document.scrollingElement||document.documentElement;"
                + "var before=box.scrollTop;box.scrollTop=before+Math.max(40,Math.floor(box.clientHeight*0.8));"
                + "return box.scrollTop!==before;})()");
        params.put("returnByValue", true);
        try {
            JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
            return value.path("result").path("value").asBoolean(false);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean virtualized(CdpConnection cdp) {
        ObjectNode params = Jsons.object();
        params.put("expression", "(function(){var nodes=document.querySelectorAll('*');"
                + "for(var i=0;i<nodes.length;i++){var el=nodes[i];var s=getComputedStyle(el);"
                + "if((s.overflowY==='auto'||s.overflowY==='scroll')&&el.scrollHeight>el.clientHeight+80"
                + "&&el.scrollHeight>800&&el.children.length<30) return true;}return false;})()");
        params.put("returnByValue", true);
        try {
            JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
            return value.path("result").path("value").asBoolean(false);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean closedShadow(DomNode node) {
        return "closed".equals(shadowModeOf(node));
    }

    private static String shadowModeOf(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if (cursor.shadowRoot && "closed".equalsIgnoreCase(cursor.shadowMode)) return "closed";
        }
        return "";
    }

    /** Clicks through {@code DOM.resolveNode} in the pkb world. Coordinate input misses {@code pointer-events:none}. */
    private static Effect clickClosed(CdpConnection cdp, DomNode node) {
        if (node == null || node.backendNodeId <= 0) return new Effect("resolveNode", false);
        try {
            int context = pkbContext(cdp);
            ObjectNode resolve = Jsons.object();
            resolve.put("backendNodeId", node.backendNodeId);
            if (context > 0) resolve.put("executionContextId", context);
            JsonNode object = cdp.call("DOM.resolveNode", resolve, Duration.ofSeconds(5)).path("object");
            String objectId = object.path("objectId").asText("");
            if (objectId.isBlank()) return new Effect("resolveNode", false);
            String before = title(cdp);
            ObjectNode call = Jsons.object();
            call.put("objectId", objectId);
            call.put("functionDeclaration", "function(){ this.click(); return document.title; }");
            call.put("returnByValue", true);
            JsonNode called = cdp.call("Runtime.callFunctionOn", call, Duration.ofSeconds(5));
            if (called.has("exceptionDetails")) return new Effect("resolveNode", false);
            String after = title(cdp);
            return new Effect("resolveNode", !before.equals(after));
        } catch (RuntimeException failure) {
            return new Effect("resolveNode", false);
        }
    }

    private static void stamp(Map<String, Object> body, CdpConnection cdp, DomNode node) {
        Object existing = body.get("frame");
        if (existing instanceof String text && !text.isBlank()) return;
        String frame = "";
        if (node != null && node.frameId != null && !node.frameId.isBlank() && !"main".equals(node.frameId)) frame = node.frameId;
        if (frame.isBlank() && cdp != null) {
            try {
                frame = mainFrameId(cdp);
            } catch (RuntimeException ignored) {
                frame = "";
            }
        }
        if (!frame.isBlank()) body.put("frame", frame);
    }

    private static void stampDesktop(Map<String, Object> body, DesktopTree.Session session) {
        Object existing = body.get("frame");
        if (existing instanceof String text && !text.isBlank()) return;
        if (session == null || session.root() == null) return;
        String window = windowName(session.root());
        if (!window.isBlank()) body.put("frame", window);
    }

    /** The nearest window name. A control inside the window is not the frame. */
    private static String windowName(DomNode node) {
        if (node == null) return "";
        if ("window".equalsIgnoreCase(node.role == null ? "" : node.role)) {
            String name = node.accessibleName == null ? "" : node.accessibleName;
            if (!name.isBlank()) return name;
        }
        for (DomNode child : node.children) {
            String name = windowName(child);
            if (!name.isBlank()) return name;
        }
        return "";
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
            case "eval", "attach" -> "inject";
            case "explain" -> "explain";
            case "find" -> "locate";
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

    private record Effect(String used, boolean applied) {
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
