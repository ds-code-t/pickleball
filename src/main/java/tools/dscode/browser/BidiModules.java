package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin typed wrappers for the WebDriver BiDi modules (W3C editor's draft retrieved 2026-11-24). Each method builds the
 * command's parameter object and sends it through one {@link Caller}; an unknown command surfaces as
 * {@link UnsupportedProtocolOperation} so {@link BrowserProtocols} can fall through to the next backend.
 */
public final class BidiModules {
    /** Sends one BiDi command and returns its result object. */
    @FunctionalInterface
    public interface Caller {
        JsonNode call(String method, Map<String, ?> params);
    }

    private final Caller caller;

    public BidiModules(Caller caller) {
        this.caller = caller;
    }

    public static BidiModules over(RpcChannel channel) {
        return new BidiModules(channel::call);
    }

    /** Key/value pairs to an ordered map; null values are left out. */
    public static Map<String, Object> p(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    public JsonNode send(String method, Map<String, ?> params) {
        try {
            return caller.call(method, params == null ? Map.of() : params);
        } catch (UnsupportedProtocolOperation unsupported) {
            throw unsupported;
        } catch (IllegalStateException failure) {
            if (UnsupportedProtocolOperation.unknownCommand(failure.getMessage())) {
                throw new UnsupportedProtocolOperation(method, "bidi", failure.getMessage());
            }
            throw failure;
        }
    }

    // session
    public JsonNode status() { return send("session.status", Map.of()); }
    public JsonNode subscribe(List<String> events, List<String> contexts) { return send("session.subscribe", p("events", events, "contexts", contexts)); }
    public JsonNode unsubscribe(List<String> subscriptionIds) { return send("session.unsubscribe", p("subscriptions", subscriptionIds)); }

    // browsingContext
    public JsonNode getTree() { return send("browsingContext.getTree", Map.of()); }
    public JsonNode create(String type, String userContext) { return send("browsingContext.create", p("type", type == null ? "tab" : type, "userContext", userContext)); }
    public JsonNode close(String context) { return send("browsingContext.close", p("context", context)); }
    public JsonNode activate(String context) { return send("browsingContext.activate", p("context", context)); }
    public JsonNode navigate(String context, String url, String wait) { return send("browsingContext.navigate", p("context", context, "url", url, "wait", wait == null ? "complete" : wait)); }
    public JsonNode reload(String context, boolean ignoreCache) { return send("browsingContext.reload", p("context", context, "ignoreCache", ignoreCache, "wait", "complete")); }
    public JsonNode traverseHistory(String context, int delta) { return send("browsingContext.traverseHistory", p("context", context, "delta", delta)); }
    public JsonNode setViewport(String context, int width, int height, Double dpr) { return send("browsingContext.setViewport", p("context", context, "viewport", p("width", width, "height", height), "devicePixelRatio", dpr)); }
    public JsonNode locateNodes(String context, Map<String, ?> locator, Integer max) { return send("browsingContext.locateNodes", p("context", context, "locator", locator, "maxNodeCount", max)); }
    public JsonNode locateByRole(String context, String role, String name, Integer max) { return locateNodes(context, p("type", "accessibility", "value", p("role", role, "name", name)), max); }
    public JsonNode handleUserPrompt(String context, boolean accept, String text) { return send("browsingContext.handleUserPrompt", p("context", context, "accept", accept, "userText", text)); }
    public JsonNode print(String context) { return send("browsingContext.print", p("context", context)); }
    public JsonNode captureScreenshot(String context) { return send("browsingContext.captureScreenshot", p("context", context)); }
    public JsonNode startScreencast(String context) { return send("browsingContext.startScreencast", p("context", context)); }
    public JsonNode stopScreencast(String context) { return send("browsingContext.stopScreencast", p("context", context)); }
    public JsonNode setBypassCSP(String context, boolean bypass) { return send("browsingContext.setBypassCSP", p("context", context, "bypass", bypass)); }

    // network
    public JsonNode addIntercept(List<String> phases, List<Map<String, ?>> urlPatterns, List<String> contexts) { return send("network.addIntercept", p("phases", phases, "urlPatterns", urlPatterns, "contexts", contexts)); }
    public JsonNode removeIntercept(String intercept) { return send("network.removeIntercept", p("intercept", intercept)); }
    public JsonNode continueRequest(String request, Map<String, ?> overrides) { Map<String, Object> m = p("request", request); if (overrides != null) m.putAll(overrides); return send("network.continueRequest", m); }
    public JsonNode continueResponse(String request, Map<String, ?> overrides) { Map<String, Object> m = p("request", request); if (overrides != null) m.putAll(overrides); return send("network.continueResponse", m); }
    public JsonNode continueWithAuth(String request, String user, String password) { return send("network.continueWithAuth", p("request", request, "action", "provideCredentials", "credentials", p("type", "password", "username", user, "password", password))); }
    public JsonNode failRequest(String request) { return send("network.failRequest", p("request", request)); }
    public JsonNode provideResponse(String request, int status, List<Map<String, ?>> headers, String bodyText) { return send("network.provideResponse", p("request", request, "statusCode", status, "headers", headers, "body", bodyText == null ? null : p("type", "string", "value", bodyText))); }
    public JsonNode setCacheBehavior(boolean bypass) { return send("network.setCacheBehavior", p("cacheBehavior", bypass ? "bypass" : "default")); }
    public JsonNode setExtraHeaders(List<Map<String, ?>> headers, List<String> contexts) { return send("network.setExtraHeaders", p("headers", headers, "contexts", contexts)); }
    public JsonNode addDataCollector(List<String> dataTypes, int maxSize) { return send("network.addDataCollector", p("dataTypes", dataTypes, "maxEncodedDataSize", maxSize)); }
    public JsonNode removeDataCollector(String collector) { return send("network.removeDataCollector", p("collector", collector)); }
    public JsonNode getData(String request, String dataType) { return send("network.getData", p("request", request, "dataType", dataType == null ? "response" : dataType)); }

    // script
    public JsonNode evaluate(String context, String expression, String sandbox, boolean awaitPromise) { return send("script.evaluate", p("expression", expression, "target", p("context", context, "sandbox", sandbox), "awaitPromise", awaitPromise)); }
    public JsonNode callFunction(String context, String fn, List<?> args, String sandbox) { return send("script.callFunction", p("functionDeclaration", fn, "target", p("context", context, "sandbox", sandbox), "arguments", args, "awaitPromise", true)); }
    public JsonNode getRealms(String context) { return send("script.getRealms", p("context", context)); }
    public JsonNode addPreloadScript(String fn, List<?> arguments, List<String> contexts, String sandbox) { return send("script.addPreloadScript", p("functionDeclaration", fn, "arguments", arguments, "contexts", contexts, "sandbox", sandbox)); }
    public JsonNode removePreloadScript(String script) { return send("script.removePreloadScript", p("script", script)); }

    // input
    public JsonNode performActions(String context, List<?> actions) { return send("input.performActions", p("context", context, "actions", actions)); }
    public JsonNode releaseActions(String context) { return send("input.releaseActions", p("context", context)); }
    public JsonNode setFiles(String context, Map<String, ?> element, List<String> files) { return send("input.setFiles", p("context", context, "element", element, "files", files)); }

    // storage
    public JsonNode getCookies(Map<String, ?> filter, Map<String, ?> partition) { return send("storage.getCookies", p("filter", filter, "partition", partition)); }
    public JsonNode setCookie(Map<String, ?> cookie, Map<String, ?> partition) { return send("storage.setCookie", p("cookie", cookie, "partition", partition)); }
    public JsonNode deleteCookies(Map<String, ?> filter, Map<String, ?> partition) { return send("storage.deleteCookies", p("filter", filter, "partition", partition)); }

    // browser
    public JsonNode createUserContext() { return send("browser.createUserContext", Map.of()); }
    public JsonNode getUserContexts() { return send("browser.getUserContexts", Map.of()); }
    public JsonNode removeUserContext(String userContext) { return send("browser.removeUserContext", p("userContext", userContext)); }
    public JsonNode getClientWindows() { return send("browser.getClientWindows", Map.of()); }
    public JsonNode setDownloadBehavior(String destinationFolder, boolean deny) { return send("browser.setDownloadBehavior", p("downloadBehavior", deny ? p("type", "denied") : p("type", "allowed", "destinationFolder", destinationFolder))); }

    // emulation (every emulation.set* command in the 2026-11-13 draft)
    public JsonNode setGeolocationOverride(List<String> contexts, Double lat, Double lon, Double accuracy) { return send("emulation.setGeolocationOverride", p("contexts", contexts, "coordinates", lat == null ? null : p("latitude", lat, "longitude", lon, "accuracy", accuracy == null ? 1.0 : accuracy))); }
    public JsonNode setLocaleOverride(List<String> contexts, String locale) { return send("emulation.setLocaleOverride", p("contexts", contexts, "locale", locale)); }
    public JsonNode setTimezoneOverride(List<String> contexts, String timezone) { return send("emulation.setTimezoneOverride", p("contexts", contexts, "timezone", timezone)); }
    public JsonNode setScreenOrientationOverride(List<String> contexts, String natural, String type) { return send("emulation.setScreenOrientationOverride", p("contexts", contexts, "screenOrientation", type == null ? null : p("natural", natural, "type", type))); }
    public JsonNode setScreenSettingsOverride(List<String> contexts, Integer width, Integer height) { return send("emulation.setScreenSettingsOverride", p("contexts", contexts, "screenArea", width == null ? null : p("width", width, "height", height))); }
    public JsonNode setUserAgentOverride(List<String> contexts, String userAgent) { return send("emulation.setUserAgentOverride", p("contexts", contexts, "userAgent", userAgent)); }
    public JsonNode setForcedColorsModeThemeOverride(List<String> contexts, String theme) { return send("emulation.setForcedColorsModeThemeOverride", p("contexts", contexts, "theme", theme)); }
    public JsonNode setNetworkConditions(List<String> contexts, boolean offline) { return send("emulation.setNetworkConditions", p("contexts", contexts, "networkConditions", offline ? p("type", "offline") : null)); }
    public JsonNode setScriptingEnabled(List<String> contexts, Boolean enabled) { return send("emulation.setScriptingEnabled", p("contexts", contexts, "enabled", enabled)); }
    public JsonNode setTouchOverride(List<String> contexts, Integer maxTouchPoints) { return send("emulation.setTouchOverride", p("contexts", contexts, "maxTouchPoints", maxTouchPoints)); }

    // permissions (extension module)
    public JsonNode setPermission(String name, String state, String origin) { return send("permissions.setPermission", p("descriptor", p("name", name), "state", state, "origin", origin)); }

    // webExtension
    public JsonNode installExtension(String path) { return send("webExtension.install", p("extensionData", p("type", "path", "path", path))); }
    public JsonNode uninstallExtension(String extension) { return send("webExtension.uninstall", p("extension", extension)); }
}
