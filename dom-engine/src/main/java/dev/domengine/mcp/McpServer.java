package dev.domengine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.EngineInfo;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP stdio server. Every tool call forwards an argv list to {@link DomEngineMain}.
 * Workbench and hosts do not reimplement the engine here.
 */
public final class McpServer {
    private McpServer() {
    }

    public static void serve(InputStream in, OutputStream out) {
        EngineLog.info("serve", "mcp");
        try {
            byte[] raw = in.readAllBytes();
            for (byte[] message : frames(raw)) {
                if (message.length == 0) continue;
                JsonNode request;
                try {
                    request = Jsons.MAPPER.readTree(message);
                } catch (Exception failure) {
                    write(out, error(null, -32700, "parse error"));
                    continue;
                }
                JsonNode id = request.get("id");
                String method = request.path("method").asText("");
                if (id == null || id.isNull()) continue;
                JsonNode result = switch (method) {
                    case "initialize" -> initialize();
                    case "tools/list" -> tools();
                    case "tools/call" -> call(request.path("params"));
                    default -> null;
                };
                if (result == null) write(out, error(id, -32601, "unknown method " + method));
                else write(out, ok(id, result));
            }
        } catch (Exception failure) {
            EngineLog.error("serve", "mcp " + failure.getMessage());
        }
    }

    private static ObjectNode initialize() {
        ObjectNode result = Jsons.object();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", EngineInfo.NAME);
        info.put("version", EngineInfo.VERSION);
        return result;
    }

    private static ObjectNode tools() {
        ObjectNode result = Jsons.object();
        ObjectNode tool = result.putArray("tools").addObject();
        tool.put("name", "domengine");
        tool.put("description", "Forward an argv list to the domengine CLI");
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties").putObject("argv").put("type", "array");
        return result;
    }

    private static ObjectNode call(JsonNode params) {
        String name = params.path("name").asText("");
        if (!"domengine".equals(name)) throw new IllegalArgumentException("unknown tool " + name);
        List<String> argv = new ArrayList<>();
        JsonNode raw = params.path("arguments").get("argv");
        if (raw != null && raw.isArray()) {
            for (JsonNode item : raw) argv.add(item.asText(""));
        }
        CliResult result = DomEngineMain.dispatch(argv);
        ObjectNode body = Jsons.object();
        ObjectNode content = body.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", result.exit() == 0 ? result.stdout() : result.stderr());
        if (result.exit() != 0) body.put("isError", true);
        return body;
    }

    private static ObjectNode ok(JsonNode id, JsonNode result) {
        ObjectNode node = Jsons.object();
        node.put("jsonrpc", "2.0");
        node.set("id", id);
        node.set("result", result);
        return node;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode node = Jsons.object();
        node.put("jsonrpc", "2.0");
        if (id == null) node.putNull("id");
        else node.set("id", id);
        ObjectNode error = node.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return node;
    }

    private static void write(OutputStream out, ObjectNode node) throws java.io.IOException {
        byte[] payload = Jsons.compact(node).getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + payload.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    static List<byte[]> frames(byte[] raw) {
        List<byte[]> messages = new ArrayList<>();
        int cursor = 0;
        while (cursor < raw.length) {
            while (cursor < raw.length && (raw[cursor] == '\n' || raw[cursor] == '\r' || raw[cursor] == ' ')) cursor++;
            if (cursor >= raw.length) break;
            if (raw[cursor] == '{') {
                int end = lineEnd(raw, cursor);
                messages.add(slice(raw, cursor, end));
                cursor = end;
                continue;
            }
            int headerEnd = indexOf(raw, cursor, "\r\n\r\n");
            if (headerEnd < 0) break;
            String header = new String(raw, cursor, headerEnd - cursor, StandardCharsets.US_ASCII);
            int length = contentLength(header);
            int start = headerEnd + 4;
            if (length < 0 || start + length > raw.length) break;
            messages.add(slice(raw, start, start + length));
            cursor = start + length;
        }
        return messages;
    }

    private static int contentLength(String header) {
        for (String line : header.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        return -1;
    }

    private static int lineEnd(byte[] raw, int start) {
        for (int i = start; i < raw.length; i++) if (raw[i] == '\n') return i;
        return raw.length;
    }

    private static int indexOf(byte[] raw, int start, String needle) {
        byte[] token = needle.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = start; i + token.length <= raw.length; i++) {
            for (int j = 0; j < token.length; j++) if (raw[i + j] != token[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static byte[] slice(byte[] raw, int start, int end) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(raw, start, Math.max(0, end - start));
        return buffer.toByteArray();
    }
}
