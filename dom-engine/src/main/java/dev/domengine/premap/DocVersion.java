package dev.domengine.premap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.snapshot.SnapshotWarnings;

import java.time.Duration;

/**
 * Browser document version {@code <loaderId>:<mutationCount>}.
 * The counter lives in the isolated world {@code pkb-premap}.
 */
public final class DocVersion {
    public static final String WORLD = "pkb-premap";

    /** Installed with {@code Page.addScriptToEvaluateOnNewDocument} and read with one evaluate. */
    public static final String SCRIPT = """
            (function(){
              if (!globalThis.__pkbPremapInstalled) {
                globalThis.__pkbPremapInstalled = true;
                globalThis.__pkbPremapCount = 0;
                var bump = function(){ globalThis.__pkbPremapCount++; };
                var arm = function(){
                  try {
                    var root = document.documentElement || document;
                    new MutationObserver(bump).observe(root, {subtree:true, childList:true, attributes:true, characterData:true});
                  } catch (e) {}
                };
                if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', arm);
                else arm();
              }
              return String(globalThis.__pkbPremapCount || 0);
            })()
            """;

    private DocVersion() {
    }

    public static String of(String loaderId, long mutations) {
        String loader = loaderId == null ? "" : loaderId;
        long count = mutations < 0 ? 0 : mutations;
        return loader + ":" + count;
    }

    /**
     * Installs the counter for later documents, creates the isolated world on {@code frameId},
     * and reads the count. A failure keeps {@code loaderId:0} and warns once.
     */
    public static String read(CdpConnection connection, String frameId, String loaderId) {
        try {
            ObjectNode preload = Jsons.object();
            preload.put("source", SCRIPT);
            preload.put("worldName", WORLD);
            connection.call("Page.addScriptToEvaluateOnNewDocument", preload, Duration.ofSeconds(5));
            ObjectNode world = Jsons.object();
            if (frameId != null && !frameId.isBlank()) world.put("frameId", frameId);
            world.put("worldName", WORLD);
            world.put("grantUniveralAccess", true);
            JsonNode created = connection.call("Page.createIsolatedWorld", world, Duration.ofSeconds(5));
            int context = created.path("executionContextId").asInt(0);
            ObjectNode evaluate = Jsons.object();
            evaluate.put("expression", SCRIPT);
            evaluate.put("returnByValue", true);
            if (context > 0) evaluate.put("contextId", context);
            JsonNode value = connection.call("Runtime.evaluate", evaluate, Duration.ofSeconds(5));
            return of(loaderId, count(value));
        } catch (RuntimeException failure) {
            SnapshotWarnings.once("doc-version " + failure.getMessage());
            return of(loaderId, 0);
        }
    }

    public static long count(JsonNode value) {
        JsonNode remote = value == null ? null : value.path("result").path("value");
        if (remote == null || remote.isMissingNode() || remote.isNull()) return 0;
        if (remote.isNumber()) return remote.asLong();
        String text = remote.asText("").trim();
        if (text.isEmpty()) return 0;
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException failure) {
            return 0;
        }
    }
}
