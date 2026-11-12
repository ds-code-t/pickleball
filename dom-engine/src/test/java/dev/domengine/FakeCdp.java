package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.json.Jsons;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tiny DevTools stand-in: HTTP {@code /json/version} and {@code /json/list}, plus a page WebSocket. */
public final class FakeCdp implements AutoCloseable {
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final String PNG_1X1 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    public final int port;
    public final List<String> methods = new CopyOnWriteArrayList<>();
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ObjectNode document;
    private final ObjectNode frameTree;

    private FakeCdp(ServerSocket server, ObjectNode document, ObjectNode frameTree) {
        this.server = server;
        this.port = server.getLocalPort();
        this.document = document;
        this.frameTree = frameTree;
    }

    public static FakeCdp start() throws IOException {
        return start(sampleDocument(), sampleFrames());
    }

    public static FakeCdp start(ObjectNode document, ObjectNode frameTree) throws IOException {
        ServerSocket server = new ServerSocket(0);
        FakeCdp fake = new FakeCdp(server, document, frameTree);
        Thread thread = new Thread(() -> fake.acceptLoop(), "fake-cdp");
        thread.setDaemon(true);
        thread.start();
        return fake;
    }

    public String http() {
        return "http://127.0.0.1:" + port;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = server.accept();
                pool.submit(() -> handle(socket));
            } catch (IOException failure) {
                if (running.get()) return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String headers = readHeaders(in);
            String path = pathOf(headers);
            if (headers.toLowerCase().contains("upgrade: websocket")) {
                handshake(headers, out);
                cdp(in, out);
                return;
            }
            byte[] body = body(path);
            String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Exception ignored) {
            // The client closed the probe socket.
        }
    }

    private byte[] body(String path) throws IOException {
        if (path.startsWith("/json/list")) {
            ObjectNode page = Jsons.object();
            page.put("id", "PAGE1");
            page.put("type", "page");
            page.put("url", "http://fixture/");
            page.put("webSocketDebuggerUrl", "ws://127.0.0.1:" + port + "/devtools/page/PAGE1");
            return Jsons.MAPPER.writeValueAsBytes(Jsons.MAPPER.createArrayNode().add(page));
        }
        ObjectNode version = Jsons.object();
        version.put("Browser", "Fake/1");
        version.put("webSocketDebuggerUrl", "ws://127.0.0.1:" + port + "/devtools/browser/BR");
        return Jsons.MAPPER.writeValueAsBytes(version);
    }

    private void handshake(String headers, OutputStream out) throws Exception {
        String key = "";
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
        String response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void cdp(InputStream in, OutputStream out) throws IOException {
        while (running.get()) {
            String text = readFrame(in);
            if (text == null) return;
            JsonNode request;
            try {
                request = Jsons.MAPPER.readTree(text);
            } catch (Exception failure) {
                continue;
            }
            if (!request.has("method")) continue;
            String method = request.path("method").asText();
            methods.add(method);
            ObjectNode response = Jsons.object();
            response.put("id", request.path("id").asLong());
            ObjectNode result = response.putObject("result");
            if ("DOM.getDocument".equals(method)) result.set("root", document);
            else if ("Page.getFrameTree".equals(method)) result.set("frameTree", frameTree);
            else if ("Browser.getVersion".equals(method)) result.put("product", "Fake/1");
            else if ("DOM.getBoxModel".equals(method)) {
                ObjectNode model = result.putObject("model");
                var border = model.putArray("border");
                for (double value : new double[] {10, 20, 90, 20, 90, 50, 10, 50}) border.add(value);
            } else if ("DOM.resolveNode".equals(method)) {
                result.putObject("object").put("objectId", "obj-1");
            } else if ("Runtime.callFunctionOn".equals(method) || "Runtime.evaluate".equals(method)) {
                ObjectNode value = result.putObject("result");
                value.put("type", "string");
                value.put("value", "BUTTON");
            } else if ("Page.captureScreenshot".equals(method)) {
                result.put("data", PNG_1X1);
            } else if ("Input.dispatchMouseEvent".equals(method) || "Input.insertText".equals(method) || "Input.dispatchKeyEvent".equals(method)) {
                result.put("ok", true);
            }
            writeFrame(out, Jsons.compact(response));
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int value = in.read();
            if (value < 0) break;
            buffer.write(value);
            if (buffer.size() >= 4) {
                byte[] bytes = buffer.toByteArray();
                int n = bytes.length;
                if (bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') break;
            }
            if (buffer.size() > 65536) break;
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private static String pathOf(String headers) {
        int space = headers.indexOf(' ');
        if (space < 0) return "/";
        int next = headers.indexOf(' ', space + 1);
        if (next < 0) return "/";
        return headers.substring(space + 1, next);
    }

    private static String readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) return null;
        int opcode = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long length = b1 & 0x7f;
        if (length == 126) length = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
        else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) length = (length << 8) | (in.read() & 0xff);
        }
        byte[] mask = new byte[4];
        if (masked) {
            if (in.read(mask) != 4) return null;
        }
        byte[] payload = in.readNBytes((int) length);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        if (opcode == 0x8) return null;
        if (opcode == 0x9) return "";
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void writeFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        if (payload.length < 126) out.write(payload.length);
        else if (payload.length < 65536) {
            out.write(126);
            out.write((payload.length >> 8) & 0xff);
            out.write(payload.length & 0xff);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((int) ((payload.length >> (8 * i)) & 0xff));
        }
        out.write(payload);
        out.flush();
    }

    public static ObjectNode sampleDocument() {
        ObjectNode button = element("BUTTON", 4, "button", "id", "go");
        ObjectNode shadowChild = element("SPAN", 6, "span", "class", "inner");
        ObjectNode shadow = Jsons.object();
        shadow.put("nodeId", 5);
        shadow.put("backendNodeId", 5);
        shadow.put("nodeType", 11);
        shadow.put("nodeName", "#document-fragment");
        shadow.put("localName", "");
        shadow.put("nodeValue", "");
        shadow.put("shadowRootType", "open");
        shadow.putArray("children").add(shadowChild);
        ObjectNode host = element("DIV", 3, "div", "id", "host");
        host.putArray("shadowRoots").add(shadow);
        ObjectNode frameBody = element("P", 9, "p", "id", "framed");
        ObjectNode frameDoc = document(8, frameBody);
        ObjectNode iframe = element("IFRAME", 7, "iframe", "id", "child");
        iframe.putArray("children").add(frameDoc);
        ObjectNode body = element("BODY", 2, "body");
        body.putArray("children").add(host).add(button).add(iframe);
        return document(1, body);
    }

    public static ObjectNode sampleFrames() {
        ObjectNode child = Jsons.object();
        ObjectNode childFrame = child.putObject("frame");
        childFrame.put("id", "child");
        childFrame.put("parentId", "main");
        childFrame.put("url", "http://fixture/frame.html");
        ObjectNode tree = Jsons.object();
        ObjectNode frame = tree.putObject("frame");
        frame.put("id", "main");
        frame.put("url", "http://fixture/");
        tree.putArray("childFrames").add(child);
        return tree;
    }

    private static ObjectNode document(long id, ObjectNode body) {
        ObjectNode html = element("HTML", id + 10, "html");
        html.putArray("children").add(body);
        ObjectNode root = Jsons.object();
        root.put("nodeId", id);
        root.put("backendNodeId", id);
        root.put("nodeType", 9);
        root.put("nodeName", "#document");
        root.put("localName", "");
        root.put("nodeValue", "");
        root.putArray("children").add(html);
        return root;
    }

    private static ObjectNode element(String name, long id, String local, String... attrs) {
        ObjectNode node = Jsons.object();
        node.put("nodeId", id);
        node.put("backendNodeId", id);
        node.put("nodeType", 1);
        node.put("nodeName", name);
        node.put("localName", local);
        node.put("nodeValue", "");
        var array = node.putArray("attributes");
        for (String attr : attrs) array.add(attr);
        return node;
    }

    @Override
    public void close() {
        running.set(false);
        try {
            server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }
}
