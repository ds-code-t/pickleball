package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E1: BiDi module request shapes against a fake socket. */
class BidiModulesTest {
    private static String success(String request) {
        return "{\"type\":\"success\",\"id\":" + ScriptedSocket.id(request) + ",\"result\":{}}";
    }

    @Test
    void requestShapesFollowTheSpec() throws Exception {
        ScriptedSocket socket = new ScriptedSocket().auto(BidiModulesTest::success);
        BidiModules bidi = new BidiAdapter(socket, "ctx").modules();
        bidi.subscribe(List.of("network.beforeRequestSent"), null);
        bidi.navigate("ctx", "https://example.test/", null);
        bidi.setViewport("ctx", 800, 600, 2.0);
        bidi.locateByRole("ctx", "button", "Submit", 5);
        bidi.addIntercept(List.of("beforeRequestSent"), List.of(Map.of("type", "string", "pattern", "https://example.test/api")), null);
        bidi.provideResponse("r1", 200, List.of(Map.of("name", "content-type", "value", Map.of("type", "string", "value", "text/plain"))), "mocked");
        bidi.continueWithAuth("r2", "user", "pw");
        bidi.setExtraHeaders(List.of(Map.of("name", "x-pkb", "value", Map.of("type", "string", "value", "1"))), List.of("ctx"));
        bidi.addDataCollector(List.of("response"), 262144);
        bidi.setFiles("ctx", Map.of("sharedId", "n1"), List.of("/tmp/a.txt"));
        bidi.setCookie(Map.of("name", "a", "value", Map.of("type", "string", "value", "b"), "domain", "example.test"), null);
        bidi.createUserContext();
        bidi.setDownloadBehavior("/tmp/dl", false);
        bidi.setGeolocationOverride(List.of("ctx"), 1.5, 2.5, null);
        bidi.setLocaleOverride(List.of("ctx"), "de-DE");
        bidi.setTimezoneOverride(List.of("ctx"), "Asia/Tokyo");
        bidi.setNetworkConditions(List.of("ctx"), true);
        bidi.installExtension("/tmp/ext");
        List<String> methods = socket.methods();
        assertEquals(List.of("session.subscribe", "browsingContext.navigate", "browsingContext.setViewport", "browsingContext.locateNodes",
                "network.addIntercept", "network.provideResponse", "network.continueWithAuth", "network.setExtraHeaders",
                "network.addDataCollector", "input.setFiles", "storage.setCookie", "browser.createUserContext",
                "browser.setDownloadBehavior", "emulation.setGeolocationOverride", "emulation.setLocaleOverride",
                "emulation.setTimezoneOverride", "emulation.setNetworkConditions", "webExtension.install"), methods);
        List<String> sent = socket.sent();
        JsonNode viewport = ScriptedSocket.params(sent.get(2));
        assertEquals(800, viewport.path("viewport").path("width").asInt());
        assertEquals(2.0, viewport.path("devicePixelRatio").asDouble());
        JsonNode locate = ScriptedSocket.params(sent.get(3));
        assertEquals("accessibility", locate.path("locator").path("type").asText());
        assertEquals("Submit", locate.path("locator").path("value").path("name").asText());
        JsonNode provide = ScriptedSocket.params(sent.get(5));
        assertEquals("mocked", provide.path("body").path("value").asText());
        assertEquals(200, provide.path("statusCode").asInt());
        assertEquals("provideCredentials", ScriptedSocket.params(sent.get(6)).path("action").asText());
        assertEquals("allowed", ScriptedSocket.params(sent.get(12)).path("downloadBehavior").path("type").asText());
        assertEquals("/tmp/dl", ScriptedSocket.params(sent.get(12)).path("downloadBehavior").path("destinationFolder").asText());
        assertEquals("offline", ScriptedSocket.params(sent.get(16)).path("networkConditions").path("type").asText());
        assertTrue(sent.get(1).contains("\"wait\":\"complete\""), sent.get(1));
    }

    @Test
    void unknownCommandBecomesUnsupported() {
        ScriptedSocket socket = new ScriptedSocket().auto(request -> "{\"type\":\"error\",\"id\":" + ScriptedSocket.id(request)
                + ",\"error\":\"unknown command\",\"message\":\"emulation.setTouchOverride\"}");
        BidiModules bidi = new BidiAdapter(socket, "ctx").modules();
        UnsupportedProtocolOperation unsupported = assertThrows(UnsupportedProtocolOperation.class,
                () -> bidi.setTouchOverride(List.of("ctx"), 5));
        assertEquals("bidi", unsupported.backend());
        assertTrue(unsupported.getMessage().startsWith("error: unsupported: emulation.setTouchOverride on bidi"), unsupported.getMessage());
    }

    @Test
    void rpcChannelDeliversEventsOffTheReaderThreadAndMatchesReplies() throws Exception {
        ScriptedSocket socket = new ScriptedSocket();
        RpcChannel channel = new RpcChannel(socket, RpcChannel.Dialect.CDP).timeoutMs(2000);
        java.util.concurrent.CompletableFuture<String> seen = new java.util.concurrent.CompletableFuture<>();
        channel.listen((method, params, session) -> {
            // A listener may call back into the channel: the reply arrives on the socket thread.
            socket.auto(request -> ScriptedSocket.cdp(request, "{\"ok\":true}"));
            JsonNode answer = channel.call("Fetch.continueRequest", Map.of("requestId", params.path("requestId").asText()), session);
            seen.complete(method + ":" + session + ":" + answer.path("ok").asBoolean());
        });
        socket.inbound("{\"method\":\"Fetch.requestPaused\",\"params\":{\"requestId\":\"r9\"},\"sessionId\":\"S1\"}");
        assertEquals("Fetch.requestPaused:S1:true", seen.get(3, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(socket.sent().get(0).contains("\"sessionId\":\"S1\""), socket.sent().toString());
        channel.close();
    }
}
