package tools.dscode.workbench.ui.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapEditorEmbedResourceTest {
    @Test
    void workbenchEmbedUsesClassicScriptsJavaFxWebViewCanRun() throws IOException {
        try (var htmlStream = WebViewPanel.class.getResourceAsStream(
                "/tools/dscode/workbench/ui/web/snap-editor/embed.html"
        );
             var jsStream = WebViewPanel.class.getResourceAsStream(
                     "/tools/dscode/workbench/ui/web/snap-editor/snap-editor.js"
             )) {
            assertNotNull(htmlStream, "Missing snap-editor embed.html");
            assertNotNull(jsStream, "Missing snap-editor.js IIFE bundle");
            String html = new String(htmlStream.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(html.contains("type=\"module\""), html);
            assertFalse(html.contains("crossorigin"), html);
            assertTrue(html.contains("snap-editor.js"));
            assertTrue(html.contains("snap-editor.css"));
        }
    }
}