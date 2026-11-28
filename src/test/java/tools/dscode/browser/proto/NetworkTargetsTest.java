package tools.dscode.browser.proto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PKB-HCR 2026-11-28 item 12: network emulation also goes to the page's service and shared workers. */
class NetworkTargetsTest {
    @Test
    void serviceAndSharedWorkersAreNetworkTargets() {
        List<CdpSession.Target> all = List.of(
                new CdpSession.Target("s1", "t1", "page", "http://a/"),
                new CdpSession.Target("s2", "t2", "service_worker", "http://a/sw.js"),
                new CdpSession.Target("s3", "t3", "iframe", "http://b/"),
                new CdpSession.Target("s4", "t4", "shared_worker", "http://a/shared.js"));
        List<CdpSession.Target> workers = RouteCommands.workerTargets(all);
        assertEquals(List.of("s2", "s4"), workers.stream().map(t -> t.sessionId).toList());
    }
}
