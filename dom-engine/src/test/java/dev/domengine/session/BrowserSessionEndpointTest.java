package dev.domengine.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A page-target attach keeps its page identity; a browser attach reports the http base. */
class BrowserSessionEndpointTest {
    @Test
    void pageTargetAttachReportsThePageDevtoolsUrl() {
        String page = "ws://127.0.0.1:9222/devtools/page/ABC123";
        BrowserSession.Endpoint endpoint = BrowserSession.discover(page);
        assertTrue(endpoint.pinnedPage());
        assertEquals("http://127.0.0.1:9222", endpoint.http());
        assertEquals(page, BrowserSession.recordedCdp(endpoint));
    }

    @Test
    void browserLevelSocketReportsTheHttpBase() throws Exception {
        try (dev.domengine.FakeCdp fake = dev.domengine.FakeCdp.start()) {
            BrowserSession.Endpoint endpoint = BrowserSession.discover(
                    "ws://127.0.0.1:" + fake.port + "/devtools/browser/XYZ");
            assertFalse(endpoint.pinnedPage());
            assertEquals("http://127.0.0.1:" + fake.port, BrowserSession.recordedCdp(endpoint));
            assertTrue(endpoint.webSocket().contains("/devtools/page/PAGE1"), endpoint.webSocket());
            assertFalse(endpoint.webSocket().contains("/devtools/browser/"), endpoint.webSocket());
        }
    }

    @Test
    void discoveredFirstPageIsNotPinned() {
        BrowserSession.Endpoint endpoint = new BrowserSession.Endpoint(
                "http://127.0.0.1:9222", "ws://127.0.0.1:9222/devtools/page/FIRST", false);
        assertEquals("http://127.0.0.1:9222", BrowserSession.recordedCdp(endpoint));
    }
}
