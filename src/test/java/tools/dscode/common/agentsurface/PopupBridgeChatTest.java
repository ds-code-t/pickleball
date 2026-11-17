package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chat input over the popup bridge: echo through the ledger, blank refused, reconnect hint on the stream. */
class PopupBridgeChatTest {
    @TempDir
    Path project;

    private static HttpResponse<String> call(PopupBridge bridge, String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.port() + path))
                .header("X-Pkb-Popup-Token", bridge.token())
                .timeout(Duration.ofSeconds(20));
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void blankChatIs400AndStoresNothing() throws Exception {
        try (PopupBridge bridge = PopupBridge.start(project, "blank")) {
            HttpResponse<String> blank = call(bridge, "POST", "/chat", "{\"text\":\"   \"}");
            assertEquals(400, blank.statusCode(), blank.body());
            assertTrue(blank.body().contains("empty"), blank.body());
            assertFalse(Files.exists(project.resolve(".pickleball/runs/blank/agent/chat/run.jsonl")));
            assertTrue(PopupLedger.after(project, "blank", 0).isEmpty());
        }
    }

    @Test
    void chatAppendsAHumanLedgerRowSoThePaneEchoesIt() throws Exception {
        try (PopupBridge bridge = PopupBridge.start(project, "echo")) {
            HttpResponse<String> sent = call(bridge, "POST", "/chat", "{\"text\":\"hello there\"}");
            assertEquals(200, sent.statusCode(), sent.body());
            assertTrue(sent.body().contains("\"id\""), sent.body());
            List<PopupLedger.Row> rows = PopupLedger.after(project, "echo", 0);
            assertEquals(1, rows.size(), rows.toString());
            assertEquals("chat", rows.get(0).op());
            assertEquals("human", rows.get(0).codes());
            assertEquals("hello there", rows.get(0).html());
            assertTrue(Files.readString(project.resolve(".pickleball/runs/echo/agent/chat/run.jsonl")).contains("\"source\":\"human\""));
        }
    }

    @Test
    void eventStreamTellsTheBrowserToReconnectQuicklyAndResumesAfterTheLastId() throws Exception {
        try (PopupBridge bridge = PopupBridge.start(project, "stream")) {
            PopupLedger.append(project, "stream", "chat", "chat", "", "", "one", "", "");
            HttpResponse<String> first = call(bridge, "GET", "/events", null);
            assertTrue(first.body().startsWith("retry: "), first.body());
            assertTrue(first.body().contains("\"seq\":1"), first.body());
            PopupLedger.append(project, "stream", "chat", "chat", "", "", "two", "", "");
            HttpRequest resume = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + bridge.port() + "/events"))
                    .header("X-Pkb-Popup-Token", bridge.token())
                    .header("Last-Event-ID", "1")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            String body = HttpClient.newHttpClient().send(resume, HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(body.contains("\"seq\":2"), body);
            assertFalse(body.contains("\"seq\":1,"), body);
        }
    }
}
