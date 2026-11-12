package dev.domengine.conform;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.cdp.CdpException;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;

import java.nio.file.Files;
import java.nio.file.Path;

/** Conformance entry. Phase 5 fills the case runner. The CLI only forwards here. */
public final class Conformance {
    private Conformance() {
    }

    public static ObjectNode run(Path root) {
        if (root == null || !Files.isDirectory(root)) throw new CdpException("conform root is not a directory");
        ObjectNode node = Jsons.object();
        node.put("ok", true);
        node.put("root", root.toAbsolutePath().normalize().toString());
        node.put("cases", 0);
        node.put("passed", 0);
        node.put("failed", 0);
        EngineLog.info("query", "conform " + root);
        return node;
    }
}
