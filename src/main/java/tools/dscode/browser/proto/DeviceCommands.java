package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Credentials;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.VirtualCredential;
import com.microsoft.playwright.options.WebMCPTool;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.BrowserProtocols.Backend;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E8: virtual WebAuthn authenticators and credentials ({@code webauthn}), browser extensions ({@code extension}) and
 * WebMCP page tools ({@code webmcp}). Private keys are never printed or logged; replies list ids, rpIds and counts.
 */
final class DeviceCommands {
    private DeviceCommands() {
    }

    static void register() {
        ProtoHost.register("webauthn", DeviceCommands::webauthn);
        ProtoHost.register("extension", DeviceCommands::extension);
        ProtoHost.register("webmcp", DeviceCommands::webmcp);
    }

    // ---- webauthn ----

    static ObjectNode webauthn(ProtoHost host, Map<String, String> f) throws Exception {
        String mode = f.containsKey("add-authenticator") ? "add-authenticator" : f.containsKey("add-credential") ? "add-credential"
                : f.containsKey("list") ? "list" : f.containsKey("remove") ? "remove" : null;
        if (mode == null) throw new IllegalArgumentException("error: webauthn needs --add-authenticator, --add-credential=@cred.json, --list or --remove\nfix: webauthn --add-authenticator --resident --uv");
        String protocol = f.getOrDefault("protocol", "ctap2");
        if (!protocol.equals("ctap2") && !protocol.equals("u2f")) throw new IllegalArgumentException("error: --protocol is ctap2 or u2f\nfix: webauthn --add-authenticator --protocol=ctap2");
        JsonNode cred = null;
        if ("add-credential".equals(mode)) {
            String raw = f.get("add-credential");
            if (raw == null || raw.isBlank() || "true".equals(raw)) throw new IllegalArgumentException("error: --add-credential needs <json>|@cred.json\nfix: webauthn --add-credential=@cred.json (rpId, id, userHandle; privateKey from --private-key=<secret-ref> or privateKeyFile)");
            if (raw.startsWith("@")) raw = Files.readString(host.project().resolve(raw.substring(1)).normalize());
            cred = ProtoJson.MAPPER.readTree(raw);
            if (cred.path("rpId").asText().isBlank()) throw new IllegalArgumentException("error: the credential needs rpId\nfix: {\"rpId\":\"example.com\",\"id\":\"<base64url>\"}");
        }
        String key = privateKey(host, f, cred);
        String via = f.get("via");
        // Playwright's authenticator is ctap2 with resident keys and user verification; other options need CDP.
        if ("add-authenticator".equals(mode) && (protocol.equals("u2f") || "false".equals(f.get("resident")) || "false".equals(f.get("uv")))) {
            via = RouteCommands.skip(host, "webauthn", via, Backend.PLAYWRIGHT);
        }
        JsonNode credential = cred;
        String viaUsed = via;
        BrowserProtocols.Served<ObjectNode> served = host.protocols().serve("webauthn", viaUsed, backend -> switch (backend) {
            case PLAYWRIGHT -> host.pw().call(c -> webauthnPw(PwBridge.page(c, f.get("page")).context().credentials(), mode, f, credential, key));
            case CDP -> webauthnCdp(host, ContextCommands.target(host, f), mode, f, protocol, credential, key);
            default -> throw new UnsupportedProtocolOperation("webauthn", backend.id(), null);
        });
        host.log("webauthn " + mode + " via=" + served.via().id());
        return served.value().put("op", "webauthn").put("mode", mode).put("via", served.via().id());
    }

    /** The private key (base64 PKCS#8): --private-key=<secret-ref> resolved by the CLI, else privateKeyFile in the JSON. */
    static String privateKey(ProtoHost host, Map<String, String> f, JsonNode cred) throws Exception {
        if (f.get("_credential") != null) return f.get("_credential");
        if (cred == null) return null;
        if (cred.has("privateKey")) {
            throw new IllegalArgumentException("error: put the private key in a file or a secret, not in the credential JSON\nfix: privateKeyFile=<path> or --private-key=env:NAME");
        }
        if (cred.hasNonNull("privateKeyFile")) {
            Path file = host.project().resolve(cred.get("privateKeyFile").asText()).normalize();
            String pem = Files.readString(file).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return pem;
        }
        return null;
    }

    static String generatedKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception failure) {
            throw new IllegalStateException("error: could not make a P-256 key: " + failure.getMessage() + "\nfix: pass --private-key=<secret-ref>");
        }
    }

    static ArrayNode list(List<Map<String, Object>> rows) {
        ArrayNode out = ProtoJson.MAPPER.createArrayNode();
        for (Map<String, Object> row : rows) {
            ObjectNode r = out.addObject();
            for (Map.Entry<String, Object> e : row.entrySet()) if (e.getValue() != null) r.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }

    static ObjectNode webauthnPw(Credentials credentials, String mode, Map<String, String> f, JsonNode cred, String key) {
        ObjectNode out = ProtoJson.obj();
        switch (mode) {
            case "add-authenticator" -> {
                credentials.install();
                out.put("authenticator", "playwright").put("protocol", "ctap2").put("resident", true).put("uv", true);
            }
            case "add-credential" -> {
                credentials.install();
                Credentials.CreateOptions options = new Credentials.CreateOptions();
                if (cred.hasNonNull("id")) options.setId(cred.get("id").asText());
                if (cred.hasNonNull("userHandle")) options.setUserHandle(cred.get("userHandle").asText());
                if (cred.hasNonNull("signCount")) options.setSignCount(cred.get("signCount").asInt());
                if (key != null) options.setPrivateKey(key);
                VirtualCredential made = credentials.create(cred.get("rpId").asText(), options);
                out.put("id", made.id).put("rpId", made.rpId);
            }
            case "list" -> {
                List<Map<String, Object>> rows = new java.util.ArrayList<>();
                for (VirtualCredential c : credentials.get()) rows.add(row(c.id, c.rpId, c.userHandle, c.signCount, true));
                out.set("credentials", list(rows));
                out.put("count", rows.size());
            }
            case "remove" -> {
                int removed = 0;
                for (VirtualCredential c : credentials.get()) {
                    if (f.get("id") != null && !f.get("id").equals(c.id)) continue;
                    credentials.delete(c.id);
                    removed++;
                }
                out.put("removed", removed);
            }
            default -> throw new IllegalArgumentException(mode);
        }
        return out;
    }

    static Map<String, Object> row(String id, String rpId, String userHandle, int signCount, boolean resident) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("rpId", rpId);
        r.put("userHandle", userHandle);
        r.put("signCount", signCount);
        r.put("resident", resident);
        return r;
    }

    static String authenticator(ProtoHost host, CdpSession.Target t, Map<String, String> f, String protocol, boolean create) {
        String key = "webauthn.cdp." + t.sessionId;
        Object id = host.stateMap().get(key);
        if (id != null && !create) return String.valueOf(id);
        if (id != null) return String.valueOf(id);
        host.cdp().send(t.sessionId, "WebAuthn.enable", ContextCommands.p("enableUI", false));
        boolean resident = !"false".equals(f.get("resident"));
        boolean uv = !"false".equals(f.get("uv"));
        JsonNode made = host.cdp().send(t.sessionId, "WebAuthn.addVirtualAuthenticator", ContextCommands.p("options", ContextCommands.p(
                "protocol", protocol, "ctap2Version", "ctap2".equals(protocol) ? "ctap2_1" : null, "transport", "ctap2".equals(protocol) ? "internal" : "usb",
                "hasResidentKey", resident, "hasUserVerification", uv, "isUserVerified", uv, "automaticPresenceSimulation", true)));
        String made_id = made.path("authenticatorId").asText();
        host.stateMap().put(key, made_id);
        host.onStop(() -> {
            try {
                host.cdp().send(t.sessionId, "WebAuthn.removeVirtualAuthenticator", ContextCommands.p("authenticatorId", made_id));
            } catch (RuntimeException gone) {
                // Tab closed.
            }
        });
        return made_id;
    }

    static ObjectNode webauthnCdp(ProtoHost host, CdpSession.Target t, String mode, Map<String, String> f, String protocol, JsonNode cred, String key) {
        ObjectNode out = ProtoJson.obj();
        switch (mode) {
            case "add-authenticator" -> {
                String id = authenticator(host, t, f, protocol, true);
                out.put("authenticator", id).put("protocol", protocol).put("resident", !"false".equals(f.get("resident"))).put("uv", !"false".equals(f.get("uv")));
            }
            case "add-credential" -> {
                String auth = authenticator(host, t, f, protocol, false);
                String id = cred.hasNonNull("id") ? cred.get("id").asText()
                        : Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.UUID.randomUUID().toString().getBytes());
                Map<String, Object> c = new LinkedHashMap<>(ContextCommands.p("credentialId", toStd(id), "isResidentCredential", cred.path("resident").asBoolean(true),
                        "rpId", cred.get("rpId").asText(), "privateKey", key == null ? generatedKey() : key, "signCount", cred.path("signCount").asInt(0)));
                if (cred.hasNonNull("userHandle")) c.put("userHandle", toStd(cred.get("userHandle").asText()));
                host.cdp().send(t.sessionId, "WebAuthn.addCredential", ContextCommands.p("authenticatorId", auth, "credential", c));
                out.put("id", id).put("rpId", cred.get("rpId").asText()).put("authenticator", auth);
            }
            case "list" -> {
                Object auth = host.stateMap().get("webauthn.cdp." + t.sessionId);
                List<Map<String, Object>> rows = new java.util.ArrayList<>();
                if (auth != null) {
                    for (JsonNode c : host.cdp().send(t.sessionId, "WebAuthn.getCredentials", ContextCommands.p("authenticatorId", auth)).path("credentials")) {
                        rows.add(row(toUrl(c.path("credentialId").asText()), c.path("rpId").asText(), c.path("userHandle").asText(null), c.path("signCount").asInt(), c.path("isResidentCredential").asBoolean()));
                    }
                }
                out.set("credentials", list(rows));
                out.put("count", rows.size());
                if (auth != null) out.put("authenticator", String.valueOf(auth));
            }
            case "remove" -> {
                Object auth = host.stateMap().get("webauthn.cdp." + t.sessionId);
                if (auth == null) {
                    out.put("removed", 0);
                } else if (f.get("id") != null) {
                    host.cdp().send(t.sessionId, "WebAuthn.removeCredential", ContextCommands.p("authenticatorId", auth, "credentialId", toStd(f.get("id"))));
                    out.put("removed", 1);
                } else {
                    host.cdp().send(t.sessionId, "WebAuthn.removeVirtualAuthenticator", ContextCommands.p("authenticatorId", auth));
                    host.stateMap().remove("webauthn.cdp." + t.sessionId);
                    out.put("removed", "authenticator");
                }
            }
            default -> throw new IllegalArgumentException(mode);
        }
        return out;
    }

    /** CDP takes standard base64; pages and replies use base64url. */
    static String toStd(String b64url) {
        String s = b64url.replace('-', '+').replace('_', '/');
        while (s.length() % 4 != 0) s += "=";
        return s;
    }

    static String toUrl(String b64) {
        return b64.replace('+', '-').replace('/', '_').replace("=", "");
    }

    // ---- extensions ----

    static ObjectNode extension(ProtoHost host, Map<String, String> f) {
        String mode = f.containsKey("install") ? "install" : f.containsKey("uninstall") ? "uninstall" : f.containsKey("list") ? "list" : null;
        if (mode == null) throw new IllegalArgumentException("error: extension needs --install=<dir>, --uninstall=<id> or --list\nfix: extension --install=extensions/my-ext");
        String via = f.get("via");
        // BiDi has no list command.
        if ("list".equals(mode)) via = RouteCommands.skip(host, "extension", via, Backend.BIDI);
        String path = null;
        if ("install".equals(mode)) {
            String raw = f.get("install");
            if (raw == null || raw.isBlank() || "true".equals(raw)) throw new IllegalArgumentException("error: --install needs a folder\nfix: extension --install=<unpacked dir>");
            Path dir = host.project().resolve(raw).normalize().toAbsolutePath();
            if (!Files.isRegularFile(dir.resolve("manifest.json"))) throw new IllegalArgumentException("error: " + raw + " has no manifest.json\nfix: pass an unpacked extension folder");
            path = dir.toString();
        }
        String dir = path;
        BrowserProtocols.Served<ObjectNode> served = host.protocols().serve("extension", via, backend -> {
            ObjectNode out = ProtoJson.obj();
            try {
                switch (backend) {
                    case BIDI -> {
                        if ("install".equals(mode)) {
                            JsonNode r = host.bidiModules().send("webExtension.install", ContextCommands.p("extensionData", ContextCommands.p("type", "path", "path", dir)));
                            out.put("id", r.path("extension").asText());
                        } else {
                            host.bidiModules().send("webExtension.uninstall", ContextCommands.p("extension", f.get("uninstall")));
                            out.put("id", f.get("uninstall"));
                        }
                    }
                    case CDP -> {
                        switch (mode) {
                            case "install" -> out.put("id", host.cdp().browser("Extensions.loadUnpacked", ContextCommands.p("path", dir)).path("id").asText());
                            case "uninstall" -> {
                                host.cdp().browser("Extensions.uninstall", ContextCommands.p("id", f.get("uninstall")));
                                out.put("id", f.get("uninstall"));
                            }
                            default -> {
                                ArrayNode list = out.putArray("extensions");
                                for (JsonNode e : host.cdp().browser("Extensions.getExtensions", Map.of()).path("extensions")) {
                                    list.addObject().put("id", e.path("id").asText()).put("name", e.path("name").asText())
                                            .put("version", e.path("version").asText()).put("enabled", e.path("enabled").asBoolean());
                                }
                                out.put("count", list.size());
                            }
                        }
                    }
                    default -> throw new UnsupportedProtocolOperation("extension", backend.id(), null);
                }
            } catch (IllegalStateException failure) {
                String msg = String.valueOf(failure.getMessage());
                if (msg.contains("not available") || msg.contains("not allowed") || msg.contains("wasn't found") || msg.contains("not supported")) {
                    throw new UnsupportedProtocolOperation("extension", backend.id(),
                            msg.lines().findFirst().orElse("").replaceFirst("^error: ", "") + "; start the browser with --enable-unsafe-extension-debugging");
                }
                throw failure;
            }
            return out;
        });
        host.log("extension " + mode + " via=" + served.via().id());
        return served.value().put("op", "extension").put("mode", mode).put("via", served.via().id());
    }

    // ---- WebMCP ----

    static ObjectNode webmcp(ProtoHost host, Map<String, String> f) throws Exception {
        boolean list = !f.containsKey("call");
        String params = f.get("params");
        if (params != null && params.startsWith("@")) params = Files.readString(host.project().resolve(params.substring(1)).normalize());
        Object args = params == null || params.isBlank() ? null : ProtoJson.MAPPER.readValue(params, Object.class);
        BrowserProtocols.Served<ObjectNode> served = host.protocols().serve("webmcp", f.get("via"), backend -> {
            ObjectNode out = ProtoJson.obj();
            switch (backend) {
                case PLAYWRIGHT -> host.pw().call(c -> {
                    Page page = PwBridge.page(c, f.get("page"));
                    try {
                        if (list) {
                            ArrayNode tools = out.putArray("tools");
                            for (WebMCPTool tool : page.webmcp().tools(new com.microsoft.playwright.WebMCP.ToolsOptions().setTimeout(5000))) {
                                tools.addObject().put("name", tool.name).put("description", tool.description);
                            }
                            out.put("count", tools.size());
                        } else {
                            Object result = page.webmcp().callTool(f.get("call"), args, new com.microsoft.playwright.WebMCP.CallToolOptions().setTimeout(15000));
                            out.set("result", ProtoJson.MAPPER.valueToTree(result));
                        }
                    } catch (RuntimeException failure) {
                        throw new UnsupportedProtocolOperation("webmcp", "playwright", PwText.first(failure.getMessage()));
                    }
                    return null;
                });
                case CDP -> {
                    CdpSession.Target t = ContextCommands.target(host, f);
                    JsonNode probe = host.cdp().send(t.sessionId, "Runtime.evaluate", ContextCommands.p("expression", "typeof navigator.modelContext", "returnByValue", true));
                    String kind = probe.path("result").path("value").asText();
                    throw new UnsupportedProtocolOperation("webmcp", backend.id(), "undefined".equals(kind)
                            ? "this browser has no WebMCP (navigator.modelContext)" : "WebMCP over CDP is not wired; use Playwright");
                }
                default -> throw new UnsupportedProtocolOperation("webmcp", backend.id(), null);
            }
            return out;
        });
        host.log("webmcp " + (list ? "list" : "call " + f.get("call")) + " via=" + served.via().id());
        return served.value().put("op", "webmcp").put("via", served.via().id());
    }

    /** First line of a Playwright error. */
    static final class PwText {
        static String first(String message) {
            if (message == null) return "";
            String line = message.lines().findFirst().orElse("");
            return line.replaceFirst("^Error: ", "");
        }
    }
}
