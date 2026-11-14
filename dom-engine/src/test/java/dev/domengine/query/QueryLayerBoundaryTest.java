package dev.domengine.query;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryLayerBoundaryTest {
    @Test
    void syntaxFilesStayOffProtocolTypes() throws Exception {
        Path dir = Path.of("dom-engine/src/main/java/dev/domengine/query");
        if (!Files.isDirectory(dir)) dir = Path.of("src/main/java/dev/domengine/query");
        assertTrue(Files.isDirectory(dir), dir.toString());
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                String source = Files.readString(file);
                assertTrue(allowed(name, source), name + " imports a protocol type");
            }
        }
        String engines = Files.readString(dir.resolve("Engines.java"));
        assertTrue(connectionOnlyInsideXPath(engines), "CdpConnection escaped the xpath method");
    }

    private static boolean allowed(String name, String source) {
        if (source.contains("org.openqa") || source.contains("com.microsoft.playwright")
                || source.contains("java.net.http") || source.contains("java.net.Socket")) return false;
        if (source.contains("dev.domengine.session") || source.contains("dev.domengine.action")
                || source.contains("dev.domengine.desktop")) return false;
        boolean hooks = "QueryHooks.java".equals(name);
        if (!hooks && (source.contains("dev.domengine.premap") || source.contains("dev.domengine.affinity"))) return false;
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ") && !trimmed.contains("dev.domengine.cdp.")) continue;
            if (trimmed.contains("CdpException")) continue;
            if (!trimmed.contains("dev.domengine.cdp.")) continue;
            boolean connection = "LiveXPath.java".equals(name) || "Engines.java".equals(name)
                    || "Displayed.java".equals(name) || "RowStates.java".equals(name);
            if (!connection) return false;
        }
        if (!"LiveXPath.java".equals(name) && !"Engines.java".equals(name)
                && !"Displayed.java".equals(name) && !"RowStates.java".equals(name)
                && source.contains("CdpConnection")) return false;
        return true;
    }

    private static boolean connectionOnlyInsideXPath(String source) {
        int method = source.indexOf("static Query.Hit xpath");
        int next = source.indexOf("static ", method + 10);
        String body = source.substring(method, next);
        String withoutImport = source.replaceFirst("import dev\\.domengine\\.cdp\\.CdpConnection;", "");
        int uses = 0;
        Matcher matcher = Pattern.compile("CdpConnection").matcher(withoutImport);
        while (matcher.find()) uses++;
        int inBody = 0;
        Matcher inner = Pattern.compile("CdpConnection").matcher(body);
        while (inner.find()) inBody++;
        return uses == inBody && inBody >= 1;
    }
}
