package tools.dscode.browser;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/** Test socket. {@code sendText} can auto-reply on the same thread before {@code call} waits. */
final class ScriptedSocket implements ProtocolSocket {
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private Consumer<String> listener = text -> {
    };
    private Function<String, String> auto = text -> null;

    ScriptedSocket auto(Function<String, String> auto) {
        this.auto = auto == null ? text -> null : auto;
        return this;
    }

    List<String> sent() {
        return List.copyOf(sent);
    }

    List<String> methods() {
        return sent.stream().map(ScriptedSocket::method).toList();
    }

    void inbound(String text) {
        listener.accept(text);
    }

    @Override
    public void sendText(String text) {
        sent.add(text);
        String reply = auto.apply(text);
        if (reply != null) listener.accept(reply);
    }

    @Override
    public void onText(Consumer<String> listener) {
        this.listener = listener == null ? text -> {
        } : listener;
    }

    @Override
    public void close() {
    }

    static String method(String json) {
        try {
            return JsonRpc.MAPPER.readTree(json).path("method").asText("");
        } catch (Exception failure) {
            return "";
        }
    }

    static long id(String json) {
        try {
            return JsonRpc.MAPPER.readTree(json).path("id").asLong();
        } catch (Exception failure) {
            return -1;
        }
    }

    static JsonNode params(String json) {
        try {
            return JsonRpc.MAPPER.readTree(json).path("params");
        } catch (Exception failure) {
            return JsonRpc.MAPPER.createObjectNode();
        }
    }

    /** CDP success with a JSON result object. */
    static String cdp(String request, String resultJson) {
        return "{\"id\":" + id(request) + ",\"result\":" + resultJson + "}";
    }
}
