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
import dev.domengine.snapshot.DesktopSnapshotProducer;
import dev.domengine.tree.DomNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Browser and desktop verbs on the existing engine path:
 * {@link Query#run}, {@link PageActions}, and {@link DesktopTree}.
 */
public final class TargetVerb {
    private static final Set<String> EVENT_CONDITIONS = Set.of(
            "mutation", "network", "load", "navigation", "mask"
    );
    private static final Set<String> INTERACTIVE = Set.of(
            "a", "button", "input", "select", "textarea", "option",
            "tab", "link", "menuitem", "checkbox", "radio", "switch", "textbox", "combobox", "searchbox"
    );
    private static final int HIT_CAP = 512;
    /** How long a dispatched click waits for a visible change before it is reported quiet. */
    private static final long CLICK_SETTLE_MS = 500;
    /** Least-recently used refs drop once the cap is passed. */
    private static final Map<String, Hit> HITS = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Hit> eldest) {
            return size() > HIT_CAP;
        }
    });
    private static final ConcurrentHashMap<String, String> BASELINE = new ConcurrentHashMap<>();
    /** Ids for {@code --snap=auto} files. One id names the before and after pair. */
    private static final AtomicInteger AUTO_SEQ = new AtomicInteger();
    /** Frame id chosen by {@code frame --enter} for this run. {@code frame --reset} clears it. */
    private static final ConcurrentHashMap<String, String> ENTERED = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> RUN = new ThreadLocal<>();

    private TargetVerb() {
    }

    public record Outcome(int exit, Map<String, Object> body) {
    }

    private static final Set<String> ACT_KEYS = Set.of(
            "target", "kind", "query", "ref", "value", "snap", "run-id", "agent", "agent-id",
            "fixture", "timeout", "condition", "frame", "group", "sequence",
            "format", "fields", "where", "limit", "budget", "pick", "session", "sessionId", "exposure", "purpose", "file"
    );

    /** CSS or text selector for a fresh {@code ref:}. Null when the ref is unknown or stale. */
    public static String anchorSelector(String raw) {
        String ref = raw == null ? "" : raw.trim();
        String expanded = SessionIds.expand(ref);
        if (expanded != null) ref = expanded;
        else if (!ref.startsWith("ref:")) ref = "ref:" + ref;
        Hit hit = HITS.get(ref);
        if (hit == null || hit.selector == null || hit.selector.isBlank()) return null;
        if (stale(hit)) return null;
        return hit.selector;
    }

    /**
     * Handle a placement can use for a short id or {@code ref:}.
     * The element id when that is what snap or find stored, otherwise {@code frame:backend}.
     * Never the accessible name. Null when this process did not publish the token.
     */
    public static String placementHandle(String raw) {
        Hit hit = published(raw);
        if (hit == null) return null;
        String selector = hit.selector == null ? "" : hit.selector.trim();
        String query = hit.query == null ? "" : hit.query.trim();
        if (selector.startsWith("#") && selector.length() > 1 && !selector.equals(query)) {
            return selector.substring(1);
        }
        String frame = hit.frameId == null || hit.frameId.isBlank() || "main".equals(hit.frameId) ? "top" : hit.frameId;
        return frame + ":" + hit.backend;
    }

    private static Hit published(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String token = raw.trim();
        Hit direct = HITS.get(token);
        if (direct != null) return direct;
        String expanded = SessionIds.expand(token);
        if (expanded != null) {
            Hit hit = HITS.get(expanded);
            if (hit != null) return hit;
        }
        if (!token.startsWith("ref:")) token = "ref:" + token;
        return HITS.get(token);
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

    /**
     * Paints {@code handle} on the selected browser page. The host page is not touched.
     * {@code applied} is true only when that page reads back {@code data-pkb-style=style_modified}.
     */
    public static Outcome highlight(String targetRaw, String handle, String css, String runId) {
        String lookup = handle == null ? "" : handle;
        String placed = placementHandle(handle);
        if (placed != null && !placed.isBlank()) lookup = placed;
        else {
            String selector = TargetHandles.selector(handle);
            if (selector != null && !selector.isBlank()) lookup = selector;
        }
        TargetSelector.Parsed target = TargetSelector.parse(targetRaw);
        if (target == null || "desktop".equals(target.kind()) || "none".equals(target.kind())) {
            Outcome failed = fail("highlight", "unknown target");
            failed.body().put("applied", false);
            return failed;
        }
        Held page = openBrowser("ref".equals(target.kind()) ? TargetSelector.parse("browser:auto") : target);
        CdpConnection cdp = page.session.connection();
        try {
            String sentence = tools.dscode.browser.SurfaceAssets.sentence("highlight");
            if (ranked(lookup)) return paintRanked(cdp, runId, target, handle, lookup, css, sentence);
            String script = "(function(){"
                    + "var handle=" + jsString(lookup) + ",css=" + jsString(css) + ",sentence=" + jsString(sentence) + ";"
                    + "var el=document.getElementById(handle);"
                    + "if(!el && handle && handle.charAt(0)==='#') el=document.getElementById(handle.slice(1));"
                    + "if(!el){try{el=document.querySelector(handle);}catch(e){el=null;}}"
                    + "if(!el){var all=document.querySelectorAll('[data-pkb-handle],[data-pkb-id]');"
                    + "for(var i=0;i<all.length;i++){var mark=all[i].getAttribute('data-pkb-handle')||all[i].getAttribute('data-pkb-id');"
                    + "if(mark===handle){el=all[i];break;}}}"
                    + "if(!el) return {ok:false,error:'unknown handle'};"
                    + "if(!el.hasAttribute('data-pkb-original-style')) el.setAttribute('data-pkb-original-style', el.getAttribute('style')||'');"
                    + "var original=el.getAttribute('data-pkb-original-style')||'';"
                    + "var joined=original; if(joined&&css&&joined.trim().slice(-1)!==';') joined+=';';"
                    + "el.setAttribute('style', joined+css);"
                    + "el.setAttribute('data-pkb-style','style_modified');"
                    + "el.setAttribute('data-pkb-sentence', sentence);"
                    + "el.setAttribute('data-pkb-css', css);"
                    + "el.setAttribute('data-pkb-highlight', " + jsString(CollabRows.presetOf(css)) + ");"
                    + "return {ok:true,style:el.getAttribute('data-pkb-style'),css:el.getAttribute('data-pkb-css')};"
                    + "})()";
            ObjectNode params = Jsons.object();
            params.put("expression", script);
            params.put("returnByValue", true);
            String session = enteredSession(cdp, runId);
            JsonNode called = session.isBlank()
                    ? cdp.call("Runtime.evaluate", params, Duration.ofSeconds(10))
                    : cdp.call("Runtime.evaluate", params, session, Duration.ofSeconds(10));
            JsonNode payload = called.path("result").path("value");
            boolean painted = payload.path("ok").asBoolean(false)
                    && "style_modified".equals(payload.path("style").asText(""));
            if (!painted) {
                String error = payload.path("error").asText("highlight did not paint");
                Outcome failed = fail("highlight", error);
                failed.body().put("applied", false);
                failed.body().put("handle", handle == null ? "" : handle);
                stamp(failed.body(), cdp, null);
                putEntered(failed.body(), runId, cdp);
                return failed;
            }
            Map<String, Object> body = base("highlight", "cdp", "browser", target);
            body.put("applied", true);
            body.put("handle", handle);
            body.put("cdp", "style");
            body.put("labels", List.of("style_modified"));
            stamp(body, cdp, null);
            putEntered(body, runId, cdp);
            return ok(body);
        } catch (RuntimeException failure) {
            String message = failure.getMessage() == null ? "highlight did not paint" : failure.getMessage();
            Outcome failed = fail("highlight", message);
            failed.body().put("applied", false);
            failed.body().put("handle", handle == null ? "" : handle);
            stamp(failed.body(), cdp, null);
            putEntered(failed.body(), runId, cdp);
            return failed;
        } finally {
            page.release();
        }
    }

    private static boolean ranked(String handle) {
        if (handle == null) return false;
        int colon = handle.lastIndexOf(':');
        if (colon <= 0 || colon >= handle.length() - 1) return false;
        try {
            Long.parseLong(handle.substring(colon + 1));
            return true;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    /** Paints a {@code frame:backend} handle. The accessible name is not a selector. */
    private static Outcome paintRanked(
            CdpConnection cdp,
            String runId,
            TargetSelector.Parsed target,
            String handle,
            String lookup,
            String css,
            String sentence
    ) {
        int colon = lookup.lastIndexOf(':');
        DomNode node = new DomNode();
        node.backendNodeId = Long.parseLong(lookup.substring(colon + 1));
        String frame = lookup.substring(0, colon);
        String session = frame.isBlank() || "top".equals(frame)
                ? enteredSession(cdp, runId)
                : FrameCoverage.sessionId(cdp, frame);
        if (session == null || session.isBlank()) session = enteredSession(cdp, runId);
        String function = "function(){var css=" + jsString(css) + ",sentence=" + jsString(sentence) + ";"
                + "if(!this||this.nodeType!==1) return '';"
                + "if(!this.hasAttribute('data-pkb-original-style')) this.setAttribute('data-pkb-original-style', this.getAttribute('style')||'');"
                + "var original=this.getAttribute('data-pkb-original-style')||'';"
                + "var joined=original; if(joined&&css&&joined.trim().slice(-1)!==';') joined+=';';"
                + "this.setAttribute('style', joined+css);"
                + "this.setAttribute('data-pkb-style','style_modified');"
                + "this.setAttribute('data-pkb-sentence', sentence);"
                + "this.setAttribute('data-pkb-css', css);"
                + "return this.getAttribute('data-pkb-style');}";
        String read = callOnNode(cdp, session, node, function);
        if (!"style_modified".equals(read)) {
            Outcome failed = fail("highlight", "unknown handle");
            failed.body().put("applied", false);
            failed.body().put("handle", handle == null ? "" : handle);
            stamp(failed.body(), cdp, null);
            putEntered(failed.body(), runId, cdp);
            return failed;
        }
        Map<String, Object> body = base("highlight", "cdp", "browser", target);
        body.put("applied", true);
        body.put("handle", handle == null ? "" : handle);
        body.put("cdp", "style");
        body.put("labels", List.of("style_modified"));
        stamp(body, cdp, null);
        putEntered(body, runId, cdp);
        return ok(body);
    }

    public static Outcome run(Path project, String runId, String op, Map<String, String> args) {
        String verb = op == null ? "" : op;
        Map<String, String> values = SessionIds.rewrite(args == null ? Map.of() : args);
        RUN.set(runId);
        try {
            TargetSelector.Parsed target = TargetSelector.parse(values.get("target"));
            if (needsLiveTarget(verb) && (target.raw().isBlank() || "none".equals(target.kind())) && liveBrowserPresent()) {
                target = TargetSelector.parse("browser:auto");
            }
            if ("ref".equals(target.kind()) && values.get("ref") == null) {
                values = new LinkedHashMap<>(values);
                values.put("ref", target.raw());
            }
            return switch (verb) {
                case "attach" -> attach(project, runId, target, values);
                case "snap" -> snap(project, runId, target, values);
                case "find" -> find(project, runId, target, values);
                case "act" -> withPick(act(project, runId, target, values));
                case "form-fill" -> formFill(project, runId, target, values);
                case "wait", "wait-for" -> wait(project, runId, target, values);
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

    /** Page verbs with no selector use the browser this process already has open. */
    private static boolean needsLiveTarget(String verb) {
        return switch (verb == null ? "" : verb) {
            case "snap", "find", "act", "form-fill", "wait", "wait-for", "watch", "explain", "capture", "eval" -> true;
            default -> false;
        };
    }

    private static boolean liveBrowserPresent() {
        if (LiveBrowserHost.chrome() != null) return true;
        try {
            return tools.dscode.common.agentcontrol.AgentControl.scenarioDriver() != null;
        } catch (RuntimeException failure) {
            return false;
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
            putEntered(body, runId, page.session.connection());
            return ok(body);
        } finally {
            page.release();
        }
    }

    private static Outcome find(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        String query = withPurpose(blank(args.get("query")), args.get("purpose"));
        if ("desktop".equals(target.kind()) || ("ref".equals(target.kind()) && desktopHit(args.get("ref")))) {
            return findDesktop(runId, target, args, query);
        }
        Held page = openBrowser(resolved(target, args));
        try {
            Map<String, String> flags = scopedFrame(runId, target);
            DomNode root = tree(page.session);
            Map<String, Object> capped = null;
            List<DomNode> hits;
            if (args.get("scroll-search") == null) {
                hits = locate(root, query, flags);
            } else {
                ScrollSearch searched = scrollFind(page.session, query, flags);
                hits = searched.hits();
                capped = searched.capped();
            }
            if (hits.isEmpty()) {
                Outcome missed = fail("find", "not found");
                if (capped != null) missed.body().put("scrollSearch", capped);
                stamp(missed.body(), page.session.connection(), null);
                putEntered(missed.body(), runId, page.session.connection());
                return missed;
            }
            String version = docVersion(page.session.connection());
            List<Map<String, Object>> rows = publish(runId, "browser", page.session.id, version, hits, query, page.session.connection());
            String exposure = exposureOf(args);
            if (exposure == null) return fail("find", "exposure is human or full");
            if ("human".equals(exposure)) rows.removeIf(row -> Boolean.FALSE.equals(row.get("visible")));
            if (rows.isEmpty()) return fail("find", "not found");
            CollabRows.narrow(rows, blank(args.get("include")));
            remember(runId, page.session);
            Map<String, Object> body = base("find", "cdp", "browser", target);
            body.put("docVersion", version);
            body.put("hits", rows);
            body.put("ref", rows.get(0).get("ref"));
            if (args.get("scroll-search") != null) body.put("scrollSearch", true);
            markBudget(body, args);
            stamp(body, page.session.connection(), hits.get(0));
            putEntered(body, runId, page.session.connection(), rows);
            stampContexts(body, root);
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
        markBudget(body, args);
        stampDesktop(body, session);
        return ok(body);
    }

    /** Candidates and the pick reason for the act running on this thread, added to its reply. */
    private static final ThreadLocal<Map<String, Object>> PICKED = new ThreadLocal<>();

    private static Outcome withPick(Outcome outcome) {
        Map<String, Object> note = PICKED.get();
        PICKED.remove();
        if (note != null && outcome != null && outcome.body() != null) outcome.body().putAll(note);
        return outcome;
    }

    private static void notePick(List<DomNode> hits, DomNode chosen, String pick) {
        if (hits == null || hits.size() < 2 || chosen == null) return;
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < hits.size() && i < 12; i++) {
            DomNode node = hits.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i + 1);
            row.put("role", role(node));
            row.put("name", name(node));
            String purpose = attr(node, "data-pkb-purpose");
            if (!purpose.isBlank()) row.put("purpose", String.join(",", purpose.trim().split("\\s+")));
            candidates.add(row);
        }
        Map<String, Object> picked = new LinkedHashMap<>();
        picked.put("index", hits.indexOf(chosen) + 1);
        picked.put("role", role(chosen));
        picked.put("name", name(chosen));
        String token = pick == null ? "" : pick.trim();
        picked.put("why", token.matches("\\d+") ? "--pick=" + token + " is position " + token + " of " + hits.size()
                : "--pick=" + token + " matched its id, name, or role");
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("candidates", candidates);
        note.put("picked", picked);
        PICKED.set(note);
    }

    private static Outcome act(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        PICKED.remove();
        for (String key : args.keySet()) {
            if (key != null && !key.isBlank() && !ACT_KEYS.contains(key)) {
                return fail("act", "act does not use --" + key);
            }
        }
        String ref = blank(args.get("ref"));
        if (ref.isBlank() && "ref".equals(target.kind())) ref = target.raw();
        if (ref.isBlank() && SessionIds.expand(blank(args.get("query"))) != null) ref = blank(args.get("query"));
        Hit hit = ref.isBlank() ? null : requireHit(ref);
        if (hit != null && "desktop".equals(hit.kind)) return actDesktop(project, runId, hit, args);
        if ("desktop".equals(target.kind())) return actDesktop(project, runId, target, args);
        Held page = openBrowser(hit == null ? target : browserOf(hit, target));
        try {
            DomNode root = tree(page.session);
            DomNode node;
            String query = withPurpose(blank(args.get("query")), args.get("purpose"));
            if (hit != null) {
                node = freshNode(hit, page.session.connection(), root);
                if (node == null) {
                    Outcome missed = staleRef("act");
                    stamp(missed.body(), page.session.connection(), null);
                    putEntered(missed.body(), runId, page.session.connection());
                    return missed;
                }
                query = hit.query;
            } else {
                List<DomNode> hits = locate(root, query, scopedFrame(runId, target));
                if (hits.isEmpty()) {
                    Outcome missed = fail("act", "not found");
                    stamp(missed.body(), page.session.connection(), null);
                    putEntered(missed.body(), runId, page.session.connection());
                    return missed;
                }
                if (hits.size() > 1 || !blank(args.get("pick")).isBlank()) {
                    DomNode chosen = blank(args.get("pick")).isBlank() ? null : pickHit(hits, args.get("pick"));
                    if (chosen == null) {
                        Outcome missed = fail("act", "ambiguous");
                        missed.body().put("candidates", publish(runId, "browser", page.session.id, docVersion(page.session.connection()), hits, query, page.session.connection()));
                        missed.body().put("fix", blank(args.get("pick")).isBlank()
                                ? "pass --ref or a tighter --query"
                                : "pass --pick as a 1-based index or the accessible name");
                        stamp(missed.body(), page.session.connection(), hits.get(0));
                        putEntered(missed.body(), runId, page.session.connection());
                        putShadow(missed.body(), hits.get(0));
                        return missed;
                    }
                    notePick(hits, chosen, args.get("pick"));
                    node = chosen;
                } else {
                    node = hits.get(0);
                }
            }
            String before = docVersion(page.session.connection());
            String titleBefore = title(page.session.connection());
            String kind = blank(args.get("kind"));
            tools.dscode.common.domoperations.MaskQuiet.Wait mask = null;
            if (kind.isBlank() || "click".equals(kind)) {
                mask = awaitMaskQuiet(page.session.connection(), node, timeoutMs(args));
                if (maskStillCovering(mask)) {
                    Outcome failed = fail("act", "covered");
                    failed.body().put("applied", false);
                    failed.body().put("dispatched", false);
                    failed.body().put("reason", "loading mask still covers the click");
                    stamp(failed.body(), page.session.connection(), node);
                    putEntered(failed.body(), runId, page.session.connection());
                    putShadow(failed.body(), node);
                    putMask(failed.body(), mask);
                    return failed;
                }
            }
            boolean wantDiff = "diff".equals(blank(args.get("snap")));
            boolean wantAuto = "auto".equals(blank(args.get("snap")));
            List<SnapRow> rowsBefore = wantDiff
                    ? elementRows(page.session.connection(), page.session.id, before) : List.of();
            List<String> autoBefore = wantAuto ? compactBrowser(page.session, runId, target) : List.of();
            Effect effect = perform(project, runId, page.session, node, args, query, selector(node, query));
            List<SnapRow> rowsAfter = wantDiff
                    ? elementRows(page.session.connection(), page.session.id, docVersion(page.session.connection())) : List.of();
            if (effect.refused()) {
                // A cover is its own error. Disabled, inert, and detached stay "could not land".
                boolean covered = "covered".equals(effect.reason());
                Outcome failed = fail("act", covered ? "covered" : "could not land");
                failed.body().put("used", effect.used());
                failed.body().put("dispatched", false);
                failed.body().put("visible", covered ? false : elementVisible(node, page.session.connection()));
                failed.body().put("reason", effect.reason());
                if (covered) failed.body().put("coveredBy", effect.detail());
                stamp(failed.body(), page.session.connection(), node);
                putEntered(failed.body(), runId, page.session.connection());
                putShadow(failed.body(), node);
                putSnapDiff(failed.body(), args, titleBefore, title(page.session.connection()), before, docVersion(page.session.connection()), rowsBefore, rowsAfter);
                putAutoSnap(failed.body(), args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
                putMask(failed.body(), mask);
                return failed;
            }
            String condition = blank(args.get("condition"));
            // The condition is the success test. A quiet click still waits, and it is not sent again.
            // A refused click already returned. A click that was not dispatched does not wait.
            if (!condition.isBlank() && (effect.applied() || effect.quiet())) {
                boolean met = waitPoll(
                        page.session.connection(),
                        condition,
                        query,
                        timeoutMs(args),
                        scopedFrame(runId, target),
                        enteredSession(page.session.connection(), runId));
                String titleAfter = title(page.session.connection());
                String after = docVersion(page.session.connection());
                if (wantDiff) {
                    rowsAfter = elementRows(page.session.connection(), page.session.id, after);
                }
                if (!met) {
                    Outcome failed = fail("act", "timeout");
                    // The click landed when the effect was read back. A quiet click that never meets the condition did not.
                    failed.body().put("applied", effect.applied());
                    failed.body().put("condition", condition);
                    failed.body().put("title", titleAfter);
                    if (kind.isBlank() || "click".equals(kind) || "hover".equals(kind)) {
                        failed.body().put("dispatched", true);
                        failed.body().put("visible", elementVisible(node, page.session.connection()));
                    }
                    putSnapDiff(failed.body(), args, titleBefore, titleAfter, before, after, rowsBefore, rowsAfter);
                    putAutoSnap(failed.body(), args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
                    stamp(failed.body(), page.session.connection(), node);
                    putEntered(failed.body(), runId, page.session.connection());
                    putShadow(failed.body(), node);
                    putMask(failed.body(), mask);
                    return failed;
                }
                Map<String, Object> body = base("act", effect.used(), "browser", target);
                body.put("applied", true);
                body.put("docVersion", after);
                body.put("kind", kind.isBlank() ? "click" : kind);
                body.put("condition", condition);
                body.put("title", titleAfter);
                if (kind.isBlank() || "click".equals(kind) || "hover".equals(kind)) {
                    body.put("dispatched", true);
                    body.put("visible", elementVisible(node, page.session.connection()));
                }
                putSnapDiff(body, args, titleBefore, titleAfter, before, after, rowsBefore, rowsAfter);
                putAutoSnap(body, args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
                stamp(body, page.session.connection(), node);
                putEntered(body, runId, page.session.connection());
                putShadow(body, node);
                putMask(body, mask);
                putReadBack(body, node, true);
                return ok(body);
            }
            if (effect.quiet()) {
                String titleAfter = title(page.session.connection());
                String after = docVersion(page.session.connection());
                boolean stillCovered = maskCovers(page.session.connection(), node);
                if (stillCovered || maskStillCovering(mask)) {
                    Outcome failed = fail("act", "covered");
                    failed.body().put("applied", false);
                    failed.body().put("dispatched", true);
                    failed.body().put("visible", false);
                    failed.body().put("reason", "loading mask still covers the click");
                    failed.body().put("kind", kind.isBlank() ? "click" : kind);
                    failed.body().put("docVersion", after);
                    putSnapDiff(failed.body(), args, titleBefore, titleAfter, before, after, rowsBefore, rowsAfter);
                    putAutoSnap(failed.body(), args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
                    stamp(failed.body(), page.session.connection(), node);
                    putEntered(failed.body(), runId, page.session.connection());
                    putShadow(failed.body(), node);
                    putMask(failed.body(), mask);
                    putReadBack(failed.body(), node, true);
                    return failed;
                }
                Map<String, Object> body = base("act", effect.used(), "browser", target);
                body.put("applied", effect.unknownApplied() ? "unknown" : false);
                body.put("dispatched", true);
                body.put("visible", elementVisible(node, page.session.connection()));
                body.put("reason", effect.unknownApplied() ? "no before/after read" : effect.reason());
                body.put("kind", kind.isBlank() ? "click" : kind);
                body.put("docVersion", after);
                putSnapDiff(body, args, titleBefore, titleAfter, before, after, rowsBefore, rowsAfter);
                putAutoSnap(body, args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
                stamp(body, page.session.connection(), node);
                putEntered(body, runId, page.session.connection());
                putShadow(body, node);
                putMask(body, mask);
                putReadBack(body, node, true);
                return ok(body);
            }
            if (!effect.applied()) {
                Outcome failed = fail("act", "not applied");
                failed.body().put("used", effect.used());
                // A key was sent to the focused node. Only a click or hover that never reached the page is undispatched.
                failed.body().put("dispatched", "press".equals(kind));
                stamp(failed.body(), page.session.connection(), node);
                putEntered(failed.body(), runId, page.session.connection());
                putShadow(failed.body(), node);
                putSnapDiff(failed.body(), args, titleBefore, title(page.session.connection()), before, docVersion(page.session.connection()), rowsBefore, rowsAfter);
                putAutoSnap(failed.body(), args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
                putMask(failed.body(), mask);
                return failed;
            }
            String after = docVersion(page.session.connection());
            String titleAfter = title(page.session.connection());
            Map<String, Object> body = base("act", effect.used(), "browser", target);
            body.put("applied", true);
            body.put("docVersion", after);
            body.put("kind", kind.isBlank() ? "click" : kind);
            if (kind.isBlank() || "click".equals(kind) || "hover".equals(kind)) {
                body.put("dispatched", true);
                body.put("visible", elementVisible(node, page.session.connection()));
            }
            putSnapDiff(body, args, titleBefore, titleAfter, before, after, rowsBefore, rowsAfter);
            putAutoSnap(body, args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
            stamp(body, page.session.connection(), node);
            putEntered(body, runId, page.session.connection());
            putShadow(body, node);
            putMask(body, mask);
            putReadBack(body, node, true);
            return ok(body);
        } finally {
            page.release();
        }
    }

    /** One call fills each field and reads it back. A missing key writes nothing. */
    private static Outcome formFill(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        if ("desktop".equals(target.kind())) return fail("form-fill", "form-fill is browser only");
        String raw = blank(args.get("fields"));
        if (raw.isBlank()) return fail("form-fill", "form-fill requires --fields");
        List<String[]> pairs = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            if (part.isBlank()) continue;
            int eq = part.indexOf('=');
            if (eq <= 0) return fail("form-fill", "fields are id=value separated by |");
            pairs.add(new String[]{part.substring(0, eq).trim(), part.substring(eq + 1)});
        }
        if (pairs.isEmpty()) return fail("form-fill", "form-fill requires --fields");
        Held page = openBrowser(resolved(target, args));
        try {
            DomNode root = tree(page.session);
            List<DomNode> nodes = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String[] pair : pairs) {
                DomNode node = fieldNode(root, pair[0]);
                nodes.add(node);
                if (node == null) missing.add(pair[0]);
            }
            if (!missing.isEmpty()) {
                Outcome missed = fail("form-fill", "not found");
                missed.body().put("missing", missing);
                missed.body().put("fields", unread(page.session.connection(), pairs, nodes));
                stamp(missed.body(), page.session.connection(), null);
                putEntered(missed.body(), runId, page.session.connection());
                return missed;
            }
            List<Map<String, Object>> filled = new ArrayList<>();
            boolean all = true;
            markAgentActing(page.session.connection(), 5000);
            for (int i = 0; i < pairs.size(); i++) {
                String[] pair = pairs.get(i);
                DomNode node = nodes.get(i);
                Map<String, Object> row = writeField(project, runId, page.session, node, pair[0], pair[1]);
                if (!Boolean.TRUE.equals(row.get("applied"))) all = false;
                filled.add(row);
            }
            markAgentActing(page.session.connection(), AGENT_ACTING_TAIL_MS);
            if (!all) {
                Outcome failed = fail("form-fill", "not applied");
                failed.body().put("applied", false);
                failed.body().put("fields", filled);
                stamp(failed.body(), page.session.connection(), null);
                putEntered(failed.body(), runId, page.session.connection());
                return failed;
            }
            Map<String, Object> body = base("form-fill", "cdp", "browser", target);
            body.put("applied", true);
            body.put("fields", filled);
            stamp(body, page.session.connection(), null);
            putEntered(body, runId, page.session.connection());
            return ok(body);
        } finally {
            page.release();
        }
    }

    /** Read-back rows for a fill that wrote nothing. A missing key has no value. */
    private static List<Map<String, Object>> unread(CdpConnection cdp, List<String[]> pairs, List<DomNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("field", pairs.get(i)[0]);
            DomNode node = nodes.get(i);
            if (node == null) {
                row.put("found", false);
            } else {
                row.put("value", readField(cdp, node));
                row.put("applied", false);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Select uses the select path. A checkbox or radio sets checked. Text is typed only after focus
     * is confirmed on that node, so a later field cannot land in the previous one.
     */
    private static Map<String, Object> writeField(
            Path project,
            String runId,
            BrowserSession session,
            DomNode node,
            String key,
            String value
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("field", key);
        String kind = fieldKind(node);
        row.put("kind", kind);
        CdpConnection cdp = session.connection();
        if ("checkbox".equals(kind) || "radio".equals(kind)) {
            boolean want = "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
            if (!focusConfirmed(cdp, node)) {
                row.put("value", readField(cdp, node));
                row.put("applied", false);
                return row;
            }
            String read = callOnNode(cdp, cdpSessionOf(node), node, ""
                    + "function(){ this.checked=" + want + ";"
                    + " this.dispatchEvent(new Event('input',{bubbles:true}));"
                    + " this.dispatchEvent(new Event('change',{bubbles:true}));"
                    + " return this.checked?'true':'false'; }");
            boolean stuck = read != null && want == "true".equals(read);
            row.put("value", read == null ? readField(cdp, node) : read);
            row.put("applied", stuck);
            return row;
        }
        if ("text".equals(kind) && !focusConfirmed(cdp, node)) {
            row.put("value", readField(cdp, node));
            row.put("applied", false);
            return row;
        }
        Map<String, String> one = new LinkedHashMap<>();
        one.put("kind", "select".equals(kind) ? "select" : "type");
        one.put("value", value);
        Effect effect;
        try {
            effect = perform(project, runId, session, node, one, key, selector(node, key));
        } catch (Miss missing) {
            row.put("value", readField(cdp, node));
            row.put("applied", false);
            row.put("error", missing.error);
            return row;
        }
        String read = readField(cdp, node);
        boolean stuck = effect.applied() && valueMatches(value, read);
        row.put("value", read);
        row.put("applied", stuck);
        return row;
    }

    /** Debounced input rows are sensed this long after the last agent keystroke and still count as the agent's. */
    static final long AGENT_ACTING_TAIL_MS = 1500;

    /**
     * Marks the next {@code ms} as agent input so the page runtime tags interaction rows {@code source=agent}.
     * The mark is a {@code data-pkb-} attribute on the root, which the page watchers already ignore.
     */
    static void markAgentActing(CdpConnection cdp, long ms) {
        if (cdp == null) return;
        evalText(cdp, "", "(function(){try{document.documentElement.setAttribute('data-pkb-agent-acting',String(Date.now()+"
                + Math.max(0, ms) + "));return '1';}catch(e){return '';}})()");
    }

    private static String readField(CdpConnection cdp, DomNode node) {
        if (node == null) return "";
        String kind = fieldKind(node);
        if ("checkbox".equals(kind) || "radio".equals(kind)) return checkedOf(cdp, node);
        if ("select".equals(kind)) return controlValue(cdp, selectOf(node));
        return controlValue(cdp, node);
    }

    private static String fieldKind(DomNode node) {
        if (node == null) return "text";
        String tag = node.tag();
        if ("select".equals(tag) || "option".equals(tag)) return "select";
        String type = attr(node, "type").toLowerCase(Locale.ROOT);
        String roleName = node.role == null ? "" : node.role.toLowerCase(Locale.ROOT);
        if ("checkbox".equals(type) || "checkbox".equals(roleName)) return "checkbox";
        if ("radio".equals(type) || "radio".equals(roleName)) return "radio";
        return "text";
    }

    /** Focuses the node and reports whether it is the active element. */
    private static boolean focusConfirmed(CdpConnection cdp, DomNode node) {
        String read = callOnNode(cdp, cdpSessionOf(node), node,
                "function(){ this.focus(); return document.activeElement===this; }");
        return "true".equals(read);
    }

    static DomNode fieldNode(DomNode root, String key) {
        if (root == null || key == null || key.isBlank()) return null;
        String token = key.trim();
        DomNode byRef = nodeForTarget(root, token);
        if (byRef != null) return isField(byRef) ? byRef : firstField(byRef) != null ? firstField(byRef) : byRef;
        DomNode direct = matchField(root, token);
        if (direct != null) return direct;
        return controlForLabel(root, token);
    }

    /** A short id or {@code ref:} names the node that snap published. */
    static DomNode nodeForTarget(DomNode root, String token) {
        if (root == null || token == null || token.isBlank()) return null;
        String expanded = SessionIds.expand(token.trim());
        String ref = expanded != null ? expanded : token.trim();
        if (!ref.startsWith("ref:")) return null;
        return nodeByRef(root, ref);
    }

    private static String labelKey(String text) {
        String trimmed = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        while (trimmed.endsWith(":") || trimmed.endsWith("*")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static DomNode matchField(DomNode node, String key) {
        if (node == null) return null;
        if (node.nodeType == 1 && isField(node)) {
            if (key.equals(attr(node, "id")) || key.equals(attr(node, "name"))) return node;
            String want = labelKey(key);
            if (!want.isEmpty() && (want.equals(labelKey(attr(node, "id")))
                    || want.equals(labelKey(attr(node, "name")))
                    || want.equals(labelKey(node.accessibleName))
                    || want.equals(labelKey(attr(node, "placeholder")))
                    || want.equals(labelKey(attr(node, "aria-label"))))) return node;
        }
        for (DomNode child : node.children) {
            DomNode found = matchField(child, key);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = matchField(shadow, key);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isField(DomNode node) {
        String tag = node.tag();
        return "input".equals(tag) || "textarea".equals(tag) || "select".equals(tag) || "option".equals(tag);
    }

    /** The control named by a label's text, via {@code for} or by wrapping that control. */
    private static DomNode controlForLabel(DomNode node, String key) {
        if (node == null) return null;
        if ("label".equals(node.tag()) && labelKey(key).equals(labelKey(HtmlTree.textOf(node)))) {
            String forId = attr(node, "for");
            if (!forId.isBlank()) {
                DomNode root = node;
                while (root.parent != null) root = root.parent;
                DomNode target = findId(root, forId);
                if (target != null && isField(target)) return target;
            }
            DomNode wrapped = firstField(node);
            if (wrapped != null) return wrapped;
        }
        for (DomNode child : node.children) {
            DomNode found = controlForLabel(child, key);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = controlForLabel(shadow, key);
            if (found != null) return found;
        }
        return null;
    }

    private static DomNode findId(DomNode node, String id) {
        if (node == null) return null;
        if (id.equals(attr(node, "id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = findId(child, id);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = findId(shadow, id);
            if (found != null) return found;
        }
        return null;
    }

    private static DomNode firstField(DomNode node) {
        if (node == null) return null;
        for (DomNode child : node.children) {
            if (isField(child)) return child;
            DomNode found = firstField(child);
            if (found != null) return found;
        }
        return null;
    }

    private static Outcome actDesktop(Path project, String runId, Hit hit, Map<String, String> args) {
        if (stale(hit)) return staleRef("act");
        DesktopTree.Session session = DesktopTree.require(hit.sessionId);
        DomNode node = findNode(session.root(), "", hit.backend);
        if (node == null) return staleRef("act");
        String beforeVersion = "desktop:" + session.sequence();
        List<SnapRow> rowsBefore = desktopRows(session);
        boolean wantAuto = "auto".equals(blank(args.get("snap")));
        List<String> autoBefore = wantAuto ? compactDesktop(session, runId) : List.of();
        boolean live = desktopAct(session, node, args);
        DesktopTree.Session after = DesktopTree.open(session.id());
        if (after == null) after = session;
        Map<String, Object> body = base("act", "desktop", "desktop", TargetSelector.parse("desktop:" + hit.sessionId));
        body.put("applied", live);
        String afterVersion = "desktop:" + after.sequence();
        body.put("docVersion", afterVersion);
        body.put("session", after.id());
        putSnapDiff(body, args, "", "", beforeVersion, afterVersion, rowsBefore, desktopRows(after));
        DesktopTree.Session snapped = after;
        putAutoSnap(body, args, project, runId, autoBefore, () -> compactDesktop(snapped, runId));
        stampDesktop(body, after);
        putReadBack(body, node, true);
        return ok(body);
    }

    private static Outcome actDesktop(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args) {
        DesktopTree.Session session = openDesktop(target, args.get("fixture"));
        String query = blank(args.get("query"));
        List<DomNode> hits = locate(session.root(), query, Map.of());
        if (hits.isEmpty()) return fail("act", "not found");
        DomNode node = hits.get(0);
        if (hits.size() > 1 || !blank(args.get("pick")).isBlank()) {
            DomNode chosen = blank(args.get("pick")).isBlank() ? null : pickHit(hits, args.get("pick"));
            if (chosen == null) {
                Outcome missed = fail("act", "ambiguous");
                missed.body().put("candidates", publish(runId, "desktop", session.id(), "desktop:" + session.sequence(), hits, query));
                missed.body().put("fix", blank(args.get("pick")).isBlank()
                        ? "pass --ref or a tighter --query"
                        : "pass --pick as a 1-based index or the accessible name");
                return missed;
            }
            node = chosen;
        }
        String beforeVersion = "desktop:" + session.sequence();
        List<SnapRow> rowsBefore = desktopRows(session);
        boolean wantAuto = "auto".equals(blank(args.get("snap")));
        List<String> autoBefore = wantAuto ? compactDesktop(session, runId) : List.of();
        boolean live = desktopAct(session, node, args);
        DesktopTree.Session after = DesktopTree.open(session.id());
        if (after == null) after = session;
        Map<String, Object> body = base("act", "desktop", "desktop", target);
        body.put("applied", live);
        String afterVersion = "desktop:" + after.sequence();
        body.put("docVersion", afterVersion);
        body.put("session", after.id());
        putSnapDiff(body, args, "", "", beforeVersion, afterVersion, rowsBefore, desktopRows(after));
        DesktopTree.Session snapped = after;
        putAutoSnap(body, args, project, runId, autoBefore, () -> compactDesktop(snapped, runId));
        stampDesktop(body, after);
        putReadBack(body, node, true);
        return ok(body);
    }

    /**
     * A fixture click still bumps the tree and reports applied false.
     * A live click is applied only when a later walk shows a tree change, or the window has gone.
     */
    private static boolean desktopAct(DesktopTree.Session session, DomNode node, Map<String, String> args) {
        String kind = blank(args.get("kind"));
        if (kind.isBlank()) kind = "click";
        String before = desktopFingerprint(session);
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("text", blank(args.get("value")));
        switch (kind) {
            case "type" -> DesktopTree.type(session.id(), flagsWithNode(session, node, flags));
            case "click", "hover", "press", "select" -> DesktopTree.click(session, node);
            default -> throw new Miss("unknown kind " + kind);
        }
        if (!session.live()) return false;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        while (true) {
            try {
                DesktopTree.walk(session.id());
                DesktopTree.Session fresh = DesktopTree.require(session.id());
                if (!before.equals(desktopFingerprint(fresh))) return true;
            } catch (RuntimeException gone) {
                return true;
            }
            if (System.nanoTime() >= deadline) return false;
            sleep(50);
        }
    }

    /** Stable role, name, and value. Node ids are renumbered on every walk and are not part of the effect. */
    private static String desktopFingerprint(DesktopTree.Session session) {
        StringBuilder text = new StringBuilder();
        fingerprint(session == null ? null : session.root(), text);
        return text.toString();
    }

    private static void fingerprint(DomNode node, StringBuilder text) {
        if (node == null) return;
        text.append(node.role == null ? "" : node.role).append('|');
        text.append(node.accessibleName == null ? "" : node.accessibleName).append('|');
        text.append(node.nodeValue == null ? "" : node.nodeValue).append('\n');
        for (DomNode child : node.children) fingerprint(child, text);
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
            int depth = depthOf(args);
            if (depth == Integer.MIN_VALUE) return fail("snap", "depth is a number");
            DomNode root = tree(page.session);
            DomNode shownRoot = root;
            Map<String, String> scopeFlags = scopedFrame(runId, target);
            if (!scopeFlags.isEmpty()) {
                try {
                    shownRoot = Frames.scope(root, scopeFlags);
                } catch (CdpException ignored) {
                    shownRoot = root;
                }
            }
            String within = blank(args.get("within"));
            if (!within.isBlank()) {
                DomNode scoped = resolveWithin(shownRoot, within);
                if (scoped == null) return fail("snap", "not found");
                shownRoot = scoped;
            }
            String version = docVersion(page.session.connection());
            List<DomNode> shown = interactive(shownRoot, depth);
            List<Map<String, Object>> rows = publish(runId, "browser", page.session.id, version, shown, "", page.session.connection());
            String exposure = exposureOf(args);
            if (exposure == null) return fail("snap", "exposure is human or full");
            if ("human".equals(exposure)) rows.removeIf(row -> Boolean.FALSE.equals(row.get("visible")));
            rows.addAll(CollabRows.messages(shownRoot, 5));
            CollabRows.narrow(rows, blank(args.get("include")));
            String since = blank(args.get("since"));
            Map<String, Object> sinceDiff = since.isBlank() ? null : SnapSince.since(since, rows);
            if (!since.isBlank() && sinceDiff == null) return fail("snap", "unknown cursor; snap again without --since");
            String snapCursor = SnapSince.record(runId, rows);
            String picture = picture(page.session.connection(), rows);
            Map<String, Object> body = base("snap", "cdp", "browser", target);
            body.put("snapCursor", snapCursor);
            if (!exposure.isBlank()) body.put("exposure", exposure);
            if (sinceDiff != null) {
                body.putAll(sinceDiff);
                rows = changedRows(rows, sinceDiff);
            }
            String panelState = panelPending(page.session.connection());
            if (!panelState.isBlank()) body.put("panel", panelState);
            body.put("mode", mode);
            body.put("docVersion", version);
            body.put("doc_version", version);
            putEntered(body, runId, page.session.connection(), rows);
            putBudgetedLines(body, rows, project, runId, "full".equals(mode), args);
            if ("diff".equals(mode)) body.put("diff", diffAgainst(runId, picture, version));
            else BASELINE.put(baselineKey(runId), picture + "\n" + version);
            body.put("virtualized", virtualized(page.session.connection()));
            stamp(body, page.session.connection(), shownRoot);
            putEntered(body, runId, page.session.connection());
            stampContexts(body, root);
            return ok(body);
        } finally {
            page.release();
        }
    }

    /** {@code --exposure}: blank keeps every row, {@code human} drops hidden rows, {@code full}/{@code full-access} keep all. */
    static String exposureOf(Map<String, String> args) {
        String raw = blank(args == null ? null : args.get("exposure")).toLowerCase(java.util.Locale.ROOT);
        return switch (raw) {
            case "" -> "";
            case "human" -> "human";
            case "full", "full-access" -> "full-access";
            default -> null;
        };
    }

    /** With {@code --since}, the printed lines are only the added and changed rows. */
    private static List<Map<String, Object>> changedRows(List<Map<String, Object>> rows, Map<String, Object> diff) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String bucket : List.of("added", "changed")) {
            Object list = diff.get(bucket);
            if (!(list instanceof List<?> items)) continue;
            for (Object item : items) {
                String text = String.valueOf(item);
                if (text.startsWith("id=")) ids.add(text.substring(3, text.indexOf(' ') < 0 ? text.length() : text.indexOf(' ')));
            }
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : rows) if (ids.contains(String.valueOf(row.get("id")))) kept.add(row);
        return kept;
    }

    /**
     * {@code pending} when the panel is configured open on this page but its layer is not in the DOM yet,
     * {@code open} when it is there, blank when no panel is configured.
     */
    private static String panelPending(CdpConnection cdp) {
        try {
            ObjectNode params = Jsons.object();
            params.put("expression", "(function(){var l=document.querySelector('pkb-layer[data-pkb-layer]');"
                    + "var r=l&&l.shadowRoot;var p=r&&r.querySelector('.pkb-panel');"
                    + "var want=document.documentElement.getAttribute('data-pkb-panel-want');"
                    + "if(p) return 'open'; return want==='open'?'pending':'';})()");
            params.put("returnByValue", true);
            return cdp.call("Runtime.evaluate", params, Duration.ofSeconds(3)).path("result").path("value").asText("");
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static Outcome snapDesktop(Path project, String runId, TargetSelector.Parsed target, Map<String, String> args, String mode) {
        DesktopTree.Session session = desktopSession(target, args);
        if ("diff".equals(mode)) session = DesktopTree.reread(session.id());
        String version = "desktop:" + session.sequence();
        List<Map<String, Object>> rows = publish(runId, "desktop", session.id(), version, interactive(session.root()), "");
        Map<String, Object> body = base("snap", "desktop", "desktop", target);
        body.put("mode", mode);
        body.put("docVersion", version);
        body.put("applied", session.live());
        putBudgetedLines(body, rows, project, runId, "full".equals(mode), args);
        String picture = desktopPicture(session);
        if ("diff".equals(mode)) body.put("diff", desktopDiff(runId, picture));
        else BASELINE.put(desktopBaselineKey(runId), picture);
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
            boolean wantDiff = "diff".equals(blank(args.get("snap")));
            boolean wantAuto = "auto".equals(blank(args.get("snap")));
            DesktopTree.Session opened = openDesktop(target, args.get("fixture"));
            String beforeVersion = "desktop:" + opened.sequence();
            List<SnapRow> rowsBefore = wantDiff ? desktopRows(opened) : List.of();
            List<String> autoBefore = wantAuto ? compactDesktop(opened, runId) : List.of();
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
            String afterVersion = "desktop:" + (session == null ? opened.sequence() : session.sequence());
            List<SnapRow> rowsAfter = wantDiff ? desktopRows(session == null ? opened : session) : List.of();
            DesktopTree.Session snapped = session == null ? opened : session;
            if (!met) {
                Outcome failed = fail("wait", "timeout");
                putSnapDiff(failed.body(), args, "", "", beforeVersion, afterVersion, rowsBefore, rowsAfter);
                putAutoSnap(failed.body(), args, project, runId, autoBefore, () -> compactDesktop(snapped, runId));
                return failed;
            }
            Map<String, Object> body = base("wait", "desktop", "desktop", target);
            body.put("applied", session.live());
            body.put("mode", mode);
            body.put("condition", condition);
            body.put("docVersion", afterVersion);
            putSnapDiff(body, args, "", "", beforeVersion, afterVersion, rowsBefore, rowsAfter);
            putAutoSnap(body, args, project, runId, autoBefore, () -> compactDesktop(snapped, runId));
            return ok(body);
        }
        Held page = openBrowser(resolved(target, args));
        try {
            String before = docVersion(page.session.connection());
            String titleBefore = title(page.session.connection());
            boolean wantDiff = "diff".equals(blank(args.get("snap")));
            boolean wantAuto = "auto".equals(blank(args.get("snap")));
            List<SnapRow> rowsBefore = wantDiff
                    ? elementRows(page.session.connection(), page.session.id, before) : List.of();
            List<String> autoBefore = wantAuto ? compactBrowser(page.session, runId, target) : List.of();
            Map<String, String> flags = scopedFrame(runId, target);
            String session = enteredSession(page.session.connection(), runId);
            String ref = blank(args.get("ref"));
            if (ref.isBlank() && "ref".equals(target.kind())) ref = target.raw();
            boolean met;
            String newControl = null;
            if ("new-control".equals(condition)) {
                newControl = waitNewControl(page.session.connection(), timeout);
                met = newControl != null;
            } else if (!ref.isBlank()) {
                met = waitOnRef(page.session.connection(), requireHit(ref), condition, timeout);
            } else if ("event".equals(mode)) {
                met = waitEvent(project, runId, page.session.connection(), condition, timeout, session);
            } else {
                met = waitPoll(page.session.connection(), condition, blank(args.get("query")), timeout, flags, session);
            }
            if (!met) {
                Outcome failed = fail("wait", "timeout");
                String after = docVersion(page.session.connection());
                List<SnapRow> rowsAfter = wantDiff
                        ? elementRows(page.session.connection(), page.session.id, after) : List.of();
                putSnapDiff(failed.body(), args, titleBefore, title(page.session.connection()), before, after, rowsBefore, rowsAfter);
                putAutoSnap(failed.body(), args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
                stamp(failed.body(), page.session.connection(), null);
                putEntered(failed.body(), runId, page.session.connection());
                return failed;
            }
            String after = docVersion(page.session.connection());
            String titleAfter = title(page.session.connection());
            Map<String, Object> body = base("wait", "cdp", "browser", target);
            body.put("mode", mode);
            body.put("condition", condition);
            if (newControl != null) body.put("control", newControl);
            body.put("docVersion", after);
            body.put("title", titleAfter);
            List<SnapRow> rowsAfter = wantDiff
                    ? elementRows(page.session.connection(), page.session.id, after) : List.of();
            putSnapDiff(body, args, titleBefore, titleAfter, before, after, rowsBefore, rowsAfter);
            putAutoSnap(body, args, project, runId, autoBefore, () -> compactBrowser(page.session, runId, target));
            stamp(body, page.session.connection(), null);
            putEntered(body, runId, page.session.connection());
            return ok(body);
        } finally {
            page.release();
        }
    }

    /**
     * {@code wait --condition=new-control}: the first control (role and name, page or pkb surface) that was not
     * there when the wait began. Null on timeout.
     */
    private static String waitNewControl(CdpConnection cdp, long timeout) {
        java.util.Set<String> before = controlKeys(cdp);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        while (System.nanoTime() < deadline) {
            sleep(100);
            java.util.Set<String> now;
            try {
                now = controlKeys(cdp);
            } catch (RuntimeException failure) {
                continue;
            }
            for (String key : now) if (!before.contains(key)) return key;
        }
        return null;
    }

    private static java.util.Set<String> controlKeys(CdpConnection cdp) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (DomNode node : interactive(tree(cdp), Integer.MAX_VALUE)) {
            String base = role(node) + " \"" + name(node) + "\"";
            int n = counts.merge(base, 1, Integer::sum);
            keys.add(n == 1 ? base : base + " #" + n);
        }
        return keys;
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
            String until = blank(args.get("until"));
            String ref = blank(args.get("ref"));
            if (!until.isBlank()) {
                String before = docVersion(page.session.connection());
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(duration, 1L));
                boolean met = false;
                while (System.nanoTime() < deadline) {
                    if (untilMet(page.session, until, ref, before)) {
                        met = true;
                        break;
                    }
                    sleep(40);
                }
                if (!met) {
                    Outcome failed = fail("watch", "timeout");
                    failed.body().put("until", until);
                    if (!ref.isBlank()) failed.body().put("ref", ref);
                    stamp(failed.body(), page.session.connection(), null);
                    putEntered(failed.body(), runId, page.session.connection());
                    return failed;
                }
                Map<String, Object> body = base("watch", "cdp", "browser", target);
                body.put("mode", "until");
                body.put("until", until);
                body.put("met", true);
                if (!ref.isBlank()) body.put("ref", ref);
                body.put("docVersion", docVersion(page.session.connection()));
                stamp(body, page.session.connection(), null);
                putEntered(body, runId, page.session.connection());
                return ok(body);
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
            putEntered(body, runId, page.session.connection());
            return ok(body);
        } finally {
            page.release();
        }
    }

    /** Same snapshot as the ambiguous find. Index is 1-based. A name matches the accessible name or id. */
    static DomNode pickHit(List<DomNode> hits, String pick) {
        if (hits == null || hits.isEmpty() || pick == null || pick.isBlank()) return null;
        String token = pick.trim();
        if (token.matches("\\d+")) {
            int index = Integer.parseInt(token);
            if (index < 1 || index > hits.size()) return null;
            return hits.get(index - 1);
        }
        for (DomNode node : hits) {
            String id = node.attributes.get("id");
            if (id != null && id.equalsIgnoreCase(token)) return node;
            if (name(node).equalsIgnoreCase(token)) return node;
            String accessible = node.accessibleName == null ? "" : node.accessibleName;
            if (!accessible.isBlank() && accessible.equalsIgnoreCase(token)) return node;
        }
        String roleToken = token;
        if (roleToken.regionMatches(true, 0, "role=", 0, 5)) roleToken = roleToken.substring(5).trim();
        else if (roleToken.regionMatches(true, 0, "role:", 0, 5)) roleToken = roleToken.substring(5).trim();
        if (!roleToken.isBlank() && !roleToken.equals(token)) {
            DomNode matched = null;
            for (DomNode node : hits) {
                if (!role(node).equalsIgnoreCase(roleToken)) continue;
                if (matched != null) return null;
                matched = node;
            }
            if (matched != null) return matched;
        }
        DomNode only = null;
        int count = 0;
        for (DomNode node : hits) {
            if (!role(node).equalsIgnoreCase(token)) continue;
            only = node;
            count++;
        }
        if (count == 1) return only;
        return null;
    }

    private static void putReadBack(Map<String, Object> body, DomNode node, boolean settled) {
        boolean visible = node != null && node.displayed && !node.states.contains("hidden");
        boolean enabled = node != null && !node.states.contains("disabled") && !node.attributes.containsKey("disabled");
        Object reason = body.get("reason");
        boolean covered = reason != null && String.valueOf(reason).toLowerCase(Locale.ROOT).contains("cover");
        boolean shown = body.get("visible") instanceof Boolean flag ? flag : sameVisibility(node, covered);
        Map<String, Object> checked = new LinkedHashMap<>();
        checked.put("visible", shown);
        checked.put("enabled", enabled);
        checked.put("covered", covered);
        checked.put("settled", settled);
        body.put("checked", checked);
        body.put("settleMs", CLICK_SETTLE_MS);
    }

    private static boolean untilMet(BrowserSession session, String until, String ref, String before) {
        String token = until == null ? "" : until.trim();
        String lower = token.toLowerCase(Locale.ROOT);
        CdpConnection cdp = session.connection();
        if ("mutation".equals(lower) || "change".equals(lower)) {
            return !before.equals(docVersion(cdp));
        }
        if (lower.startsWith("text=")) {
            String needle = token.substring("text=".length());
            String text = evalText(cdp, "document.body?document.body.innerText:''");
            return text != null && text.contains(needle);
        }
        if ("visible".equals(lower) || "hidden".equals(lower)) {
            String selector = selectorOfRef(ref);
            String expression = selector.isBlank()
                    ? "!!document.body"
                    : "(function(){var el=document.querySelector(" + jsString(selector) + ");"
                    + "if(!el)return false;var r=el.getBoundingClientRect();var s=getComputedStyle(el);"
                    + "return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';})()";
            boolean visible = "true".equals(evalText(cdp, expression));
            return "visible".equals(lower) ? visible : !visible;
        }
        if ("click".equals(lower)) {
            List<ObjectNode> events = session.events().read(
                    new SessionEvents.Filter("", "", 0L, null, 40), 1L);
            for (ObjectNode event : events) {
                String kind = event.path("kind").asText(event.path("method").asText("")).toLowerCase(Locale.ROOT);
                if (kind.contains("click")) return true;
            }
            return false;
        }
        return false;
    }

    private static String selectorOfRef(String ref) {
        if (ref == null || ref.isBlank()) return "";
        try {
            Hit hit = requireHit(ref);
            if (hit == null || hit.selector == null) return "";
            return hit.selector;
        } catch (Miss missing) {
            return "";
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
            DomNode root = tree(page.session);
            DomNode node;
            if (!ref.isBlank()) {
                Hit hit = requireHit(ref);
                node = freshNode(hit, page.session.connection(), root);
                if (node == null) {
                    Outcome missed = staleRef("explain");
                    stamp(missed.body(), page.session.connection(), null);
                    putEntered(missed.body(), runId, page.session.connection());
                    return missed;
                }
            } else {
                List<DomNode> hits = locate(root, blank(args.get("query")), scopedFrame(runId, target));
                node = hits.isEmpty() ? null : hits.get(0);
            }
            if (node == null) {
                Outcome missed = fail("explain", "not found");
                stamp(missed.body(), page.session.connection(), null);
                putEntered(missed.body(), runId, page.session.connection());
                return missed;
            }
            Map<String, Object> body = base("explain", "cdp", "browser", target);
            body.put("report", report(node, page.session.connection()));
            body.put("docVersion", docVersion(page.session.connection()));
            stamp(body, page.session.connection(), node);
            putEntered(body, runId, page.session.connection());
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
            putEntered(body, runId, page.session.connection());
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
            String session = enteredSession(page.session.connection(), runId);
            if ("pkb".equals(world)) {
                int context = session.isBlank()
                        ? pkbContext(page.session.connection())
                        : pkbContext(page.session.connection(), session, runId);
                if (context <= 0) {
                    Outcome failed = fail("eval", "eval failed");
                    stamp(failed.body(), page.session.connection(), null);
                    if (!session.isBlank()) putEntered(failed.body(), runId, page.session.connection());
                    return failed;
                }
                params.put("contextId", context);
            }
            JsonNode value = session.isBlank()
                    ? page.session.connection().call("Runtime.evaluate", params, Duration.ofSeconds(10))
                    : page.session.connection().call("Runtime.evaluate", params, session, Duration.ofSeconds(10));
            if (value.has("exceptionDetails")) {
                Outcome failed = fail("eval", "eval failed");
                stamp(failed.body(), page.session.connection(), null);
                if (!session.isBlank()) putEntered(failed.body(), runId, page.session.connection());
                return failed;
            }
            Map<String, Object> body = base("eval", "cdp", "browser", target);
            body.put("world", world);
            body.put("value", jsonText(value.path("result").path("value")));
            stamp(body, page.session.connection(), null);
            if (!session.isBlank()) putEntered(body, runId, page.session.connection());
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
        DomNode control = "select".equals(kind) ? selectOf(node) : node;
        boolean inputOff = cdpInputOff();
        if (control == null) return Effect.refused(inputOff ? "js" : "cdp", "detached");
        if (disabled(control)) {
            if (inputOff) FallbackLog.note(project, runId, "act", "cdp", "js", "cdp input disabled");
            return Effect.refused(inputOff ? "js" : "cdp", inert(control) ? "inert" : "disabled");
        }
        String framed = cdpSessionOf(node);
        if ("click".equals(kind)) {
            String coveredBy = coverOf(session.connection(), framed, acted);
            if (!coveredBy.isBlank()) return Effect.covered(inputOff ? "js" : "cdp", coveredBy);
        }
        if (!framed.isBlank()) return actInFrame(session.connection(), framed, kind, value, node);
        if ("click".equals(kind) && insideFrame(node) && !closedShadow(acted)) {
            return clickInPlace(session.connection(), node);
        }
        if ("hover".equals(kind) && hoverInPlaceFrame(node)) {
            if (!hoverReachable(visibilityProbe(session.connection(), "", node), node)) return new Effect("cdp", false);
            return hoverInPlace(session.connection(), node);
        }
        if (inputOff) PageActions.useCdpInput(false);
        try {
            if (inputOff) {
                FallbackLog.note(project, runId, "act", "cdp", "js", "cdp input disabled");
                return jsAct(session.connection(), kind, query, selector, value, node);
            }
            if ("click".equals(kind) && closedShadow(acted)) return clickClosed(session.connection(), acted);
            if ("hover".equals(kind) && !hoverReachable(visibilityProbe(session.connection(), "", node), node)) {
                return new Effect("cdp", false);
            }
            String before = pageMark(session.connection());
            String signature = "click".equals(kind) ? nodeSignature(session.connection(), "", node) : "";
            String probe = "click".equals(kind) ? collabProbe(session.connection(), "", acted, true) : "";
            String keySignature = "press".equals(kind) ? nodeSignature(session.connection(), "", node) : "";
            LiveXPath.use(session.connection());
            try {
                PageActions.act(session, verb, flags);
            } finally {
                LiveXPath.use(null);
            }
            if ("click".equals(kind) && probeMoved(probe, collabProbe(session.connection(), "", acted, false))) {
                return Effect.changed("cdp");
            }
            if ("select".equals(kind)) return new Effect("cdp", valueMatches(value, controlValue(session.connection(), selectOf(acted))));
            if ("type".equals(kind)) return new Effect("cdp", valueMatches(value, controlValue(session.connection(), node)));
            if ("click".equals(kind)) {
                Effect read = clickReadBack(signature, nodeSignature(session.connection(), "", node), clickSettled(session.connection(), "", before, "cdp"));
                if (read != null && read.quiet()) {
                    String after = collabProbe(session.connection(), "", acted, false);
                    if (probeMoved(probe, after)) return Effect.changed("cdp");
                    if (probe == null || probe.isBlank()) return Effect.unknown("cdp");
                }
                return read;
            }
            if ("hover".equals(kind)) return Effect.changed("cdp");
            if (!before.equals(pageMark(session.connection()))) return Effect.changed("cdp");
            if ("press".equals(kind) && pressMoved(keySignature, nodeSignature(session.connection(), "", node),
                    collabProbe(session.connection(), "", node, false))) {
                return Effect.changed("cdp");
            }
            return new Effect("cdp", false);
        } catch (CdpException failure) {
            String message = failure.getMessage() == null ? "" : failure.getMessage();
            if (!inputOff && !message.contains("cdp input") && !message.contains("Input.")) throw failure;
            FallbackLog.note(project, runId, "act", "cdp", "js", message.isBlank() ? "cdp input disabled" : message);
            return jsAct(session.connection(), kind, query, selector, value, node);
        } finally {
            PageActions.useCdpInput(true);
        }
    }

    /** One click on a same-origin frame node. Coordinates from that document miss the parent viewport. */
    private static Effect clickInPlace(CdpConnection cdp, DomNode node) {
        String before = pageMark(cdp);
        String signature = nodeSignature(cdp, "", node);
        if (callOnNode(cdp, "", node, "function(){ this.click(); return true; }") == null) {
            return Effect.refused("cdp", "detached");
        }
        return clickReadBack(signature, nodeSignature(cdp, "", node), clickSettled(cdp, "", before, "cdp"));
    }

    /** One hover on a same-origin frame node. Parent-viewport coordinates miss that document. */
    private static Effect hoverInPlace(CdpConnection cdp, DomNode node) {
        String script = "function(){ this.dispatchEvent(new MouseEvent('mouseover',{bubbles:true}));"
                + " this.dispatchEvent(new MouseEvent('mouseenter',{bubbles:true}));"
                + " this.dispatchEvent(new MouseEvent('mousemove',{bubbles:true})); return true; }";
        if (callOnNode(cdp, "", node, script) == null) return new Effect("cdp", false);
        return Effect.changed("cdp");
    }

    /** Click, type, or select on the frame session that owns the node. One call, then a read-back. */
    private static Effect actInFrame(CdpConnection cdp, String sessionId, String kind, String value, DomNode node) {
        if ("hover".equals(kind) && !hoverReachable(visibilityProbe(cdp, sessionId, node), node)) {
            return new Effect("cdp", false);
        }
        String before = pageMark(cdp, sessionId);
        String signature = "click".equals(kind) ? nodeSignature(cdp, sessionId, node) : "";
        String script = switch (kind) {
            case "type" -> "function(){ this.focus(); this.value=" + jsString(value)
                    + "; this.dispatchEvent(new Event('input',{bubbles:true})); this.dispatchEvent(new Event('change',{bubbles:true})); return String(this.value); }";
            case "select" -> "function(){ var box=this.tagName==='OPTION'?(this.closest('select')||this):this; if(!box||box.tagName!=='SELECT') return '';"
                    + " var value=" + jsString(value) + "; var matched=false; for(var j=0;j<box.options.length;j++){ var o=box.options[j];"
                    + " var label=(o.label||o.text||'').replace(/\\s+/g,' ').trim(); if(o.value===value||label===value){ o.selected=true; matched=true; break; } }"
                    + " if(!matched) return ''; box.dispatchEvent(new Event('input',{bubbles:true})); box.dispatchEvent(new Event('change',{bubbles:true}));"
                    + " var chosen=box.options[box.selectedIndex]; return chosen?String(chosen.value||chosen.text||'').trim():''; }";
            case "hover" -> "function(){ this.dispatchEvent(new MouseEvent('mouseover',{bubbles:true}));"
                    + " this.dispatchEvent(new MouseEvent('mouseenter',{bubbles:true}));"
                    + " this.dispatchEvent(new MouseEvent('mousemove',{bubbles:true})); return true; }";
            case "click", "press" -> "function(){ this.click(); return true; }";
            default -> "";
        };
        if (script.isBlank()) return new Effect("cdp", false);
        String read = callOnNode(cdp, sessionId, node, script);
        if (read == null) return "click".equals(kind) ? Effect.refused("cdp", "detached") : new Effect("cdp", false);
        if ("type".equals(kind) || "select".equals(kind)) return new Effect("cdp", valueMatches(value, read));
        if ("hover".equals(kind)) return Effect.changed("cdp");
        if ("click".equals(kind)) {
            return clickReadBack(signature, nodeSignature(cdp, sessionId, node), clickSettled(cdp, sessionId, before, "cdp"));
        }
        return !before.equals(pageMark(cdp, sessionId)) ? Effect.changed("cdp") : new Effect("cdp", false);
    }

    /** {@code null} when the node cannot be called. The result is the returned string. */
    private static String callOnNode(CdpConnection cdp, String sessionId, DomNode node, String function) {
        if (cdp == null || node == null || node.backendNodeId <= 0 || function == null || function.isBlank()) return null;
        try {
            ObjectNode resolve = Jsons.object();
            resolve.put("backendNodeId", node.backendNodeId);
            JsonNode resolved = sessionId == null || sessionId.isBlank()
                    ? cdp.call("DOM.resolveNode", resolve, Duration.ofSeconds(5))
                    : cdp.call("DOM.resolveNode", resolve, sessionId, Duration.ofSeconds(5));
            String objectId = resolved.path("object").path("objectId").asText("");
            if (objectId.isBlank()) return null;
            ObjectNode call = Jsons.object();
            call.put("objectId", objectId);
            call.put("functionDeclaration", function);
            call.put("returnByValue", true);
            JsonNode called = sessionId == null || sessionId.isBlank()
                    ? cdp.call("Runtime.callFunctionOn", call, Duration.ofSeconds(5))
                    : cdp.call("Runtime.callFunctionOn", call, sessionId, Duration.ofSeconds(5));
            if (called.has("exceptionDetails")) return null;
            return jsonText(called.path("result").path("value"));
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String cdpSessionOf(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if (cursor.cdpSession != null && !cursor.cdpSession.isBlank()) return cursor.cdpSession;
        }
        return "";
    }

    private static boolean insideFrame(DomNode node) {
        for (DomNode cursor = node == null ? null : node.parent; cursor != null; cursor = cursor.parent) {
            if (cursor.iframe) return true;
        }
        return false;
    }

    /**
     * Role and name of an element painted over the target's centre, or blank when the click would reach it.
     * Closed-shadow hosts are not covers: {@code elementFromPoint} returns the host, and the walk crosses it.
     */
    private static String coverOf(CdpConnection cdp, String sessionId, DomNode node) {
        String raw = callOnNode(cdp, sessionId, node, ""
                + "function(){"
                + "function chain(outer,node){var cursor=node,hops=0;"
                + "while(cursor&&hops++<30){if(cursor===outer)return true;"
                + "var root=cursor.getRootNode?cursor.getRootNode():null;"
                + "if(root&&root.host)cursor=root.host;else cursor=cursor.parentElement;}"
                + "return false;}"
                + "try{var el=this;if(!el||!el.getBoundingClientRect)return '';"
                + "var r=el.getBoundingClientRect();if(r.width<1||r.height<1)return '';"
                + "var x=r.left+r.width/2,y=r.top+r.height/2;"
                + "var doc=el.ownerDocument||document;var top=null;"
                + "if(doc.elementsFromPoint){var stack=doc.elementsFromPoint(x,y);top=stack&&stack.length?stack[0]:null;}"
                + "else if(doc.elementFromPoint)top=doc.elementFromPoint(x,y);"
                + "var guard=0;while(top&&top.shadowRoot&&guard++<8){"
                + "var inner=top.shadowRoot.elementFromPoint(x,y);if(!inner||inner===top)break;top=inner;}"
                + "if(!top||chain(el,top)||chain(top,el))return '';"
                + "var role=(top.getAttribute&&top.getAttribute('role'))||(top.tagName?top.tagName.toLowerCase():'element');"
                + "var name=top.getAttribute? (top.getAttribute('aria-label')||''):'';"
                + "name=String(name).replace(/\\s+/g,' ').trim();"
                + "if(name.length>80)name=name.slice(0,80);"
                + "if(name)return role+' \"'+name+'\"';"
                + "if(top.id)return (top.tagName?top.tagName.toLowerCase():'element')+'#'+top.id;"
                + "return role;}catch(e){return '';}}");
        return raw == null ? "" : raw.trim();
    }

    /** A click was sent once. Wait for a visible change, and do not send it again. */
    private static Effect clickSettled(CdpConnection cdp, String sessionId, String before, String used) {
        return settledChange(cdp, sessionId, before) ? Effect.changed(used) : Effect.quiet(used);
    }

    /**
     * True when the title or the mutation count moves within {@link #CLICK_SETTLE_MS}.
     * The main frame waits on the page watcher. A frame session polls its own mark.
     */
    private static boolean settledChange(CdpConnection cdp, String sessionId, String before) {
        if (!before.equals(pageMark(cdp, sessionId))) return true;
        if (sessionId == null || sessionId.isBlank()) {
            String watched = evaluateWatch(cdp, "", CLICK_SETTLE_MS);
            if (watched.contains("\"ok\":true")) return true;
            return !before.equals(pageMark(cdp, sessionId));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLICK_SETTLE_MS);
        while (System.nanoTime() < deadline) {
            sleep(40);
            if (!before.equals(pageMark(cdp, sessionId))) return true;
        }
        return false;
    }

    private static String pageMark(CdpConnection cdp) {
        return pageMark(cdp, "");
    }

    private static String pageMark(CdpConnection cdp, String sessionId) {
        return evalText(cdp, sessionId, "document.title") + "\n" + docVersion(cdp, sessionId);
    }

    private static String docVersion(CdpConnection cdp, String sessionId) {
        String text = evalText(cdp, sessionId, docVersionExpression(), pkbWorld(cdp, sessionId));
        return text.isBlank() ? "0:0" : text;
    }

    /**
     * Top document plus same-origin iframe documents. Child sessions are folded in by {@link #docVersion(CdpConnection)}.
     * A replaced iframe document and a changed selected tab change the version even when that document's counter starts at zero.
     */
    static String docVersionExpression() {
        return """
                (function(){var root=document.documentElement;if(!root)return '0:0';
                var g=globalThis;
                function ignore(record){var t=record.target;
                if(record.type==='attributes'&&record.attributeName&&record.attributeName.indexOf('data-pkb-')===0)return true;
                if(t&&t.nodeType===1&&t.closest&&t.closest('[data-pkb-agent],[data-pkb-layer],[data-pkb-record],[data-pkb-meta="true"]'))return true;
                return false;}
                function arm(doc){if(!doc||!doc.documentElement)return 0;if(doc.__pkbFrameWatch)return doc.__pkbFrameCount||0;
                doc.__pkbFrameCount=0;doc.__pkbFrameWatch=new MutationObserver(function(records){
                for(var i=0;i<records.length;i++){if(ignore(records[i]))continue;doc.__pkbFrameCount++;return;}});
                try{doc.__pkbFrameWatch.observe(doc.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});}catch(e){}
                return 0;}
                function noteFrame(frame){var doc=null;try{doc=frame.contentDocument;}catch(e){doc=null;}
                var mark='';
                if(doc){if(!doc.__pkbDocId){g.__pkbDocSeq=(g.__pkbDocSeq||0)+1;try{doc.__pkbDocId=g.__pkbDocSeq;}catch(e2){}}
                var href='';try{href=doc.location?doc.location.href:'';}catch(e3){}
                mark=String(doc.__pkbDocId||'')+'@'+href;}else{mark='x:'+(frame.src||'');}
                var seen=frame.__pkbSeenDoc||'';if(seen&&seen!==mark)g.__pkbDocCount++;frame.__pkbSeenDoc=mark;return mark;}
                function selected(doc){var out='';try{var nodes=doc.querySelectorAll('[aria-selected="true"]');
                for(var i=0;i<nodes.length&&i<8;i++){var n=nodes[i];out+=(n.id||n.getAttribute('aria-controls')||'')+',';}}catch(e){}
                return out;}
                function sum(doc){if(!doc||!doc.documentElement)return {n:0,mark:'',sel:''};var n=arm(doc);var mark='';var sel=selected(doc);
                var frames=doc.querySelectorAll?doc.querySelectorAll('iframe,frame'):[];
                for(var i=0;i<frames.length;i++){var frame=frames[i];mark+='|'+noteFrame(frame);
                try{var child=frame.contentDocument;if(child&&child!==doc){var sub=sum(child);n+=sub.n;mark+=sub.mark;sel+=sub.sel;}}catch(e){}}
                return {n:n,mark:mark,sel:sel};}
                if(!g.__pkbDocWatch){g.__pkbDocCount=0;g.__pkbDocWatch=new MutationObserver(function(records){
                for(var i=0;i<records.length;i++){if(ignore(records[i]))continue;g.__pkbDocCount++;return;}});
                g.__pkbDocWatch.observe(root,{subtree:true,childList:true,attributes:true,characterData:true});}
                if(!g.__pkbFrameLoad){g.__pkbFrameLoad=true;document.addEventListener('load',function(ev){
                var t=ev.target;if(!t||!t.tagName)return;var tag=t.tagName;if(tag==='IFRAME'||tag==='FRAME')g.__pkbDocCount++;},true);}
                var rolled=sum(document);
                return location.href+':'+g.__pkbDocCount+':'+rolled.n+':'+rolled.sel+':'+rolled.mark;})()
                """;
    }

    private static String docVersion(CdpConnection cdp) {
        StringBuilder text = new StringBuilder(docVersion(cdp, ""));
        try {
            for (String frameId : FrameCoverage.frameIds(cdp)) {
                String session = FrameCoverage.sessionId(cdp, frameId);
                if (session == null || session.isBlank()) continue;
                text.append('|').append(docVersion(cdp, session));
            }
        } catch (RuntimeException ignored) {
            // A missing child session leaves the main document version.
        }
        return text.toString();
    }

    /** Isolated world for this session's document. The page's main world is not written. */
    private static int pkbWorld(CdpConnection cdp, String sessionId) {
        String frame;
        try {
            if (sessionId == null || sessionId.isBlank()) {
                frame = mainFrameId(cdp);
            } else {
                JsonNode tree = cdp.call("Page.getFrameTree", Jsons.object(), sessionId, Duration.ofSeconds(5));
                frame = tree.path("frameTree").path("frame").path("id").asText("");
            }
        } catch (RuntimeException failure) {
            return 0;
        }
        if (frame.isBlank()) return 0;
        ObjectNode created = Jsons.object();
        created.put("frameId", frame);
        created.put("worldName", "pkb-surface");
        created.put("grantUniveralAccess", true);
        try {
            JsonNode worldNode = sessionId == null || sessionId.isBlank()
                    ? cdp.call("Page.createIsolatedWorld", created, Duration.ofSeconds(5))
                    : cdp.call("Page.createIsolatedWorld", created, sessionId, Duration.ofSeconds(5));
            return worldNode.path("executionContextId").asInt(0);
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    private static String evalText(CdpConnection cdp, String sessionId, String expression) {
        return evalText(cdp, sessionId, expression, 0);
    }

    private static String evalText(CdpConnection cdp, String sessionId, String expression, int contextId) {
        ObjectNode params = Jsons.object();
        params.put("returnByValue", true);
        params.put("expression", expression);
        if (contextId > 0) params.put("contextId", contextId);
        JsonNode value = sessionId == null || sessionId.isBlank()
                ? cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5))
                : cdp.call("Runtime.evaluate", params, sessionId, Duration.ofSeconds(5));
        return value.path("result").path("value").asText("");
    }

    /** JS runs on the session that was targeted, including a {@code browser:cdp=} page. */
    private static Effect jsAct(CdpConnection cdp, String kind, String query, String selector, String value, DomNode node) {
        String before = pageMark(cdp);
        String signature = "click".equals(kind) ? nodeSignature(cdp, "", node) : "";
        String script = ""
                + "(async function(){"
                + "var kind=" + jsString(kind) + ",q=" + jsString(query) + ",sel=" + jsString(selector) + ",value=" + jsString(value) + ";"
                + "function text(el){return ((el.innerText||el.value||el.getAttribute('aria-label')||el.getAttribute('placeholder')||el.id||'')+'').replace(/\\s+/g,' ').trim();}"
                + "function settle(){ return new Promise(function(r){ setTimeout(r, 0); }); }"
                + "var el=null;if(sel){try{el=document.querySelector(sel);}catch(e){el=null;}}"
                + "if(!el){var all=document.querySelectorAll('button,a,input,select,textarea,option,label,[role=button]');"
                + "for(var i=0;i<all.length;i++){if(text(all[i])===q||all[i].id===q){el=all[i];break;}}}"
                + "if(!el) return {ok:false};"
                + "if(el.tagName==='LABEL'&&el.htmlFor){var linked=document.getElementById(el.htmlFor); if(linked) el=linked;}"
                + "if(kind==='type'){el.focus(); el.value=value; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true})); await settle(); return {ok:true,value:String(el.value)};}"
                + "if(kind==='select'){var box=el.tagName==='OPTION'?(el.closest('select')||el):el; if(!box||box.tagName!=='SELECT') return {ok:false};"
                + "var matched=false; for(var j=0;j<box.options.length;j++){var o=box.options[j]; var label=(o.label||o.text||'').replace(/\\s+/g,' ').trim();"
                + "if(o.value===value||label===value){o.selected=true; matched=true; break;}} if(!matched) return {ok:false};"
                + "box.dispatchEvent(new Event('input',{bubbles:true})); box.dispatchEvent(new Event('change',{bubbles:true}));"
                + "await settle();"
                + "var chosen=box.options[box.selectedIndex]; var shown=chosen?((chosen.value||chosen.text||'')+'').replace(/\\s+/g,' ').trim():'';"
                + "return {ok:true,value:shown};}"
                + "if(kind==='hover'){ var style=getComputedStyle(el); var box=el.getBoundingClientRect();"
                + " if(style.display==='none'||style.visibility==='hidden'||box.width<1||box.height<1) return {ok:false};"
                + " el.dispatchEvent(new MouseEvent('mouseover',{bubbles:true}));"
                + " el.dispatchEvent(new MouseEvent('mouseenter',{bubbles:true}));"
                + " el.dispatchEvent(new MouseEvent('mousemove',{bubbles:true})); return {ok:true}; }"
                + "el.click(); return {ok:true};"
                + "})()";
        ObjectNode params = Jsons.object();
        params.put("expression", script);
        params.put("awaitPromise", true);
        params.put("returnByValue", true);
        JsonNode called = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(10));
        JsonNode payload = called.path("result").path("value");
        if (!payload.path("ok").asBoolean(false)) return new Effect("js", false);
        if ("type".equals(kind) || "select".equals(kind)) {
            String read = payload.path("value").asText("");
            if (!valueMatches(value, read) && node != null) read = controlValue(cdp, "select".equals(kind) ? selectOf(node) : node);
            return new Effect("js", valueMatches(value, read));
        }
        if ("click".equals(kind)) return clickReadBack(signature, nodeSignature(cdp, "", node), clickSettled(cdp, "", before, "js"));
        if ("hover".equals(kind)) return Effect.changed("js");
        return new Effect("js", !before.equals(pageMark(cdp)));
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

    /** A loading mask holds the click only when it covers that click. The check itself cannot run forever. */
    private static tools.dscode.common.domoperations.MaskQuiet.Wait awaitMaskQuiet(CdpConnection cdp, DomNode node, long timeout) {
        long start = System.nanoTime();
        if (!maskCovers(cdp, node)) return new tools.dscode.common.domoperations.MaskQuiet.Wait(0, false);
        long deadline = start + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        while (true) {
            if (System.nanoTime() >= deadline) {
                return new tools.dscode.common.domoperations.MaskQuiet.Wait(elapsedMs(start), false);
            }
            sleep(50);
            if (!maskCovers(cdp, node)) {
                return new tools.dscode.common.domoperations.MaskQuiet.Wait(Math.max(1, elapsedMs(start)), true);
            }
        }
    }

    private static boolean maskCovers(CdpConnection cdp, DomNode node) {
        String read = callOnNode(
                cdp,
                cdpSessionOf(node),
                node,
                "function(){" + tools.dscode.common.domoperations.MaskQuiet.COVERS + "}"
        );
        return "true".equals(read);
    }

    private static long elapsedMs(long start) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private static void putMask(Map<String, Object> body, tools.dscode.common.domoperations.MaskQuiet.Wait mask) {
        if (body == null || mask == null) return;
        body.put("waitedMs", mask.waitedMs());
        body.put("maskCleared", mask.maskCleared());
    }

    /** A covering mask that did not clear. A miss ({@code waitedMs == 0}) is not a cover. */
    static boolean maskStillCovering(tools.dscode.common.domoperations.MaskQuiet.Wait mask) {
        return mask != null && mask.waitedMs() > 0 && !mask.maskCleared();
    }

    /**
     * A quiet click is applied when the node's own text or state moved.
     * A blank read stays quiet, so a handler that only bumps a counter is not a visible change.
     */
    static boolean signatureApplied(String before, String after) {
        if (before == null || before.isBlank() || after == null || after.isBlank()) return false;
        return !before.equals(after);
    }

    /**
     * A collaboration probe on the clicked node: connected, rendered, the mutation count of the node's own
     * surface root (a shadow root or a pickleball surface), and where focus went. Arming installs one observer.
     * The page-wide watcher ignores pickleball surfaces, so a card or panel button that removes its card or
     * switches its pane is read here instead of reported as "no visible change".
     */
    static String collabProbe(CdpConnection cdp, String sessionId, DomNode node, boolean arm) {
        String raw = callOnNode(cdp, sessionId, node, ""
                + "function(){ var g=globalThis; var arm=" + arm + ";"
                + " var root=this.getRootNode?this.getRootNode():document;"
                + " var surface=(this.closest&&this.closest('[data-pkb-agent],[data-pkb-layer],[data-pkb-record],[data-pkb-meta=\"true\"],[data-pkb-purpose]'))||null;"
                + " var shadow=root&&root.nodeType===11;"
                + " if(arm){ var watch=shadow?root:surface; g.__pkbProbe={count:0,root:root,watched:!!watch};"
                + "  try{ if(g.__pkbProbeObs) g.__pkbProbeObs.disconnect(); }catch(e){}"
                + "  if(watch){ try{ g.__pkbProbeObs=new MutationObserver(function(r){ g.__pkbProbe.count+=r.length; });"
                + "   g.__pkbProbeObs.observe(watch,{subtree:true,childList:true,attributes:true,characterData:true}); }catch(e){} } }"
                + " var p=g.__pkbProbe||{count:0,root:root,watched:false};"
                + " var focusRoot=(p.root&&p.root.activeElement!==undefined)?p.root:document;"
                + " var ae=focusRoot.activeElement||document.activeElement;"
                + " var focus=!ae||ae===document.body?'':((ae===this||(this.contains&&this.contains(ae))||(ae.contains&&ae.contains(this)))?'self':(ae.tagName+'#'+(ae.id||'')+'.'+(ae.getAttribute&&ae.getAttribute('data-pkb-purpose')||'')));"
                + " var shown=(this.isConnected&&this.getClientRects&&this.getClientRects().length>0)?'1':'0';"
                + " return [this.isConnected?'1':'0', shown, p.watched?String(p.count):'-', focus].join('|'); }");
        return raw == null ? "" : raw;
    }

    /**
     * True when the probe proves the click did something: the node was removed or hidden, its surface mutated,
     * or focus moved to another control. A before read with no after read means the node is gone.
     */
    static boolean probeMoved(String before, String after) {
        if (before == null || before.isBlank()) return false;
        String[] b = before.split("\\|", -1);
        if (b.length < 4) return false;
        if (after == null || after.isBlank()) return "1".equals(b[0]);
        String[] a = after.split("\\|", -1);
        if (a.length < 4) return false;
        if ("1".equals(b[0]) && "0".equals(a[0])) return true;
        if ("1".equals(b[1]) && "0".equals(a[1])) return true;
        if (!"-".equals(b[2]) && !"-".equals(a[2]) && !b[2].equals(a[2])) return true;
        return !a[3].isBlank() && !"self".equals(a[3]) && !a[3].equals(b[3]);
    }

    /**
     * A key was sent to the node after it was focused. It landed when focus left the node (Tab, Escape),
     * or the node's value, selection, or state moved (Enter, arrows).
     */
    static boolean pressMoved(String signatureBefore, String signatureAfter, String probeAfter) {
        if (signatureApplied(signatureBefore, signatureAfter)) return true;
        if (probeAfter == null || probeAfter.isBlank()) return false;
        String[] a = probeAfter.split("\\|", -1);
        if (a.length < 4) return false;
        return !"self".equals(a[3]);
    }

    private static Effect clickReadBack(String before, String after, Effect settled) {
        if (settled == null || !settled.quiet()) return settled;
        if (!signatureApplied(before, after)) return settled;
        return Effect.changed(settled.used());
    }

    /** Text, selection, class, checked, and value. A counter that is not on the node is absent. */
    private static String nodeSignature(CdpConnection cdp, String sessionId, DomNode node) {
        if (cdp == null || node == null) return "";
        String raw = callOnNode(cdp, sessionId, node, ""
                + "function(){"
                + " function clip(v){ v=String(v==null?'':v).replace(/\\s+/g,' ').trim(); return v.length>120?v.slice(0,120):v; }"
                + " var text=clip(this.innerText||this.textContent||'');"
                + " var selected=this.getAttribute?(this.getAttribute('aria-selected')||''):'';"
                + " var checked=this.getAttribute?(this.getAttribute('aria-checked')||''):'';"
                + " var current=this.getAttribute?(this.getAttribute('aria-current')||''):'';"
                + " var cls=this.getAttribute?(this.getAttribute('class')||''):'';"
                + " var box=this.checked===true?'1':(this.checked===false?'0':'');"
                + " var value=('value' in this)?clip(this.value):'';"
                + " return [text, selected, checked, current, clip(cls), box, value].join('\\n');"
                + "}");
        return raw == null ? "" : raw;
    }

    /**
     * A probe of {@code 1} is visible. A blank or {@code 0} probe is not proof the node is hidden
     * when the tree already says it is displayed.
     */
    static boolean hoverReachable(String probe, DomNode node) {
        if ("1".equals(probe)) return true;
        if (node == null || !node.displayed || node.states.contains("hidden")) return false;
        return true;
    }

    /** Same-origin iframe hover. A child CDP session uses {@code actInFrame} instead. */
    static boolean hoverInPlaceFrame(DomNode node) {
        if (node == null || !insideFrame(node) || closedShadow(node)) return false;
        return cdpSessionOf(node).isBlank();
    }

    private static String visibilityProbe(CdpConnection cdp, String sessionId, DomNode node) {
        if (cdp == null || node == null) return null;
        return callOnNode(cdp, sessionId, node, ""
                + "function(){"
                + " var style=getComputedStyle(this);"
                + " if(!style||style.display==='none'||style.visibility==='hidden') return '0';"
                + " var box=this.getBoundingClientRect();"
                + " return (box.width>=1 && box.height>=1)?'1':'0';"
                + "}");
    }

    /** Polls one ref until that node matches {@code condition}, or the timeout ends. */
    private static boolean waitOnRef(CdpConnection cdp, Hit hit, String condition, long timeout) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        while (true) {
            if (refCondition(cdp, hit, condition)) return true;
            if (System.nanoTime() >= deadline) return false;
            sleep(40);
        }
    }

    /**
     * {@code visible}, {@code hidden}, {@code enabled}, and {@code detached} describe that node.
     * {@code text=} matches that node's own text. A missing node is only {@code detached}.
     */
    private static boolean refCondition(CdpConnection cdp, Hit hit, String condition) {
        if (hit == null || "desktop".equals(hit.kind)) return false;
        String kind = condition == null ? "" : condition.trim();
        DomNode node;
        try {
            node = findNode(tree(cdp), "", hit.backend);
        } catch (RuntimeException failure) {
            node = null;
        }
        if ("detached".equals(kind)) return node == null;
        if (node == null) return false;
        if ("visible".equals(kind) || "hidden".equals(kind)) {
            Displayed.prepare(cdp, List.of(node));
            boolean shown = Displayed.isDisplayed(node);
            return "visible".equals(kind) ? shown : !shown;
        }
        if ("enabled".equals(kind)) return !disabled(node);
        if (kind.startsWith("text=")) {
            String want = kind.substring("text=".length());
            String text = HtmlTree.textOf(node).trim();
            return want.equals(text) || want.equals(name(node));
        }
        return false;
    }

    private static boolean waitPoll(
            CdpConnection cdp,
            String condition,
            String query,
            long timeout,
            Map<String, String> flags,
            String sessionId
    ) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        while (true) {
            if (conditionMet(cdp, condition, query, flags, sessionId)) return true;
            if (System.nanoTime() >= deadline) return false;
            sleep(50);
        }
    }

    /** Text and title checks run in the entered frame when that frame was scoped. */
    private static boolean conditionMet(
            CdpConnection cdp,
            String condition,
            String query,
            Map<String, String> flags,
            String sessionId
    ) {
        if (condition.startsWith("title=")) {
            return condition.substring("title=".length()).equals(title(cdp, sessionId));
        }
        if (condition.startsWith("url=")) {
            String want = condition.substring("url=".length());
            String href = href(cdp, sessionId);
            return !want.isBlank() && (href.equals(want) || href.startsWith(want) || href.contains(want));
        }
        if (condition.startsWith("ref=") && condition.endsWith(":selected")) {
            String ref = condition.substring("ref=".length(), condition.length() - ":selected".length());
            return refSelected(cdp, ref, flags);
        }
        if (condition.startsWith("text=")) {
            String want = condition.substring("text=".length());
            if (sessionId != null && !sessionId.isBlank()) return exactVisibleText(cdp, sessionId, want);
            try {
                DomNode root = tree(cdp);
                if (flags != null && flags.get("frame") != null && !flags.get("frame").isBlank()) {
                    root = Frames.scope(root, flags);
                }
                return exactVisibleText(root, want);
            } catch (RuntimeException failure) {
                return false;
            }
        }
        String text = query;
        if (text == null || text.isBlank()) text = condition;
        if (text.isBlank() || EVENT_CONDITIONS.contains(text)) return !title(cdp, sessionId).isBlank();
        try {
            DomNode root = tree(cdp);
            if (flags != null && flags.get("frame") != null && !flags.get("frame").isBlank()) {
                root = Frames.scope(root, flags);
            }
            return !locate(root, text, Map.of()).isEmpty();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** Direct visible text in the entered frame. Script and style text and another document's name do not match. */
    private static boolean exactVisibleText(CdpConnection cdp, String sessionId, String want) {
        if (want == null || want.isEmpty()) return false;
        ObjectNode params = Jsons.object();
        params.put("returnByValue", true);
        params.put("expression", "(function(){var want=" + jsString(want) + ";"
                + "var skip={SCRIPT:1,STYLE:1,NOSCRIPT:1};"
                + "var all=document.getElementsByTagName('*');"
                + "for(var i=0;i<all.length;i++){var el=all[i];"
                + "if(skip[el.tagName])continue;"
                + "if(el.closest&&el.closest('script,style,noscript,head'))continue;"
                + "var own='';var c=el.firstChild;"
                + "while(c){if(c.nodeType===3)own+=c.nodeValue;c=c.nextSibling;}"
                + "if(own.replace(/\\s+/g,' ').trim()===want)return true;}"
                + "return false;})()");
        try {
            JsonNode value = sessionId == null || sessionId.isBlank()
                    ? cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5))
                    : cdp.call("Runtime.evaluate", params, sessionId, Duration.ofSeconds(5));
            return value.path("result").path("value").asBoolean(false);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static boolean exactVisibleText(DomNode node, String want) {
        if (node == null || want == null || want.isEmpty()) return false;
        if (node.nodeType == 1 && !node.iframe) {
            String tag = node.tag();
            if (!"script".equals(tag) && !"style".equals(tag) && !"noscript".equals(tag) && !"head".equals(tag)
                    && !inHead(node) && want.equals(directText(node))) {
                return true;
            }
        }
        for (DomNode child : node.children) {
            if (child.iframe) continue;
            if (exactVisibleText(child, want)) return true;
        }
        return false;
    }

    private static String directText(DomNode node) {
        StringBuilder builder = new StringBuilder();
        for (DomNode child : node.children) {
            if (child.nodeType == 3 && child.nodeValue != null) builder.append(child.nodeValue);
        }
        return builder.toString().replaceAll("\\s+", " ").trim();
    }

    /** A remembered ref, or a name, is selected when aria-selected, selected, or aria-current says so. */
    private static boolean refSelected(CdpConnection cdp, String ref, Map<String, String> flags) {
        if (ref == null || ref.isBlank()) return false;
        String key = ref.startsWith("ref:") ? ref : "ref:" + ref;
        Hit hit = HITS.get(key);
        try {
            DomNode root = tree(cdp);
            if (flags != null && flags.get("frame") != null && !flags.get("frame").isBlank()) {
                root = Frames.scope(root, flags);
            }
            List<DomNode> hits = new ArrayList<>();
            if (hit != null) {
                long backend = hit.backend();
                walk(root, node -> {
                    if (node.backendNodeId == backend) addHit(hits, node);
                });
                if (hits.isEmpty() && hit.query() != null && !hit.query().isBlank()) hits.addAll(locate(root, hit.query(), Map.of()));
                if (hits.isEmpty() && hit.name() != null && !hit.name().isBlank()) hits.addAll(named(root, hit.name()));
            } else {
                hits.addAll(named(root, ref));
            }
            for (DomNode node : hits) {
                if (selectedNode(node)) return true;
            }
            return false;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    static boolean selectedNode(DomNode node) {
        if (node == null) return false;
        if ("true".equalsIgnoreCase(attr(node, "aria-selected"))) return true;
        if (node.attributes.containsKey("selected")) return true;
        String current = attr(node, "aria-current");
        if ("page".equalsIgnoreCase(current) || "true".equalsIgnoreCase(current) || "step".equalsIgnoreCase(current)) return true;
        return node.liveState != null && node.liveState.path("selected").asBoolean(false);
    }

    private static boolean waitEvent(Path project, String runId, CdpConnection cdp, String condition, long timeout, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            String primary = evaluateWatch(cdp, condition, timeout);
            if (primary.startsWith("watchUntil:")) {
                return !primary.contains("\"ok\":false") && !primary.contains("timeout");
            }
            FallbackLog.note(project, runId, "wait", "watcher", "mutation-observer", "pkb watchUntil was not on the page");
        }
        ObjectNode params = Jsons.object();
        params.put("awaitPromise", true);
        params.put("returnByValue", true);
        params.put("expression", ""
                + "(async function(){return await new Promise(function(resolve){"
                + "var timer=setTimeout(function(){resolve('timeout');}," + timeout + ");"
                + "var obs=new MutationObserver(function(){clearTimeout(timer);obs.disconnect();resolve('mutation');});"
                + "function watch(doc){if(!doc||!doc.documentElement)return;"
                + "try{obs.observe(doc.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});}catch(e){}"
                + "var frames=doc.querySelectorAll?doc.querySelectorAll('iframe,frame'):[];"
                + "for(var i=0;i<frames.length;i++){try{var child=frames[i].contentDocument;if(child&&child!==doc)watch(child);}catch(e){}}}"
                + "watch(document);"
                + "});})()");
        Duration budget = Duration.ofMillis(Math.max(1000, timeout + 2000));
        JsonNode value = sessionId == null || sessionId.isBlank()
                ? cdp.call("Runtime.evaluate", params, budget)
                : cdp.call("Runtime.evaluate", params, sessionId, budget);
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
        return pkbContext(cdp, "", null);
    }

    /** Surface world of the entered frame when {@code sessionId} is that frame's CDP session. */
    private static int pkbContext(CdpConnection cdp, String sessionId, String runId) {
        String frame;
        if (sessionId == null || sessionId.isBlank()) {
            frame = mainFrameId(cdp);
        } else {
            String token = ENTERED.get(runId == null ? "" : runId);
            frame = token == null ? "" : resolveFrameId(cdp, token);
        }
        if (frame.isBlank()) return 0;
        ObjectNode created = Jsons.object();
        created.put("frameId", frame);
        created.put("worldName", "pkb-surface");
        created.put("grantUniveralAccess", true);
        JsonNode worldNode = sessionId == null || sessionId.isBlank()
                ? cdp.call("Page.createIsolatedWorld", created, Duration.ofSeconds(5))
                : cdp.call("Page.createIsolatedWorld", created, sessionId, Duration.ofSeconds(5));
        return worldNode.path("executionContextId").asInt(0);
    }

    public static String mainFrame(CdpConnection cdp) {
        try {
            return mainFrameId(cdp);
        } catch (RuntimeException failure) {
            return "";
        }
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

    /**
     * The node from {@code hit} when it is still that same control in the same document.
     * Unrelated mutations and pkb attribute writes do not drop it.
     */
    private static DomNode freshNode(Hit hit, CdpConnection cdp, DomNode root) {
        if (hit == null || "desktop".equals(hit.kind)) return null;
        if (documentMoved(hit, cdp)) return null;
        DomNode node = findNode(root, "", hit.backend);
        if (node == null) return null;
        if (!sameText(hit.role, role(node)) || !sameText(hit.name, name(node))) return null;
        return node;
    }

    /** True when this frame's URL or loader is no longer the one stored on the ref. */
    private static boolean documentMoved(Hit hit, CdpConnection cdp) {
        Map<String, String[]> frames = frameIndex(cdp);
        if (frames.isEmpty()) return true;
        String[] live = frames.get(hit.frameId);
        if (live == null) return true;
        return !sameText(hit.url, live[0]) || !sameText(hit.loader, live[1]);
    }

    private static Map<String, String[]> frameIndex(CdpConnection cdp) {
        Map<String, String[]> frames = new LinkedHashMap<>();
        try {
            JsonNode tree = cdp.call("Page.getFrameTree", Jsons.object(), Duration.ofSeconds(5));
            collectFrameIndex(tree.path("frameTree"), frames);
        } catch (RuntimeException ignored) {
            // Parent tree is optional. Covered out-of-process ids are added below.
        }
        // Chrome 158 drops an out-of-process child from the parent tree. frame --list
        // still prints that id from FrameCoverage. The index has to know it too, or a
        // hit is reported as the top frame.
        if (cdp != null) {
            for (String id : FrameCoverage.frameIds(cdp)) {
                if (id == null || id.isBlank() || frames.containsKey(id)) continue;
                frames.put(id, new String[]{FrameCoverage.url(cdp, id), ""});
            }
        }
        return frames;
    }

    private static void collectFrameIndex(JsonNode node, Map<String, String[]> frames) {
        if (node == null || node.isMissingNode() || node.isNull()) return;
        JsonNode frame = node.path("frame");
        String id = frame.path("id").asText("");
        if (!id.isBlank()) {
            frames.put(id, new String[]{frame.path("url").asText(""), frame.path("loaderId").asText("")});
        }
        JsonNode children = node.path("childFrames");
        if (children.isArray()) {
            for (JsonNode child : children) collectFrameIndex(child, frames);
        }
    }

    private static boolean sameText(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        return a.equals(b);
    }

    private static Outcome staleRef(String op) {
        Outcome missed = fail(op, "stale_ref");
        missed.body().put("fix", "run snap (or find) again for a fresh ref");
        return missed;
    }

    private static Hit requireHit(String ref) {
        String expanded = SessionIds.expand(ref);
        String key = expanded != null ? expanded : (ref != null && ref.startsWith("ref:") ? ref : "ref:" + (ref == null ? "" : ref));
        Hit hit = HITS.get(key);
        if (hit == null && ref != null) hit = HITS.get(ref);
        if (hit == null) throw new Miss("unknown target");
        return hit;
    }

    private static boolean desktopHit(String ref) {
        if (ref == null || ref.isBlank()) return false;
        try {
            Hit hit = requireHit(ref);
            return hit != null && "desktop".equals(hit.kind);
        } catch (Miss missing) {
            return false;
        }
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
        String pageId = tools.dscode.common.composite.CompositeRuntime.pageTargetId(driver);
        if (pageId == null || pageId.isBlank()) pageId = pageIdForUrl(cdp, currentUrl(driver));
        try {
            BrowserSession attached = BrowserSession.attach(
                    "worker" + Long.toUnsignedString(System.nanoTime()),
                    cdp,
                    false,
                    List.of(),
                    pageId == null || pageId.isBlank() ? null : pageId);
            return new Held(attached, true);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String currentUrl(org.openqa.selenium.WebDriver driver) {
        try {
            return driver.getCurrentUrl();
        } catch (RuntimeException failure) {
            return "";
        }
    }

    /** Page id whose {@code /json/list} url is the driver's current page. Blank when none match. */
    private static String pageIdForUrl(String cdp, String url) {
        if (url == null || url.isBlank() || cdp == null || cdp.isBlank()) return "";
        String http = cdp.startsWith("ws") ? "http" + cdp.substring(2) : cdp;
        int slash = http.indexOf("/devtools");
        if (slash > 0) http = http.substring(0, slash);
        try {
            HttpRequest list = HttpRequest.newBuilder(URI.create(http + "/json/list"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> body = HttpClient.newHttpClient().send(list, HttpResponse.BodyHandlers.ofString());
            JsonNode pages = Jsons.MAPPER.readTree(body.body());
            if (!pages.isArray()) return "";
            for (JsonNode page : pages) {
                if (!"page".equals(page.path("type").asText())) continue;
                if (url.equals(page.path("url").asText(""))) return page.path("id").asText("");
            }
        } catch (Exception failure) {
            return "";
        }
        return "";
    }

    private static boolean names(DomNode node, String label) {
        if (node == null || label == null || label.isBlank()) return false;
        String name = node.accessibleName == null ? "" : node.accessibleName;
        if (label.equals(name)) return true;
        for (DomNode child : node.children) if (names(child, label)) return true;
        return false;
    }

    private static final java.util.regex.Pattern PURPOSE = java.util.regex.Pattern.compile("(?:^|\\s)purpose=([A-Za-z0-9_-]+)");

    /** {@code --purpose=<token>} joins the query as {@code purpose=<token>}. */
    static String withPurpose(String query, String purpose) {
        String token = purpose == null ? "" : purpose.trim();
        if (token.isBlank()) return query == null ? "" : query;
        return ((query == null ? "" : query.trim()) + " purpose=" + token).trim();
    }

    /**
     * {@code purpose=<token>} selects controls whose {@code data-pkb-purpose} has that token. An exact accessible
     * name (case and spacing ignored) wins over substring hits: when one exists, the substrings are dropped.
     */
    private static List<DomNode> locate(DomNode root, String query, Map<String, String> extra) {
        String text = query == null ? "" : query;
        java.util.regex.Matcher purposeMatch = PURPOSE.matcher(text);
        String purpose = "";
        if (purposeMatch.find()) {
            purpose = purposeMatch.group(1);
            text = (text.substring(0, purposeMatch.start()) + " " + text.substring(purposeMatch.end())).trim();
        }
        if (!purpose.isBlank() && text.isBlank()) {
            if (root != null) root.linkParents();
            List<DomNode> tagged = new ArrayList<>();
            String want = purpose;
            walk(root, node -> { if (hasPurpose(node, want)) addHit(tagged, node); });
            return tagged;
        }
        List<DomNode> hits = locateText(root, text, extra);
        if (!purpose.isBlank()) {
            String want = purpose;
            hits = new ArrayList<>(hits.stream().filter(node -> hasPurpose(node, want)).toList());
        }
        if (!text.isBlank() && hits.size() > 1) {
            String wanted = text.replaceAll("\\s+", " ").trim();
            List<DomNode> exact = new ArrayList<>();
            for (DomNode node : hits) {
                String shown = name(node).replaceAll("\\s+", " ").trim();
                if (shown.equalsIgnoreCase(wanted)) exact.add(node);
            }
            if (!exact.isEmpty() && exact.size() < hits.size()) return exact;
        }
        return hits;
    }

    private static boolean hasPurpose(DomNode node, String token) {
        String tokens = attr(node, "data-pkb-purpose");
        if (tokens.isBlank()) return false;
        for (String one : tokens.trim().split("\\s+")) if (one.equals(token)) return true;
        return false;
    }

    private static List<DomNode> locateText(DomNode root, String query, Map<String, String> extra) {
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
        return deepest(controlsFor(search, preferControls(preferBody(preferLinks(hits)))));
    }

    /**
     * A document title can share the visible label (the button text is copied into {@code document.title}).
     * The clickable hit is the control in the body. A head-only match, such as the title itself, stays.
     */
    private static List<DomNode> preferBody(List<DomNode> hits) {
        List<DomNode> body = new ArrayList<>();
        for (DomNode node : hits) {
            if (!inHead(node)) body.add(node);
        }
        return body.isEmpty() ? hits : body;
    }

    private static boolean inHead(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if ("head".equals(cursor.tag())) return true;
        }
        return false;
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
            if (labelMatches(accessible, query)) {
                addHit(hits, node);
                return;
            }
            String text = HtmlTree.textOf(node);
            if (labelMatches(text, query)) {
                if ("label".equals(node.tag()) && node.attributes.containsKey("for")) {
                    DomNode control = byId.get(node.attributes.get("for"));
                    if (control != null) addHit(hits, control);
                    return;
                }
                if (ownedByControl(node, query)) return;
                addHit(hits, node);
                return;
            }
            if (labelMatches(attr(node, "aria-label"), query)) addHit(hits, node);
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

    /** Exact name, a symbol prefix, or a font-icon ligature such as {@code account_circleDan}. */
    static boolean labelMatches(String name, String query) {
        if (name == null || query == null) return false;
        String left = name.replaceAll("\\s+", " ").trim();
        String right = query.replaceAll("\\s+", " ").trim();
        if (left.isEmpty() || right.isEmpty()) return false;
        if (left.equalsIgnoreCase(right)) return true;
        if (left.length() <= right.length()) return false;
        if (!left.regionMatches(true, left.length() - right.length(), right, 0, right.length())) return false;
        int cut = left.length() - right.length();
        char before = left.charAt(cut - 1);
        if (!Character.isLetterOrDigit(before)) return true;
        String prefix = left.substring(0, cut);
        return prefix.indexOf('_') >= 0 || prefix.indexOf('-') >= 0;
    }

    /**
     * An icon inside a link is the link. {@code img}, {@code svg}, {@code i}, and {@code span}
     * promote to the enclosing anchor so find returns the link, not the icon.
     */
    private static List<DomNode> preferLinks(List<DomNode> hits) {
        List<DomNode> promoted = new ArrayList<>();
        if (hits == null) return promoted;
        for (DomNode node : hits) {
            addHit(promoted, linkOf(node));
        }
        return promoted;
    }

    private static DomNode linkOf(DomNode node) {
        if (node == null || !iconCarrier(node)) return node;
        for (DomNode cursor = node.parent; cursor != null; cursor = cursor.parent) {
            if ("a".equals(cursor.tag()) || "link".equalsIgnoreCase(role(cursor))) return cursor;
        }
        return node;
    }

    private static boolean iconCarrier(DomNode node) {
        String tag = node.tag();
        return "img".equals(tag) || "svg".equals(tag) || "i".equals(tag) || "span".equals(tag)
                || "use".equals(tag) || "path".equals(tag);
    }

    /** Name match used by tests. Promotes an icon hit to its link and keeps the control. */
    static List<DomNode> findNamed(DomNode root, String query) {
        if (root != null) root.linkParents();
        return deepest(preferControls(preferLinks(named(root, query))));
    }

    /**
     * A copied static name is not a second target when a button or link already matches.
     * A document shell stays out even when its accessible role is button.
     */
    private static List<DomNode> preferControls(List<DomNode> hits) {
        List<DomNode> controls = new ArrayList<>();
        if (hits == null) return controls;
        for (DomNode node : hits) {
            if (node != null && interactiveNode(node) && !depthWrapper(node)) controls.add(node);
        }
        return controls.isEmpty() ? hits : controls;
    }

    private static String attr(DomNode node, String name) {
        String value = node.attributes.get(name);
        return value == null ? "" : value.trim();
    }

    private static List<DomNode> interactive(DomNode root) {
        return interactive(root, -1);
    }

    /** {@code depth} is how many levels under {@code root} to keep. Negative means the whole subtree. */
    private static List<DomNode> interactive(DomNode root, int depth) {
        List<DomNode> hits = new ArrayList<>();
        walkInteractive(root, depth, hits);
        return hits;
    }

    /** A document, shadow root, or html/body shell is not a content level. */
    private static boolean depthWrapper(DomNode node) {
        if (node.shadowRoot || node.nodeType == 9 || node.nodeType == 11) return true;
        String tag = node.tag();
        return "html".equals(tag) || "body".equals(tag) || "head".equals(tag);
    }

    private static boolean interactiveNode(DomNode node) {
        String tag = node.tag();
        String roleName = node.role == null ? "" : node.role.toLowerCase(Locale.ROOT);
        return INTERACTIVE.contains(tag) || INTERACTIVE.contains(roleName) || "button".equals(roleName);
    }

    private static void walkInteractive(DomNode node, int depthLeft, List<DomNode> hits) {
        if (node == null) return;
        if (depthWrapper(node)) {
            for (DomNode child : node.children) walkInteractive(child, depthLeft, hits);
            for (DomNode shadow : node.shadowRoots) walkInteractive(shadow, depthLeft, hits);
            return;
        }
        if (interactiveNode(node)) hits.add(node);
        if (depthLeft == 0) return;
        int next = depthLeft < 0 ? -1 : depthLeft - 1;
        for (DomNode child : node.children) walkInteractive(child, next, hits);
        for (DomNode shadow : node.shadowRoots) walkInteractive(shadow, next, hits);
    }

    /** Negative when {@code --depth} is not a number. {@link Integer#MIN_VALUE} is that failure. */
    private static int depthOf(Map<String, String> args) {
        String raw = blank(args.get("depth"));
        if (raw.isBlank()) return -1;
        try {
            int depth = Integer.parseInt(raw);
            if (depth < 0) return Integer.MIN_VALUE;
            return depth;
        } catch (NumberFormatException failure) {
            return Integer.MIN_VALUE;
        }
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

    /** {@code --within} is a stored ref, a short id, a backend id, or an element id. */
    static DomNode resolveWithin(DomNode root, String within) {
        if (root == null || within == null || within.isBlank()) return null;
        String token = within.trim();
        Hit remembered = null;
        try {
            remembered = requireHit(token);
            DomNode stored = findNode(root, "", remembered.backend);
            if (stored != null) return stored;
        } catch (Miss ignored) {
            // A fresh id is still an element id or a backend id.
        }
        String expanded = SessionIds.expand(token);
        DomNode fromRef = nodeByRef(root, expanded != null ? expanded : token);
        if (fromRef != null) return fromRef;
        if (token.matches("\\d+")) {
            try {
                DomNode byBackend = findNode(root, "", Long.parseLong(token));
                if (byBackend != null) return byBackend;
            } catch (NumberFormatException ignored) {
                // Fall through to the element id.
            }
        }
        String id = token.startsWith("#") ? token.substring(1) : token;
        DomNode byId = findByElementId(root, id);
        if (byId != null) return byId;
        if (remembered != null && remembered.selector() != null && remembered.selector().startsWith("#")) {
            return findByElementId(root, remembered.selector().substring(1));
        }
        return null;
    }

    static List<DomNode> scopedInteractive(DomNode root, String within, int depth) {
        DomNode shown = within == null || within.isBlank() ? root : resolveWithin(root, within);
        if (shown == null) return List.of();
        return interactive(shown, depth);
    }

    private static DomNode nodeByRef(DomNode root, String ref) {
        if (ref == null || !ref.startsWith("ref:")) return null;
        int mark = ref.lastIndexOf(':');
        if (mark < 0 || mark == ref.length() - 1) return null;
        try {
            return findNode(root, "", Long.parseLong(ref.substring(mark + 1)));
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static DomNode findByElementId(DomNode node, String id) {
        if (node == null || id == null || id.isBlank()) return null;
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = findByElementId(child, id);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = findByElementId(shadow, id);
            if (found != null) return found;
        }
        return null;
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
        return publish(runId, kind, sessionId, version, nodes, query, null);
    }

    private static List<Map<String, Object>> publish(String runId, String kind, String sessionId, String version, List<DomNode> nodes, String query, CdpConnection cdp) {
        Map<String, String[]> frames = cdp == null ? Map.of() : frameIndex(cdp);
        Map<Long, JsonNode> live = cdp == null ? Map.of() : CollabRows.values(cdp, nodes);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DomNode node : nodes) {
            String legacy = "ref:" + version + ":" + node.backendNodeId;
            String ref = publishedRef(version, node.backendNodeId);
            String shortId = SessionIds.intern(runId, node.backendNodeId, legacy);
            String selector = selector(node, query);
            String[] identity = new String[2];
            String frameId = reportedFrame(cdp, node, frames, identity);
            Hit hit = new Hit(
                    kind, sessionId, node.backendNodeId, version, selector, query == null ? "" : query,
                    frameId, identity[0] == null ? "" : identity[0], identity[1] == null ? "" : identity[1], role(node), name(node));
            HITS.put(legacy, hit);
            if (!legacy.equals(ref)) HITS.put(ref, hit);
            HITS.put(shortId, hit);
            boolean visible = node.displayed && !node.states.contains("hidden");
            boolean enabled = !node.states.contains("disabled") && !node.attributes.containsKey("disabled");
            String roleName = role(node);
            String tag = node.tag();
            boolean interactive = INTERACTIVE.contains(tag) || INTERACTIVE.contains(roleName) || "button".equals(roleName);
            String frame = stableFrame(node, frameId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ref", ref);
            row.put("id", shortId);
            row.put("role", roleName);
            row.put("type", tag.isBlank() ? roleName : tag);
            row.put("name", name(node));
            row.put("handle", node.backendNodeId);
            row.put("visible", visible);
            row.put("enabled", enabled);
            row.put("interactive", interactive);
            row.put("frame", frame);
            String via = graftViaOf(node);
            if (!via.isBlank()) row.put("via", via);
            String shadow = shadowModeOf(node);
            if (!shadow.isBlank()) row.put("shadow", shadow);
            String shadowPath = shadowPathOf(node);
            if (!shadowPath.isBlank()) {
                row.put("shadow_path", shadowPath);
            }
            CollabRows.put(row, node, live);
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

    /**
     * Section headers, then the compact lines. Over the line or byte budget, the reply keeps a prefix
     * and the whole list is written under {@code .pickleball/agent-full/}. Hits are not copied beside the lines.
     * {@code --format=json} stores the row objects. Every other format, including the default, stores line strings.
     */
    private static void putBudgetedLines(
            Map<String, Object> body,
            List<Map<String, Object>> rows,
            Path project,
            String runId,
            boolean alwaysFull,
            Map<String, String> args
    ) {
        if (jsonFormat(args)) {
            putJsonRows(body, rows, project, runId, alwaysFull, args);
            return;
        }
        List<Map<String, Object>> shaped = SnapView.matching(rows, args);
        List<String> all = sectioned(shaped, args);
        int lineBudget = SnapView.lineBudget(args);
        int byteBudget = SnapView.byteBudget(args);
        List<String> shown = new ArrayList<>();
        int bytes = 0;
        for (String line : all) {
            int next = bytes + line.length() + 1;
            if (shown.size() >= lineBudget || (shown.size() > 0 && next > byteBudget)) break;
            shown.add(line);
            bytes = next;
        }
        body.put("lines", shown);
        body.put("replyBudget", byteBudget);
        if (args != null && args.get("format") != null && !args.get("format").isBlank()) {
            body.put("format", args.get("format"));
        }
        if (shaped != null && !shaped.isEmpty()) {
            body.put("ref", shaped.get(0).get("ref"));
            body.put("id", shaped.get(0).get("id"));
        }
        boolean truncated = shown.size() < all.size();
        if (truncated) body.put("truncated", true);
        if (truncated || alwaysFull) body.put("full", writeLines(project, runId, all));
    }

    /** Object rows for {@code --format=json}. The default snap print stays line strings. */
    private static void putJsonRows(
            Map<String, Object> body,
            List<Map<String, Object>> rows,
            Path project,
            String runId,
            boolean alwaysFull,
            Map<String, String> args
    ) {
        List<Map<String, Object>> objects = new ArrayList<>(SnapView.filter(rows, args));
        int lineBudget = SnapView.lineBudget(args);
        boolean truncated = false;
        if (objects.size() > lineBudget) {
            objects = new ArrayList<>(objects.subList(0, lineBudget));
            truncated = true;
        }
        body.put("lines", objects);
        int byteBudget = SnapView.byteBudget(args);
        body.put("replyBudget", byteBudget);
        body.put("format", "json");
        if (!objects.isEmpty()) {
            body.put("ref", objects.get(0).get("ref"));
            body.put("id", objects.get(0).get("id"));
        }
        List<String> all = sectioned(SnapView.matching(rows, args), args);
        if (truncated) body.put("truncated", true);
        if (truncated || alwaysFull) body.put("full", writeLines(project, runId, all));
    }

    private static boolean jsonFormat(Map<String, String> args) {
        if (args == null) return false;
        return "json".equalsIgnoreCase(blank(args.get("format")));
    }

    /** The list {@code snap} would put on {@code lines} for these args. Package tests use it. */
    static Object viewLines(List<Map<String, Object>> rows, Map<String, String> args) {
        Map<String, Object> body = new LinkedHashMap<>();
        putBudgetedLines(body, rows, null, "view", false, args);
        return body.get("lines");
    }

    /** Interactable rows under {@code # role} or {@code # type}. {@code --priority=on} reorders and marks each line. */
    private static List<String> sectioned(List<Map<String, Object>> rows, Map<String, String> args) {
        String group = args == null ? "" : blank(args.get("group"));
        boolean byType = "type".equalsIgnoreCase(group);
        boolean priority = args != null && "on".equalsIgnoreCase(blank(args.get("priority")));
        List<Map<String, Object>> ordered = rows == null ? List.of() : new ArrayList<>(rows);
        if (priority) {
            ordered.sort(java.util.Comparator.comparingInt(TargetVerb::priorityRank));
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : ordered) {
            String key = byType
                    ? String.valueOf(row.getOrDefault("type", row.get("role")))
                    : String.valueOf(row.get("role"));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(snapLine(row, priority, SnapView.fieldNames(args)));
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<String>> groupRow : groups.entrySet()) {
            lines.add("# " + groupRow.getKey());
            lines.addAll(groupRow.getValue());
        }
        return lines;
    }

    private static final List<String> SNAP_PRIORITY = List.of(
            "button", "link", "tab", "textbox", "searchbox", "checkbox", "radio", "switch",
            "combobox", "menuitem", "option", "a", "input", "select", "textarea"
    );

    private static int priorityRank(Map<String, Object> row) {
        String roleName = String.valueOf(row.get("role")).toLowerCase(Locale.ROOT);
        int index = SNAP_PRIORITY.indexOf(roleName);
        return index < 0 ? SNAP_PRIORITY.size() : index;
    }

    static List<String> snapLines(List<Map<String, Object>> rows, Map<String, String> args) {
        return sectioned(rows, args);
    }

    private static String snapLine(Map<String, Object> row, boolean priority, List<String> fields) {
        if (fields != null && !fields.isEmpty()) {
            StringBuilder picked = new StringBuilder("-");
            for (String field : fields) {
                if (!row.containsKey(field) || row.get(field) == null) continue;
                picked.append(' ').append(field).append('=').append(LineView.token(String.valueOf(row.get(field))));
            }
            if (priority) picked.append(" priority=").append(priorityRank(row));
            return picked.toString();
        }
        StringBuilder line = new StringBuilder();
        Object name = row.get("name");
        Object shown = name instanceof String text ? StandardView.unescapeQuoted(text) : name;
        line.append("- ").append(row.get("role")).append(" \"").append(shown).append("\"");
        Object ref = row.get("ref");
        Object id = row.get("id");
        boolean hasId = id != null && !String.valueOf(id).isBlank();
        String refText = ref == null ? "" : String.valueOf(ref);
        boolean omitRef = hasId && (refText.length() > 40 || refText.contains("data:"));
        if (ref != null && !omitRef) line.append(" [ref=").append(ref).append("]");
        if (hasId) line.append(" id=").append(LineView.token(String.valueOf(id)));
        if (row.get("visible") != null) line.append(" visible=").append(row.get("visible"));
        if (row.get("enabled") != null) line.append(" enabled=").append(row.get("enabled"));
        if (row.get("interactive") != null) line.append(" interactive=").append(row.get("interactive"));
        Object frame = row.get("frame");
        line.append(" frame=").append(frame == null || String.valueOf(frame).isBlank() ? "top" : LineView.token(String.valueOf(frame)));
        for (String key : CollabRows.KEYS) {
            Object extra = row.get(key);
            if (extra == null) continue;
            line.append(' ').append(key).append('=').append(LineView.token(String.valueOf(extra)));
        }
        if (priority) line.append(" priority=").append(priorityRank(row));
        return line.toString();
    }

    private static String writeLines(Path project, String runId, List<String> lines) {
        Path dir = (project == null ? Path.of(".") : project).resolve(".pickleball").resolve("agent-full");
        Path file = dir.resolve("snap-" + (runId == null || runId.isBlank() ? "run" : runId) + ".txt");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, String.join("\n", lines) + "\n");
        } catch (java.io.IOException failure) {
            throw new CdpException("snap full: " + failure.getMessage());
        }
        return file.toString();
    }

    /**
     * Compact interactable lines for the document this call is in.
     * Same lines as {@code snap}, with no reply budget.
     */
    private static List<String> compactBrowser(BrowserSession session, String runId, TargetSelector.Parsed target) {
        DomNode root = tree(session);
        DomNode shown = root;
        Map<String, String> scopeFlags = scopedFrame(runId, target);
        if (!scopeFlags.isEmpty()) {
            try {
                shown = Frames.scope(root, scopeFlags);
            } catch (CdpException ignored) {
                shown = root;
            }
        }
        String version = docVersion(session.connection());
        List<Map<String, Object>> rows = publish(
                runId, "browser", session.id, version, interactive(shown), "", session.connection());
        return sectioned(rows, Map.of());
    }

    private static List<String> compactDesktop(DesktopTree.Session session, String runId) {
        if (session == null || session.root() == null) return List.of();
        String version = "desktop:" + session.sequence();
        List<Map<String, Object>> rows = publish(
                runId, "desktop", session.id(), version, interactive(session.root()), "");
        return sectioned(rows, Map.of());
    }

    /**
     * Writes the pre/post compact snapshot and returns its id on {@code snapshot}.
     * The lines stay in the file. {@code --snap=auto} is the only caller.
     */
    private static void putAutoSnap(
            Map<String, Object> body,
            Map<String, String> args,
            Path project,
            String runId,
            List<String> before,
            Supplier<List<String>> after
    ) {
        if (!"auto".equals(blank(args == null ? null : args.get("snap")))) return;
        String raw = runId == null || runId.isBlank() ? "run" : runId;
        String safe = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        String id = "auto-" + safe + "-" + AUTO_SEQ.incrementAndGet();
        List<String> lines = new ArrayList<>();
        lines.add("## before");
        if (before != null) lines.addAll(before);
        lines.add("## after");
        List<String> later = after == null ? List.of() : after.get();
        if (later != null) lines.addAll(later);
        writeNamed(project, id, lines);
        body.put("snapshot", id);
    }

    private static void writeNamed(Path project, String id, List<String> lines) {
        Path dir = (project == null ? Path.of(".") : project).resolve(".pickleball").resolve("agent-full");
        Path file = dir.resolve(id + ".txt");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, String.join("\n", lines) + "\n");
        } catch (java.io.IOException failure) {
            throw new CdpException("auto snap: " + failure.getMessage());
        }
    }

    private static Map<String, Object> report(DomNode node, CdpConnection cdp) {
        if (cdp != null && node != null && node.nodeId > 0) {
            Displayed.prepare(cdp, List.of(node));
            RowStates.fill(cdp, List.of(node));
        }
        String cover = "";
        if (cdp != null && node != null && node.nodeId > 0) {
            try {
                cover = coverOf(cdp, cdpSessionOf(node), node);
            } catch (RuntimeException ignored) {
                cover = "";
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        boolean visible = sameVisibility(node, cover != null && !cover.isBlank());
        boolean enabled = !disabled(node);
        report.put("role", role(node));
        report.put("name", name(node));
        report.put("visible", visible);
        report.put("enabled", enabled);
        report.put("actionable", visible && enabled);
        if (cover != null && !cover.isBlank()) report.put("coveredBy", cover);
        report.put("reason", cover != null && !cover.isBlank()
                ? "covered"
                : (visible && enabled ? "visible and enabled" : (visible ? "disabled" : "not visible")));
        return report;
    }

    /**
     * The same shown-or-not answer {@code explain} puts on {@code report.visible}.
     * A covering mask is not shown. A node with no box yet keeps {@code displayed}.
     */
    static boolean sameVisibility(DomNode node, boolean covered) {
        if (node == null || covered) return false;
        if (node.states.contains("hidden")) return false;
        if (node.boxRead || node.hasBox || !node.computed.isEmpty()) return Displayed.isDisplayed(node);
        return node.displayed;
    }

    private static boolean elementVisible(DomNode node, CdpConnection cdp) {
        if (node == null) return false;
        if (cdp != null && node.nodeId > 0) {
            try {
                Displayed.prepare(cdp, List.of(node));
                RowStates.fill(cdp, List.of(node));
            } catch (RuntimeException ignored) {
                // The stored displayed flag is still the answer.
            }
            try {
                String cover = coverOf(cdp, cdpSessionOf(node), node);
                if (cover != null && !cover.isBlank()) return false;
            } catch (RuntimeException ignored) {
                // No cover reading. The box test still applies.
            }
        }
        return sameVisibility(node, false);
    }

    private static boolean inert(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if (cursor.attributes.containsKey("inert")) return true;
        }
        return false;
    }

    /** Bare {@code disabled} counts. So does {@code aria-disabled} other than false or 0, and a disabled fieldset. */
    private static boolean disabled(DomNode node) {
        if (node == null) return true;
        if (node.attributes.containsKey("disabled")) return true;
        if (node.attributes.containsKey("inert")) return true;
        String aria = node.attributes.get("aria-disabled");
        if (aria != null && !aria.isBlank() && !"false".equalsIgnoreCase(aria) && !"0".equals(aria)) return true;
        if (node.liveState != null && node.liveState.path("disabled").asBoolean(false)) return true;
        for (DomNode parent = node.parent; parent != null; parent = parent.parent) {
            if (parent.attributes.containsKey("inert")) return true;
            if ("fieldset".equals(parent.tag()) && parent.attributes.containsKey("disabled")) return true;
        }
        return false;
    }

    static String role(DomNode node) {
        if (node == null) return "element";
        if (node.role != null && !node.role.isBlank()) return node.role;
        String mapped = Query.roleOf(node);
        if (mapped != null && !mapped.isBlank()) return mapped;
        String tag = node.tag();
        return tag.isBlank() ? "element" : tag;
    }

    /**
     * Name shared by snap and find. Accessible name, then visible or label text.
     * Clipped at 80 characters so a row stays one line.
     */
    static String name(DomNode node) {
        if (node == null) return "";
        String raw = rawName(node);
        String normalized = StandardView.normalize(raw);
        return normalized.length() > 80 ? normalized.substring(0, 80) : normalized;
    }

    private static String rawName(DomNode node) {
        if ("select".equals(node.tag())) {
            String label = labelOf(node);
            String selected = selectedText(node);
            if (!label.isBlank() && !selected.isBlank()) return label + " " + selected;
            if (!selected.isBlank()) return selected;
            if (!label.isBlank()) return label;
        }
        String aria = attr(node, "aria-label");
        if (!aria.isBlank()) return aria;
        if (node.accessibleName != null && !node.accessibleName.isBlank()) return node.accessibleName;
        String labelled = labelledBy(node);
        if (!labelled.isBlank()) return labelled;
        String alt = attr(node, "alt");
        if (!alt.isBlank()) return alt;
        String title = attr(node, "title");
        if (!title.isBlank()) return title;
        String label = labelOf(node);
        if (!label.isBlank()) return label;
        String placeholder = attr(node, "placeholder");
        if (!placeholder.isBlank()) return placeholder;
        String buttonValue = buttonValue(node);
        if (!buttonValue.isBlank()) return buttonValue;
        String text = HtmlTree.textOf(node);
        if (!text.isBlank()) return text;
        if (node.nodeValue != null && !node.nodeValue.isBlank()) return node.nodeValue;
        String id = node.attributes.get("id");
        return id == null ? "" : id;
    }

    /** {@code aria-labelledby} ids, in order, joined with a space. */
    private static String labelledBy(DomNode node) {
        String ids = attr(node, "aria-labelledby");
        if (ids.isBlank()) return "";
        DomNode root = node;
        while (root.parent != null) root = root.parent;
        Map<String, DomNode> byId = new LinkedHashMap<>();
        walk(root, candidate -> {
            String id = candidate.attributes.get("id");
            if (id != null && !id.isBlank() && !byId.containsKey(id)) byId.put(id, candidate);
        });
        StringBuilder text = new StringBuilder();
        for (String id : ids.split("\\s+")) {
            DomNode target = byId.get(id);
            if (target == null) continue;
            String piece = HtmlTree.textOf(target).trim();
            if (piece.isBlank()) continue;
            if (!text.isEmpty()) text.append(' ');
            text.append(piece);
        }
        return text.toString();
    }

    /** The value of a button, submit, or reset. A typed field's value is not its name. */
    private static String buttonValue(DomNode node) {
        String tag = node.tag();
        String type = attr(node, "type").toLowerCase(Locale.ROOT);
        boolean button = "button".equals(tag)
                || ("input".equals(tag) && ("button".equals(type) || "submit".equals(type) || "reset".equals(type)));
        return button ? attr(node, "value") : "";
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
            if (root != null) {
                root.linkParents();
                stampRoles(cdp, root);
                List<DomNode> options = new ArrayList<>();
                collectSelectOptions(root, options);
                if (!options.isEmpty()) RowStates.fill(cdp, options);
            }
            return root;
        } finally {
            LiveXPath.use(null);
        }
    }

    /** Direct options of each select. The compact name uses the option that is selected now. */
    private static void collectSelectOptions(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if ("select".equals(node.tag())) {
            for (DomNode child : node.children) {
                if ("option".equals(child.tag()) && child.liveState == null) into.add(child);
            }
        }
        for (DomNode child : node.children) collectSelectOptions(child, into);
        for (DomNode shadow : node.shadowRoots) collectSelectOptions(shadow, into);
    }

    private static String title(CdpConnection cdp) {
        return title(cdp, "");
    }

    private static String title(CdpConnection cdp, String sessionId) {
        ObjectNode params = Jsons.object();
        params.put("returnByValue", true);
        params.put("expression", "document.title");
        JsonNode value = sessionId == null || sessionId.isBlank()
                ? cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5))
                : cdp.call("Runtime.evaluate", params, sessionId, Duration.ofSeconds(5));
        return value.path("result").path("value").asText("");
    }

    private static String href(CdpConnection cdp) {
        return href(cdp, "");
    }

    private static String href(CdpConnection cdp, String sessionId) {
        ObjectNode params = Jsons.object();
        params.put("returnByValue", true);
        params.put("expression", "location.href");
        JsonNode value = sessionId == null || sessionId.isBlank()
                ? cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5))
                : cdp.call("Runtime.evaluate", params, sessionId, Duration.ofSeconds(5));
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

    /** Compact desktop lines with no ref, so a version bump is not a change. */
    private static String desktopPicture(DesktopTree.Session session) {
        StringBuilder text = new StringBuilder();
        if (session == null) return "";
        for (DomNode node : interactive(session.root())) {
            if (text.length() > 0) text.append('\n');
            text.append(desktopLine(node));
        }
        return text.toString();
    }

    private static String desktopLine(DomNode node) {
        StringBuilder line = new StringBuilder();
        line.append("- ").append(role(node)).append(" \"").append(name(node).replace("\"", "'")).append("\"");
        String value = node.nodeValue == null ? "" : node.nodeValue;
        if (!value.isBlank()) line.append(" value=\"").append(value.replace("\"", "'")).append("\"");
        return line.toString();
    }

    private static List<SnapRow> desktopRows(DesktopTree.Session session) {
        List<SnapRow> rows = new ArrayList<>();
        if (session == null || session.root() == null) return rows;
        String version = "desktop:" + session.sequence();
        for (DomNode node : interactive(session.root())) {
            String roleName = role(node);
            String shown = name(node);
            String value = node.nodeValue == null ? "" : node.nodeValue;
            String key = roleName + "\n" + shown + "\n" + value;
            String ref = "ref:" + version + ":" + node.backendNodeId;
            rows.add(new SnapRow(node.backendNodeId, key, desktopLine(node) + " [ref=" + ref + "]"));
        }
        return rows;
    }

    private static String desktopBaselineKey(String runId) {
        return baselineKey(runId) + ":desktop";
    }

    /** Added compact lines, plus {@code removed } lines. An equal picture is an empty list. */
    private static List<String> desktopDiff(String runId, String picture) {
        String previous = BASELINE.put(desktopBaselineKey(runId), picture);
        if (previous == null) return splitLines(picture);
        if (previous.equals(picture)) return List.of();
        List<String> left = new ArrayList<>(splitLines(previous));
        List<String> diff = new ArrayList<>();
        for (String line : splitLines(picture)) {
            if (!left.remove(line)) diff.add(line);
        }
        for (String line : left) diff.add("removed " + line);
        return diff;
    }

    private static List<String> splitLines(String picture) {
        if (picture == null || picture.isBlank()) return List.of();
        return new ArrayList<>(List.of(picture.split("\n", -1)));
    }

    private static void putSnapDiff(
            Map<String, Object> body,
            Map<String, String> args,
            String titleBefore,
            String titleAfter,
            String before,
            String after
    ) {
        putSnapDiff(body, args, titleBefore, titleAfter, before, after, null, null);
    }

    private static void putSnapDiff(
            Map<String, Object> body,
            Map<String, String> args,
            String titleBefore,
            String titleAfter,
            String before,
            String after,
            List<SnapRow> beforeRows,
            List<SnapRow> afterRows
    ) {
        if (!"diff".equals(blank(args == null ? null : args.get("snap")))) return;
        Map<String, Object> diff = diffOf(titleBefore, titleAfter, before, after);
        if (beforeRows != null && afterRows != null) {
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            List<String> updated = new ArrayList<>();
            Map<Long, SnapRow> beforeBy = new LinkedHashMap<>();
            for (SnapRow row : beforeRows) beforeBy.put(row.backend(), row);
            Map<Long, SnapRow> afterBy = new LinkedHashMap<>();
            for (SnapRow row : afterRows) afterBy.put(row.backend(), row);
            for (SnapRow row : afterRows) {
                SnapRow prior = beforeBy.get(row.backend());
                if (prior == null) added.add(row.line());
                else if (!prior.key().equals(row.key())) updated.add(row.line());
            }
            for (SnapRow row : beforeRows) {
                if (!afterBy.containsKey(row.backend())) removed.add(row.line());
            }
            diff.put("added", added);
            diff.put("removed", removed);
            diff.put("updated", updated);
            boolean titleChanged = titleBefore != null && titleAfter != null && !titleBefore.equals(titleAfter);
            boolean urlChanged = !urlOfVersion(before).equals(urlOfVersion(after));
            diff.put("changed", titleChanged || urlChanged || !added.isEmpty() || !removed.isEmpty() || !updated.isEmpty());
            capSnap(diff, added, removed, updated);
        }
        body.put("snap", diff);
        reconcileApplied(body);
    }

    /** A changed snap is not the same reply as {@code applied:false} / no visible change. */
    static void reconcileApplied(Map<String, Object> body) {
        if (body == null) return;
        Object snap = body.get("snap");
        if (!(snap instanceof Map<?, ?> diff)) return;
        if (!body.containsKey("applied")) return;
        if (!Boolean.TRUE.equals(diff.get("changed"))) return;
        if (Boolean.FALSE.equals(body.get("dispatched"))) return;
        body.put("applied", true);
        if (body.containsKey("visible")) body.put("visible", true);
        if ("no visible change".equals(body.get("reason"))) body.remove("reason");
    }

    private static void capSnap(Map<String, Object> diff, List<String> added, List<String> removed, List<String> updated) {
        int omitted = capSnapLines(added) + capSnapLines(removed) + capSnapLines(updated);
        omitted += SnapView.limitLines(added, 20, 160, SnapView.REPLY_CAP);
        omitted += SnapView.limitLines(removed, 20, 160, SnapView.REPLY_CAP);
        omitted += SnapView.limitLines(updated, 20, 160, SnapView.REPLY_CAP);
        if (omitted > 0) {
            diff.put("truncated", true);
            diff.put("omitted", omitted);
        }
    }

    /** Keeps 20 lines and 160 characters. Returns how many lines were dropped. */
    static int capSnapLines(List<String> lines) {
        if (lines == null) return 0;
        int omitted = 0;
        if (lines.size() > 20) {
            omitted = lines.size() - 20;
            lines.subList(20, lines.size()).clear();
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line != null && line.length() > 160) lines.set(i, line.substring(0, 157) + "...");
        }
        return omitted;
    }

    /** The document URL stored in front of the mutation count. */
    private static String urlOfVersion(String version) {
        if (version == null || version.isBlank()) return "";
        int colon = version.lastIndexOf(':');
        return colon < 0 ? version : version.substring(0, colon);
    }

    /** Interactive nodes and nodes with their own text. Pickleball's layer is left out. */
    private static List<SnapRow> elementRows(CdpConnection cdp, String sessionId, String version) {
        DomNode root = tree(cdp);
        List<DomNode> nodes = new ArrayList<>();
        collectSnap(root, false, nodes);
        Map<String, String[]> frames = frameIndex(cdp);
        List<SnapRow> rows = new ArrayList<>();
        for (DomNode node : nodes) rows.add(snapRow(cdp, sessionId, version, node, frames));
        return rows;
    }

    /** Copies computed roles onto the DOM nodes. A missing tree leaves {@link #role} on the tag. */
    private static void stampRoles(CdpConnection cdp, DomNode root) {
        if (cdp == null || root == null) return;
        JsonNode nodes;
        try {
            nodes = cdp.call("Accessibility.getFullAXTree", Jsons.object(), Duration.ofSeconds(8)).path("nodes");
        } catch (RuntimeException failure) {
            return;
        }
        if (!nodes.isArray()) return;
        Map<Long, DomNode> byId = new LinkedHashMap<>();
        indexBackend(root, byId);
        for (JsonNode ax : nodes) {
            if (ax.path("ignored").asBoolean(false)) continue;
            long id = ax.path("backendDOMNodeId").asLong(0);
            DomNode node = byId.get(id);
            if (node == null) continue;
            String roleName = ax.path("role").path("value").asText("");
            if (!roleName.isBlank()) node.role = roleName;
            String accessible = ax.path("name").path("value").asText("");
            if (!accessible.isBlank()) node.accessibleName = accessible;
        }
    }

    private static void indexBackend(DomNode node, Map<Long, DomNode> byId) {
        if (node == null) return;
        if (node.cdpSession != null && !node.cdpSession.isBlank()) return;
        if (node.backendNodeId > 0) byId.put(node.backendNodeId, node);
        for (DomNode child : node.children) indexBackend(child, byId);
        for (DomNode shadow : node.shadowRoots) indexBackend(shadow, byId);
    }

    private static void collectSnap(DomNode node, boolean insidePkb, List<DomNode> into) {
        if (node == null) return;
        boolean pkb = insidePkb
                || node.attributes.containsKey("data-pkb-agent")
                || node.attributes.containsKey("data-pkb-layer")
                || node.attributes.containsKey("data-pkb-record")
                || "true".equals(node.attributes.get("data-pkb-meta"));
        if (!pkb && node.nodeType == 1) {
            String tag = node.tag();
            if (!"script".equals(tag) && !"style".equals(tag) && !"noscript".equals(tag)) {
                String text = ownText(node);
                String roleName = role(node);
                boolean keep = !text.isBlank()
                        || INTERACTIVE.contains(tag)
                        || INTERACTIVE.contains(roleName)
                        || "listitem".equals(roleName)
                        || "li".equals(tag);
                if (keep && node.backendNodeId > 0) into.add(node);
            }
        }
        if (pkb) return;
        for (DomNode child : node.children) collectSnap(child, false, into);
        for (DomNode shadow : node.shadowRoots) collectSnap(shadow, false, into);
    }

    private static String ownText(DomNode node) {
        StringBuilder text = new StringBuilder();
        for (DomNode child : node.children) {
            if (child.nodeType == 3 && child.nodeValue != null) text.append(child.nodeValue);
        }
        return text.toString().replaceAll("\\s+", " ").trim();
    }

    private static SnapRow snapRow(
            CdpConnection cdp,
            String sessionId,
            String version,
            DomNode node,
            Map<String, String[]> frames
    ) {
        String text = ownText(node);
        String shown = text.isBlank() ? name(node) : text;
        String tag = node.tag();
        String value = null;
        if ("input".equals(tag) || "textarea".equals(tag) || "select".equals(tag)) value = controlValue(cdp, node);
        String type = attr(node, "type").toLowerCase(Locale.ROOT);
        String roleName = role(node);
        String checked = null;
        if ("checkbox".equals(type) || "radio".equals(type) || "checkbox".equals(roleName) || "radio".equals(roleName)) {
            checked = checkedOf(cdp, node);
        }
        String key = roleName + "\n" + text + "\n" + (value == null ? "" : value) + "\n" + (checked == null ? "" : checked);
        String[] identity = new String[2];
        String frameId = reportedFrame(cdp, node, frames, identity);
        String url = identity[0] == null ? "" : identity[0];
        String loader = identity[1] == null ? "" : identity[1];
        String legacy = "ref:" + version + ":" + node.backendNodeId;
        String ref = publishedRef(version, node.backendNodeId);
        Hit hit = new Hit(
                "browser", sessionId == null ? "" : sessionId, node.backendNodeId, version, selector(node, shown), shown,
                frameId, url, loader, roleName, shown);
        HITS.put(legacy, hit);
        if (!legacy.equals(ref)) HITS.put(ref, hit);
        StringBuilder line = new StringBuilder();
        line.append("- ").append(roleName).append(" \"").append(shown.replace("\"", "'")).append("\"");
        if (value != null) line.append(" value=\"").append(value.replace("\"", "'")).append("\"");
        if (checked != null) line.append(" checked=").append(checked);
        line.append(" [ref=").append(ref).append("]");
        return new SnapRow(node.backendNodeId, key, line.toString());
    }

    /** Public ref. Desktop versions stay literal. A ref longer than 40 characters, or a data: URL, becomes {@code ref:d<hash>:<backend>}. */
    static String publishedRef(String version, long backend) {
        String safe = version == null ? "" : version;
        if (safe.startsWith("desktop:")) return "ref:" + safe + ":" + backend;
        String literal = "ref:" + safe + ":" + backend;
        if (!bulky(safe) && literal.length() <= 40) return literal;
        return "ref:d" + Integer.toUnsignedString(safe.hashCode(), 36) + ":" + backend;
    }

    /**
     * Frame id a hit reports. Same value {@code frame --list} prints.
     * A real CDP id stays, including an out-of-process id the parent frame tree omitted.
     * Only a blank or {@code main} id borrows the top document.
     */
    private static String reportedFrame(CdpConnection cdp, DomNode node, Map<String, String[]> frames, String[] identityOut) {
        String frameId = node == null || node.frameId == null ? "" : node.frameId.trim();
        String[] identity = frameId.isBlank() ? null : frames.get(frameId);
        if (identity == null && cdp != null && !frameId.isBlank() && !"main".equals(frameId)) {
            String listed = listedFrameId(cdp, frames, frameId);
            if (!listed.isBlank() && !listed.equals(frameId)) {
                frameId = listed;
                identity = frames.get(frameId);
            }
            if (identity == null) identity = new String[]{FrameCoverage.url(cdp, frameId), ""};
        }
        if ((frameId.isBlank() || "main".equals(frameId)) && frames != null && !frames.isEmpty()) {
            Map.Entry<String, String[]> top = frames.entrySet().iterator().next();
            frameId = top.getKey();
            identity = top.getValue();
        }
        identityOut[0] = identity == null || identity.length < 1 || identity[0] == null ? "" : identity[0];
        identityOut[1] = identity == null || identity.length < 2 || identity[1] == null ? "" : identity[1];
        return frameId;
    }

    /** Maps a DOM or target id onto the id {@code frame --list} already shows, when they differ. */
    private static String listedFrameId(CdpConnection cdp, Map<String, String[]> frames, String raw) {
        if (raw == null || raw.isBlank() || frames == null || frames.containsKey(raw)) return raw == null ? "" : raw;
        if (!FrameCoverage.sessionId(cdp, raw).isBlank()) return raw;
        String url = FrameCoverage.url(cdp, raw);
        if (url.isBlank()) return raw;
        for (Map.Entry<String, String[]> entry : frames.entrySet()) {
            String[] known = entry.getValue();
            if (known != null && known.length > 0 && url.equals(known[0]) && !FrameCoverage.sessionId(cdp, entry.getKey()).isBlank()) {
                return entry.getKey();
            }
        }
        return raw;
    }

    /** CDP frame id. An XPath or a blank/main id is {@code top}. */
    static String stableFrame(DomNode node, String frameId) {
        String id = frameId == null ? "" : frameId.trim();
        if (id.isBlank() || "main".equals(id) || id.startsWith("/") || id.startsWith("(")) return "top";
        return id;
    }

    /** Reply docVersion. Desktop stays. A data: URL or a long document address becomes a short token. */
    public static String shownVersion(String version) {
        if (version == null || version.isBlank()) return version == null ? "" : version;
        int arrow = version.indexOf(" -> ");
        if (arrow >= 0) {
            return shownVersion(version.substring(0, arrow)) + " -> " + shownVersion(version.substring(arrow + 4));
        }
        if (version.startsWith("desktop:") || !bulky(version)) return version;
        return "d" + Integer.toUnsignedString(version.hashCode(), 36);
    }

    private static boolean bulky(String value) {
        return value.length() > 80 || value.contains("data:") || value.indexOf('\n') >= 0;
    }

    private static void markBudget(Map<String, Object> body, Map<String, String> args) {
        if (body == null || args == null) return;
        String raw = args.get("budget");
        if (raw == null || raw.isBlank()) return;
        body.put("replyBudget", SnapView.byteBudget(args));
    }

    private static String checkedOf(CdpConnection cdp, DomNode node) {
        if (cdp == null || node == null || node.backendNodeId <= 0) return "false";
        try {
            ObjectNode params = Jsons.object();
            params.put("backendNodeId", node.backendNodeId);
            JsonNode resolved = cdp.call("DOM.resolveNode", params, Duration.ofSeconds(5));
            String objectId = resolved.path("object").path("objectId").asText("");
            if (objectId.isBlank()) return "false";
            ObjectNode call = Jsons.object();
            call.put("objectId", objectId);
            call.put("functionDeclaration", "function(){ return this.checked===true; }");
            call.put("returnByValue", true);
            return cdp.call("Runtime.callFunctionOn", call, Duration.ofSeconds(5))
                    .path("result").path("value").asBoolean(false) ? "true" : "false";
        } catch (RuntimeException failure) {
            return "false";
        }
    }

    private static Map<String, Object> diffOf(String titleBefore, String titleAfter, String before, String after) {
        Map<String, Object> diff = new LinkedHashMap<>();
        if (titleBefore != null && titleAfter != null && (!titleBefore.isEmpty() || !titleAfter.isEmpty())) {
            diff.put("title", titleBefore + " -> " + titleAfter);
        }
        diff.put("docVersion", before + " -> " + after);
        boolean titleChanged = titleBefore != null && titleAfter != null && !titleBefore.equals(titleAfter);
        diff.put("changed", titleChanged || !before.equals(after));
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
                tools.dscode.common.agentcontrol.AgentControl.resetLiveDocument();
                Map<String, Object> body = base("frame", "cdp", "browser", target);
                body.put("entered", "");
                stamp(body, cdp, null);
                putEntered(body, runId, cdp);
                return ok(body);
            }
            String enter = blank(args.get("enter"));
            if (!enter.isBlank()) {
                if (!frameExists(cdp, enter)) return fail("frame", "unknown frame");
                ENTERED.put(key, enter);
                tools.dscode.common.agentcontrol.AgentControl.enterNamedFrame(enter);
                String id = resolveFrameId(cdp, enter);
                Map<String, Object> body = base("frame", "cdp", "browser", target);
                body.put("entered", enter);
                body.put("frame", id.isBlank() ? enter : id);
                return ok(body);
            }
            Map<String, Object> body = base("frame", "cdp", "browser", target);
            body.put("frames", listFrames(cdp));
            String entered = ENTERED.get(key);
            if (entered != null) body.put("entered", entered);
            stamp(body, cdp, null);
            putEntered(body, runId, cdp);
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
            String reported = frame.path("parentId").asText("");
            String parentId = !parent.isBlank() ? parent : reported;
            if (parentId.isBlank()) parentId = FrameCoverage.parentOf(cdp, id);
            row.put("parentId", parentId);
            String url = frame.path("url").asText("");
            if (url.isBlank() || "about:blank".equals(url)) {
                String known = FrameCoverage.url(cdp, id);
                if (!known.isBlank()) url = known;
            }
            row.put("url", url);
            row.put("name", frame.path("name").asText(""));
            String session = FrameCoverage.sessionId(cdp, id);
            row.put("session", !session.isBlank());
            String surface = FrameCoverage.surface(cdp, id);
            if (!surface.isBlank()) row.put("surface", surface);
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
            row.put("parentId", FrameCoverage.parentOf(cdp, id));
            row.put("url", FrameCoverage.url(cdp, id));
            row.put("name", "");
            String session = FrameCoverage.sessionId(cdp, id);
            row.put("session", !session.isBlank());
            String surface = FrameCoverage.surface(cdp, id);
            if (!surface.isBlank()) row.put("surface", surface);
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

    /** Viewport steps on one box before a scroll-search reports {@code capped}. */
    private static final int SCROLL_STEPS = 40;

    private record ScrollCandidate(String mark, String label) {
    }

    private record ScrollSearch(List<DomNode> hits, Map<String, Object> capped) {
    }

    /**
     * Tries every overflow box. A box whose rendered rows change, or whose text matches the query,
     * is tried before a static tall box. The document is last. Each miss restores that box.
     * Forty steps without a hit is {@code scrollSearch:{container, steps, capped:true}}.
     */
    private static ScrollSearch scrollFind(BrowserSession session, String query, Map<String, String> flags) {
        CdpConnection cdp = session.connection();
        List<DomNode> hits = List.of();
        Map<String, Object> capped = null;
        try {
            List<ScrollCandidate> plan = scrollPlan(cdp, query);
            String saved = scrollState(cdp);
            for (ScrollCandidate candidate : plan) {
                int steps = 0;
                int stuck = 0;
                hits = locate(tree(session), query, flags);
                while (hits.isEmpty() && steps < SCROLL_STEPS) {
                    if (!scrollBox(cdp, candidate.mark())) {
                        hits = locate(tree(session), query, flags);
                        if (!hits.isEmpty() || ++stuck >= 3) break;
                        continue;
                    }
                    stuck = 0;
                    steps++;
                    hits = locate(tree(session), query, flags);
                }
                if (!hits.isEmpty()) {
                    restoreScroll(cdp, saved, candidate.mark());
                    break;
                }
                restoreScroll(cdp, saved, "");
                if (capped == null && steps >= SCROLL_STEPS) {
                    Map<String, Object> report = new LinkedHashMap<>();
                    report.put("container", candidate.label());
                    report.put("steps", steps);
                    report.put("capped", true);
                    capped = report;
                }
            }
            if (hits.isEmpty()) restoreScroll(cdp, saved, "");
        } finally {
            clearScrollMarks(cdp);
        }
        if (!hits.isEmpty() && query != null && !query.isBlank()) {
            reveal(cdp, query);
            List<DomNode> painted = locate(tree(session), query, flags);
            if (!painted.isEmpty()) hits = painted;
        }
        return new ScrollSearch(hits, hits.isEmpty() ? capped : null);
    }

    /** Marks each overflow box, probes one step, restores it, and returns boxes best-first. */
    private static List<ScrollCandidate> scrollPlan(CdpConnection cdp, String query) {
        String quoted = Jsons.MAPPER.valueToTree(query == null ? "" : query).toString();
        String expression = "(function(){var q=" + quoted + ";"
                + "function scrollable(el){if(!el||el.nodeType!==1)return false;var s=getComputedStyle(el);var oy=s.overflowY;"
                + "return (oy==='auto'||oy==='scroll')&&el.scrollHeight>el.clientHeight+2;}"
                + "function sig(el){var kids=el.children,parts=[],n=Math.min(kids.length,8);"
                + "for(var i=0;i<n;i++) parts.push(((kids[i].innerText||kids[i].textContent||'')+'').replace(/\\s+/g,' ').trim().slice(0,60));"
                + "return parts.join('|');}"
                + "var nodes=document.querySelectorAll('*');var marked=[];"
                + "for(var i=0;i<nodes.length;i++){if(!scrollable(nodes[i]))continue;"
                + "nodes[i].setAttribute('data-pkb-scroll-box',String(marked.length+1));marked.push(nodes[i]);}"
                + "var root=document.scrollingElement;"
                + "if(root&&root.nodeType===1&&!root.getAttribute('data-pkb-scroll-box')){"
                + "root.setAttribute('data-pkb-scroll-box',String(marked.length+1));marked.push(root);}"
                + "var token=((q||'').trim().split(/\\s+/)[0]||'').toLowerCase();var lines=[];"
                + "for(var j=0;j<marked.length;j++){var el=marked[j];var before=sig(el);var top=el.scrollTop;"
                + "var step=Math.max(40,Math.floor(el.clientHeight*0.8));"
                + "if(scrollable(el)){el.scrollTop=top+step;try{el.dispatchEvent(new Event('scroll'));}catch(e){}}"
                + "var after=sig(el);var moved=el.scrollTop!==top;el.scrollTop=top;"
                + "if(scrollable(el)){try{el.dispatchEvent(new Event('scroll'));}catch(e){}}"
                + "var text=((el.innerText||el.textContent||'')+'').toLowerCase();"
                + "var like=!!(token&&text.indexOf(token)>=0);var virtual=moved&&before!==after;"
                + "var rank=virtual?0:(like?1:2);if(el===root&&!virtual&&!like)rank=3;"
                + "var label=el.id?el.id:(el===root?'document':el.tagName.toLowerCase());"
                + "lines.push(rank+'\\t'+el.getAttribute('data-pkb-scroll-box')+'\\t'+label);}"
                + "lines.sort(function(a,b){return (+a.split('\\t')[0])-(+b.split('\\t')[0]);});"
                + "return lines.join('\\n');})()";
        List<ScrollCandidate> plan = new ArrayList<>();
        String raw = evalText(cdp, expression);
        if (raw.isBlank()) return plan;
        for (String line : raw.split("\n")) {
            String[] part = line.split("\t", 3);
            if (part.length < 3 || part[1].isBlank()) continue;
            plan.add(new ScrollCandidate(part[1], part[2]));
        }
        return plan;
    }

    private static boolean scrollBox(CdpConnection cdp, String mark) {
        String expression = "(function(){var el=document.querySelector('[data-pkb-scroll-box=\"" + mark + "\"]');"
                + "if(!el)return false;var before=el.scrollTop;"
                + "el.scrollTop=before+Math.max(40,Math.floor(el.clientHeight*0.8));"
                + "try{el.dispatchEvent(new Event('scroll'));}catch(e){}"
                + "return el.scrollTop!==before;})()";
        return "true".equals(evalText(cdp, expression));
    }

    private static void clearScrollMarks(CdpConnection cdp) {
        evalText(cdp, "(function(){var nodes=document.querySelectorAll('[data-pkb-scroll-box]');"
                + "for(var i=0;i<nodes.length;i++) nodes[i].removeAttribute('data-pkb-scroll-box');return true;})()");
    }

    private static String evalText(CdpConnection cdp, String expression) {
        if (cdp == null) return "";
        ObjectNode params = Jsons.object();
        params.put("expression", expression);
        params.put("returnByValue", true);
        try {
            return cdp.call("Runtime.evaluate", params, Duration.ofSeconds(8)).path("result").path("value").asText("");
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static String scrollState(CdpConnection cdp) {
        ObjectNode params = Jsons.object();
        params.put("expression", "(function(){function scrollable(el){var s=getComputedStyle(el);var oy=s.overflowY;"
                + "return (oy==='auto'||oy==='scroll')&&el.scrollHeight>el.clientHeight+2;}"
                + "var nodes=document.querySelectorAll('*');var tops=[];"
                + "for(var i=0;i<nodes.length;i++){if(scrollable(nodes[i])) tops.push(nodes[i].scrollTop);}"
                + "var root=document.scrollingElement; if(root) tops.push(root.scrollTop); return tops.join(',');})()");
        params.put("returnByValue", true);
        try {
            return cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5)).path("result").path("value").asText("");
        } catch (RuntimeException failure) {
            return "";
        }
    }

    private static void restoreScroll(CdpConnection cdp, String saved, String keepMark) {
        if (cdp == null || saved == null) return;
        String keep = keepMark == null ? "" : keepMark;
        ObjectNode params = Jsons.object();
        params.put("expression", "(function(){var saved=" + jsString(saved) + ".split(',');"
                + "var keep=" + jsString(keep) + ";"
                + "function scrollable(el){var s=getComputedStyle(el);var oy=s.overflowY;"
                + "return (oy==='auto'||oy==='scroll')&&el.scrollHeight>el.clientHeight+2;}"
                + "function put(el, index){if(!el||index>=saved.length) return;"
                + "if(keep&&el.getAttribute&&el.getAttribute('data-pkb-scroll-box')===keep) return;"
                + "el.scrollTop=Number(saved[index])||0;"
                + "try{el.dispatchEvent(new Event('scroll'));}catch(e){}}"
                + "var nodes=document.querySelectorAll('*');var n=0;"
                + "for(var i=0;i<nodes.length;i++){if(!scrollable(nodes[i])) continue; put(nodes[i], n); n++;}"
                + "var root=document.scrollingElement; if(root) put(root, n); return true;})()");
        params.put("returnByValue", true);
        try {
            cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
        } catch (RuntimeException ignored) {
            // A miss still reports not found when the scroll position cannot be put back.
        }
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
            if (!cursor.shadowRoot || cursor.shadowMode == null || cursor.shadowMode.isBlank()) continue;
            return "closed".equalsIgnoreCase(cursor.shadowMode) ? "closed" : "open";
        }
        return "";
    }

    /** One click through {@code DOM.resolveNode} in the pkb world. Coordinate input misses {@code pointer-events:none}. */
    private static Effect clickClosed(CdpConnection cdp, DomNode node) {
        if (node == null || node.backendNodeId <= 0) return Effect.refused("resolveNode", "detached");
        String before = pageMark(cdp);
        try {
            int context = pkbContext(cdp);
            ObjectNode resolve = Jsons.object();
            resolve.put("backendNodeId", node.backendNodeId);
            if (context > 0) resolve.put("executionContextId", context);
            JsonNode object = cdp.call("DOM.resolveNode", resolve, Duration.ofSeconds(5)).path("object");
            String objectId = object.path("objectId").asText("");
            if (objectId.isBlank()) return Effect.refused("resolveNode", "detached");
            ObjectNode call = Jsons.object();
            call.put("objectId", objectId);
            // Title, text, and attributes on the node. A closed-shadow dataset write is not in the light DOM.
            call.put("functionDeclaration", "function(){ var before=String(this.outerHTML)+'\\n'+document.title;"
                    + " this.click(); var after=String(this.outerHTML)+'\\n'+document.title;"
                    + " return before===after?'same':'changed'; }");
            call.put("returnByValue", true);
            JsonNode called = cdp.call("Runtime.callFunctionOn", call, Duration.ofSeconds(5));
            if (called.has("exceptionDetails")) return Effect.refused("resolveNode", "detached");
            if ("changed".equals(called.path("result").path("value").asText(""))) return Effect.changed("resolveNode");
            return clickSettled(cdp, "", before, "resolveNode");
        } catch (RuntimeException failure) {
            return Effect.refused("resolveNode", "detached");
        }
    }

    private static void putEntered(Map<String, Object> body, String runId, CdpConnection cdp) {
        putEntered(body, runId, cdp, null);
    }

    /**
     * Fills a blank or main-frame id with the entered frame's CDP id.
     * A hit that already names its own frame, such as a nested grandchild, keeps that id.
     */
    private static void putEntered(Map<String, Object> body, String runId, CdpConnection cdp, List<Map<String, Object>> rows) {
        String token = ENTERED.get(runId == null ? "" : runId);
        if (token == null || token.isBlank() || cdp == null) return;
        String id = resolveFrameId(cdp, token);
        if (id.isBlank()) return;
        String main = "";
        try {
            main = mainFrameId(cdp);
        } catch (RuntimeException ignored) {
            main = "";
        }
        replaceGenericFrame(body, id, main);
        if (rows == null) return;
        for (Map<String, Object> row : rows) replaceGenericFrame(row, id, main);
    }

    private static void replaceGenericFrame(Map<String, Object> body, String id, String main) {
        Object existing = body.get("frame");
        if (existing instanceof String text && !text.isBlank() && !text.equals(main) && !"main".equals(text)) return;
        body.put("frame", id);
    }

    private static void putShadow(Map<String, Object> body, DomNode node) {
        String path = shadowPathOf(node);
        if (!path.isBlank()) body.put("shadow_path", path);
    }

    /** CDP session of the entered frame, or empty when that frame is in-process. */
    private static String enteredSession(CdpConnection cdp, String runId) {
        String token = ENTERED.get(runId == null ? "" : runId);
        if (token == null || token.isBlank() || cdp == null) return "";
        return FrameCoverage.sessionId(cdp, resolveFrameId(cdp, token));
    }

    private static String resolveFrameId(CdpConnection cdp, String token) {
        for (Map<String, Object> row : listFrames(cdp)) {
            if (token.equals(String.valueOf(row.get("id"))) || token.equals(String.valueOf(row.get("name")))) {
                return String.valueOf(row.get("id"));
            }
        }
        DomNode owner = findFrameElement(tree(cdp), token);
        if (owner == null) return token;
        for (DomNode child : owner.children) {
            if (child.nodeType == 9 && child.frameId != null && !child.frameId.isBlank() && !"main".equals(child.frameId)) {
                return child.frameId;
            }
        }
        if (owner.ownerFrameId != null && !owner.ownerFrameId.isBlank() && !owner.ownerFrameId.equals(owner.frameId)) {
            return owner.ownerFrameId;
        }
        return token;
    }

    private static DomNode findFrameElement(DomNode node, String id) {
        if (node == null) return null;
        if (node.iframe && (id.equals(node.attributes.get("id")) || id.equals(node.attributes.get("name"))
                || id.equals(node.ownerFrameId) || id.equals(node.frameId))) {
            return node;
        }
        for (DomNode child : node.children) {
            DomNode found = findFrameElement(child, id);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Steps reach the main document, same-origin frames, and open shadow.
     * An out-of-process frame or a closed shadow is reported as a difference.
     */
    private static void stampContexts(Map<String, Object> body, DomNode root) {
        List<String> extra = new ArrayList<>();
        collectContextDiff(root, extra);
        if (extra.isEmpty()) {
            body.put("contexts", "same");
            return;
        }
        body.put("contexts", "diff");
        body.put("contextDiff", "steps stay on the main document, same-origin frames, and open shadow; agent also has " + String.join(", ", extra));
    }

    private static void collectContextDiff(DomNode node, List<String> extra) {
        if (node == null) return;
        if (node.iframe) {
            for (DomNode child : node.children) {
                if (child.nodeType == 9 && child.cdpSession != null && !child.cdpSession.isBlank()) {
                    String id = child.frameId == null || child.frameId.isBlank() ? node.ownerFrameId : child.frameId;
                    extra.add("oopif " + id);
                }
            }
        }
        if (node.shadowRoot && "closed".equalsIgnoreCase(node.shadowMode)) {
            String host = node.parent == null ? "" : selector(node.parent, "");
            extra.add("closed-shadow " + host);
        }
        for (DomNode child : node.children) collectContextDiff(child, extra);
        for (DomNode shadow : node.shadowRoots) collectContextDiff(shadow, extra);
    }

    private static String graftViaOf(DomNode node) {
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if (cursor.graftVia != null && !cursor.graftVia.isBlank()) return cursor.graftVia;
        }
        return "";
    }

    private static String shadowPathOf(DomNode node) {
        if (node != null && node.shadowPath != null && !node.shadowPath.isBlank()) return node.shadowPath;
        List<String> hosts = new ArrayList<>();
        for (DomNode cursor = node; cursor != null; cursor = cursor.parent) {
            if (cursor.shadowRoot && cursor.parent != null) hosts.add(0, selector(cursor.parent, ""));
        }
        return String.join(" >> ", hosts);
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
        else body.put("frame", "top");
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
        boolean usage = error != null && (error.startsWith("unknown kind") || error.contains("does not use"));
        return new Outcome(usage ? 2 : 1, body);
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

    private record Effect(String used, boolean applied, String disposition, String reason, String detail) {
        Effect(String used, boolean applied) {
            this(used, applied, applied ? "applied" : "failed", "", "");
        }

        /** Existing four-argument calls keep an empty detail. */
        Effect(String used, boolean applied, String disposition, String reason) {
            this(used, applied, disposition, reason == null ? "" : reason, "");
        }

        static Effect changed(String used) {
            return new Effect(used, true, "applied", "");
        }

        static Effect quiet(String used) {
            return new Effect(used, false, "dispatched", "no visible change");
        }

        /** The click was sent and no before/after pair could be read. Never reported as a false {@code false}. */
        static Effect unknown(String used) {
            return new Effect(used, false, "dispatched", "unknown");
        }

        boolean unknownApplied() {
            return "unknown".equals(reason);
        }

        static Effect refused(String used, String reason) {
            return new Effect(used, false, "refused", reason == null || reason.isBlank() ? "detached" : reason);
        }

        static Effect covered(String used, String by) {
            return new Effect(used, false, "refused", "covered", by == null ? "" : by);
        }

        boolean refused() {
            return "refused".equals(disposition);
        }

        boolean quiet() {
            return "dispatched".equals(disposition);
        }
    }

    private record SnapRow(long backend, String key, String line) {
    }

    private record Hit(
            String kind,
            String sessionId,
            long backend,
            String docVersion,
            String selector,
            String query,
            String frameId,
            String url,
            String loader,
            String role,
            String name
    ) {
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
