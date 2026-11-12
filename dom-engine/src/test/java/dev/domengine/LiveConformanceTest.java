package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.conform.Conformance;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live Chrome conformance. The offline suite stays green on its own.
 * This run evaluates the assertions the offline path cannot see.
 */
class LiveConformanceTest {
    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void liveChromeCoversAssertionsTheOfflinePathSkips() throws Exception {
        String chrome = BrowserSession.findChrome();
        assumeTrue(chrome != null,
                "missing Chrome binary (looked for DOMENGINE_CHROME, google-chrome, google-chrome-stable, chromium, chromium-browser, chrome)");
        Path root = Path.of("src/test/resources/conformance");
        JsonNode offline = Conformance.run(root);
        assertEquals(0, offline.path("failed").asInt(), offline.path("failures").toString());

        CliResult cli = DomEngineMain.dispatch(List.of("conform", "--root", root.toString(), "--live"));
        assertEquals(0, cli.exit(), cli.stderr());
        JsonNode live = Jsons.MAPPER.readTree(cli.stdout());
        assertTrue(live.path("live").asBoolean(), live.toString());
        assertEquals(0, live.path("failed").asInt(), live.path("failures").toString());
        assertTrue(live.path("cases").asInt() >= 100, live.toString());
        assertTrue(live.path("checked").asInt() > offline.path("checked").asInt(), live.toString());
    }
}
