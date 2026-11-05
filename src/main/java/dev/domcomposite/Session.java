package dev.domcomposite;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One named browser session owned by a {@link Client}. Not a singleton.
 * Closing it asks the CLI to close that id and does not stop the sidecar.
 */
public final class Session implements AutoCloseable {
    private final Client client;
    private final String id;
    private final JsonNode record;
    private volatile boolean closed;

    Session(Client client, String id, JsonNode record) {
        this.client = client;
        this.id = id;
        this.record = record;
    }

    public String id() {
        return id;
    }

    /** The JSON object returned by {@code session create}. */
    public JsonNode record() {
        return record;
    }

    public boolean isClosed() {
        return closed;
    }

    public JsonNode click(Object body) {
        return client.command("click", client.forceSession(body, id));
    }

    public JsonNode type(Object body) {
        return client.command("type", client.forceSession(body, id));
    }

    public JsonNode scroll(Object body) {
        return client.command("scroll", client.forceSession(body, id));
    }

    public JsonNode submit(Object body) {
        return client.command("submit", client.forceSession(body, id));
    }

    public JsonNode key(Object body) {
        return client.command("key", client.forceSession(body, id));
    }

    public JsonNode dblclick(Object body) {
        return client.command("dblclick", client.forceSession(body, id));
    }

    public JsonNode contextclick(Object body) {
        return client.command("contextclick", client.forceSession(body, id));
    }

    public JsonNode drag(Object body) {
        return client.command("drag", client.forceSession(body, id));
    }

    public JsonNode eval(Object body) {
        return client.command("eval", client.forceSession(body, id));
    }

    public JsonNode query(Object body) {
        return client.command("query", client.forceSession(body, id));
    }

    public JsonNode resolve(Object body) {
        return client.command("resolve", client.forceSession(body, id));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        client.closeSession(id);
    }

    void markClosed() {
        closed = true;
    }
}
