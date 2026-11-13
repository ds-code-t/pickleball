package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.session.SessionRegistry;
import dev.domengine.tree.DomNode;
import dev.domengine.tree.LiveTree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentWalkFixtureTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void pierceIncludesShadowAndTheChildFrame() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CdpConnection connection = CdpConnection.connect(
                    URI.create("ws://127.0.0.1:" + cdp.port + "/devtools/page/PAGE1"),
                    java.time.Duration.ofSeconds(5)
            );
            try {
                LiveTree tree = LiveTree.read(connection);
                assertEquals("main", tree.frames.get(0).id());
                assertEquals("child", tree.frames.get(1).id());
                assertTrue(tree.root.count() > 5, "nodes=" + tree.root.count());
                DomNode host = find(tree.root, "host");
                assertEquals(1, host.shadowRoots.size());
                assertEquals("open", host.shadowRoots.get(0).shadowMode);
                DomNode inner = findTag(host.shadowRoots.get(0), "span");
                assertEquals("span", inner.tag());
                DomNode iframe = find(tree.root, "child");
                assertTrue(iframe.iframe);
                DomNode framed = find(iframe, "framed");
                assertEquals("child", framed.frameId);
                assertEquals("main", host.frameId);
            } finally {
                connection.close();
            }
            assertTrue(cdp.methods.contains("DOM.getDocument"));
            assertTrue(cdp.methods.contains("Page.getFrameTree"));
        }
    }

    @Test
    void snapshotCommandWalksTheAttachedSession() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult snap = DomEngineMain.dispatch(java.util.List.of(
                    "snapshot", "--cdp", cdp.http(), "--i-accept-local-attach"
            ));
            assertEquals(0, snap.exit(), snap.stderr());
            JsonNode json = Jsons.MAPPER.readTree(snap.stdout());
            assertTrue(json.path("nodes").size() > 5, snap.stdout());
            assertEquals("main", json.path("frames").get(0).path("id").asText());
            assertEquals("child", json.path("frames").get(1).path("id").asText());
            assertTrue(snap.stdout().contains("shadow_root"));
        }
    }

    private static DomNode findTag(DomNode node, String tag) {
        if (tag.equals(node.tag())) return node;
        for (DomNode child : node.children) {
            DomNode found = findTag(child, tag);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = findTag(shadow, tag);
            if (found != null) return found;
        }
        return null;
    }

    private static DomNode find(DomNode node, String id) {
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = find(child, id);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = find(shadow, id);
            if (found != null) return found;
        }
        return null;
    }
}

