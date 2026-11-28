package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.DropPayload;
import tools.dscode.browser.BidiModules;
import tools.dscode.browser.BrowserProtocols;
import tools.dscode.browser.RpcChannel;
import tools.dscode.browser.UnsupportedProtocolOperation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * E7: dialog policy ({@code dialogs}), file inputs and file-chooser dialogs ({@code upload --via} and
 * {@code upload --on-dialog}), and drag-and-drop of files or data ({@code drop}). Rows: {@code kind=dialog},
 * {@code kind=file-dialog}.
 */
final class DialogCommands {
    static final String DROP_JS = "(function(sel, files, data){const el=document.querySelector(sel);if(!el)return 'no element';"
            + "const dt=new DataTransfer();for(const f of files){const bin=atob(f.b64);const bytes=new Uint8Array(bin.length);"
            + "for(let i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);dt.items.add(new File([bytes],f.name,{type:f.type||''}))}"
            + "for(const [m,t] of Object.entries(data))dt.setData(m,t);"
            + "for(const type of ['dragenter','dragover','drop']){el.dispatchEvent(new DragEvent(type,{bubbles:true,cancelable:true,dataTransfer:dt}))}"
            + "return 'dropped'})";

    private DialogCommands() {
    }

    static void register() {
        ProtoHost.register("dialogs", DialogCommands::dialogs);
        ProtoHost.register("upload", DialogCommands::upload);
        ProtoHost.register("drop", DialogCommands::drop);
    }

    // ---- dialogs ----

    static ObjectNode dialogs(ProtoHost host, Map<String, String> f) {
        String policy = f.getOrDefault("policy", "");
        if (!(policy.equals("accept") || policy.equals("dismiss") || policy.startsWith("prompt:") || policy.equals("ask") || policy.equals("default"))) {
            throw new IllegalArgumentException("error: --policy is accept, dismiss, prompt:<text>, ask or default\nfix: dialogs --policy=accept");
        }
        host.stateMap().put("dialogs.policy", policy);
        // The policy wins over an attached Playwright session (aria-snap, clock, ws-route): no auto-dismiss.
        host.holdPlaywrightDialogs(!"default".equals(policy));
        if (!host.stateMap().containsKey("events.source")) EventCommands.start(host, f.containsKey("via") ? Map.of("via", f.get("via")) : Map.of());
        BrowserProtocols.Served<String> served = host.protocols().serve("dialogs", f.get("via"), backend -> {
            switch (backend) {
                case CDP -> {
                    if (host.stateMap().putIfAbsent("dialogs.cdp", true) == null) {
                        host.cdp().onTarget(t -> {
                            if ("page".equals(t.type)) host.cdp().send(t.sessionId, "Page.enable", Map.of());
                        });
                        host.cdp().channel().listen((method, params, session) -> {
                            if (!"Page.javascriptDialogOpening".equals(method)) return;
                            String p = String.valueOf(host.stateMap().get("dialogs.policy"));
                            Map<String, Object> answer = answer(p, params.path("type").asText());
                            if (answer == null) return;
                            keepDefault(answer, params.path("type").asText(), params.path("defaultPrompt").asText(""));
                            new Thread(() -> {
                                try {
                                    host.cdp().send(session, "Page.handleJavaScriptDialog", answer);
                                    handled(host, "cdp", p, params.path("type").asText(), params.path("message").asText());
                                } catch (RuntimeException gone) {
                                    failed(host, "cdp", params.path("type").asText(), gone);
                                }
                            }, "pkb-dialog").start();
                        });
                    }
                }
                case BIDI -> {
                    if (host.stateMap().putIfAbsent("dialogs.bidi", true) == null) {
                        host.bidiModules().subscribe(List.of("browsingContext.userPromptOpened"), null);
                        host.bidi().listen((method, params, session) -> {
                            if (!"browsingContext.userPromptOpened".equals(method)) return;
                            String p = String.valueOf(host.stateMap().get("dialogs.policy"));
                            Map<String, Object> answer = answer(p, params.path("type").asText());
                            if (answer == null) return;
                            keepDefault(answer, params.path("type").asText(), params.path("defaultValue").asText(""));
                            new Thread(() -> {
                                try {
                                    host.bidiModules().handleUserPrompt(params.path("context").asText(), (Boolean) answer.get("accept"), (String) answer.get("promptText"));
                                    handled(host, "bidi", p, params.path("type").asText(), params.path("message").asText());
                                } catch (RuntimeException gone) {
                                    failed(host, "bidi", params.path("type").asText(), gone);
                                }
                            }, "pkb-dialog").start();
                        });
                    }
                }
                case PLAYWRIGHT -> {
                    if (host.stateMap().putIfAbsent("dialogs.pw", true) == null) {
                        Consumer<Dialog> handler = dialog -> {
                            String p = String.valueOf(host.stateMap().get("dialogs.policy"));
                            Map<String, Object> answer = answer(p, dialog.type());
                            if (answer == null) return;
                            keepDefault(answer, dialog.type(), dialog.defaultValue());
                            if (Boolean.TRUE.equals(answer.get("accept"))) dialog.accept((String) answer.get("promptText"));
                            else dialog.dismiss();
                            handled(host, "playwright", p, dialog.type(), dialog.message());
                        };
                        host.pw().call(c -> {
                            for (BrowserContext context : c.browser().contexts()) {
                                for (Page page : context.pages()) page.onDialog(handler);
                                context.onPage(page -> page.onDialog(handler));
                            }
                            return null;
                        });
                        host.pw().listening(true);
                    }
                }
                default -> throw new UnsupportedProtocolOperation("dialogs", backend.id(), null);
            }
            return backend.id();
        });
        host.log("dialogs policy=" + (policy.startsWith("prompt:") ? "prompt" : policy) + " via=" + served.value());
        return ProtoJson.obj().put("op", "dialogs").put("via", served.value()).put("policy", policy.startsWith("prompt:") ? "prompt" : policy);
    }

    /** The answer for a policy, or null to leave the dialog open (ask) or to the browser's own handling (default). */
    static Map<String, Object> answer(String policy, String type) {
        Map<String, Object> a = new LinkedHashMap<>();
        if ("accept".equals(policy)) a.put("accept", true);
        else if ("dismiss".equals(policy)) a.put("accept", false);
        else if (policy.startsWith("prompt:")) {
            a.put("accept", true);
            if ("prompt".equals(type)) a.put("promptText", policy.substring(7));
        } else return null;
        return a;
    }

    /** Accepting a prompt without a text keeps the page's default value, as a person pressing OK would. */
    static void keepDefault(Map<String, Object> answer, String type, String defaultValue) {
        if ("prompt".equals(type) && Boolean.TRUE.equals(answer.get("accept")) && !answer.containsKey("promptText")) {
            answer.put("promptText", defaultValue == null ? "" : defaultValue);
        }
    }

    /** A dialog that could not be answered, usually because another client closed it first. */
    private static void failed(ProtoHost host, String source, String type, RuntimeException why) {
        String msg = String.valueOf(why.getMessage()).lines().findFirst().orElse("");
        host.hub().add(host.hub().row(source, "dialog", "unanswered").put("method", type).put("text", msg).put("level", "warn"));
        host.log("dialogs " + source + " could not answer " + type + ": " + msg);
    }

    private static void handled(ProtoHost host, String source, String policy, String type, String message) {
        host.hub().add(host.hub().row(source, "dialog", "handled").put("method", type).put("text", message)
                .put("level", policy.startsWith("prompt:") ? "prompt" : policy));
    }

    // ---- uploads and file dialogs ----

    static List<String> files(ProtoHost host, String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("error: needs --file=<path>[,<path>]\nfix: upload --selector='input[type=file]' --file=report.pdf --via=cdp");
        List<String> out = new ArrayList<>();
        for (String part : EventCommands.list(raw)) {
            Path p = host.project().resolve(part).normalize().toAbsolutePath();
            if (!Files.isRegularFile(p)) throw new IllegalArgumentException("error: no file " + part + "\nfix: paths are relative to the project");
            out.add(p.toString());
        }
        return out;
    }

    static ObjectNode upload(ProtoHost host, Map<String, String> f) {
        List<String> files = files(host, f.get("file"));
        if (f.containsKey("on-dialog")) return onDialog(host, f, files);
        String selector = f.getOrDefault("selector", "input[type=file]");
        BrowserProtocols.Served<String> served = host.protocols().serve("upload", f.get("via"), backend -> {
            switch (backend) {
                case PLAYWRIGHT -> host.pw().call(c -> {
                    PwBridge.page(c, f.get("page")).locator(selector).first().setInputFiles(files.stream().map(Path::of).toArray(Path[]::new));
                    return null;
                });
                case BIDI -> {
                    String context = ContextCommands.bidiContext(host, f);
                    JsonNode nodes = host.bidiModules().locateNodes(context, BidiModules.p("type", "css", "value", selector), 1).path("nodes");
                    if (nodes.isEmpty()) throw new IllegalArgumentException("error: no element " + selector + "\nfix: check --selector");
                    host.bidiModules().setFiles(context, BidiModules.p("sharedId", nodes.get(0).path("sharedId").asText()), files);
                }
                case CDP -> {
                    CdpSession.Target t = ContextCommands.target(host, f);
                    int root = host.cdp().send(t.sessionId, "DOM.getDocument", Map.of("depth", 0)).path("root").path("nodeId").asInt();
                    int node = host.cdp().send(t.sessionId, "DOM.querySelector", Map.of("nodeId", root, "selector", selector)).path("nodeId").asInt();
                    if (node == 0) throw new IllegalArgumentException("error: no element " + selector + "\nfix: check --selector");
                    host.cdp().send(t.sessionId, "DOM.setFileInputFiles", Map.of("files", files, "nodeId", node));
                }
                default -> throw new UnsupportedProtocolOperation("upload", backend.id(), null);
            }
            return backend.id();
        });
        host.log("upload " + files.size() + " file(s) via=" + served.value());
        return ProtoJson.obj().put("op", "upload").put("via", served.value()).put("files", files.size()).put("selector", selector);
    }

    /** Arms the next file chooser: a kind=file-dialog row when it opens, then these files answer it. */
    static ObjectNode onDialog(ProtoHost host, Map<String, String> f, List<String> files) {
        host.stateMap().put("file-dialog.pending", files);
        String via = RouteCommands.skip(host, "file-dialog", f.get("via"));
        BrowserProtocols.Served<String> served = host.protocols().serve("file-dialog", via, backend -> {
            switch (backend) {
                case CDP -> {
                    if (host.stateMap().putIfAbsent("file-dialog.cdp", true) == null) {
                        host.cdp().onTarget(t -> {
                            if (!"page".equals(t.type)) return;
                            host.cdp().send(t.sessionId, "Page.enable", Map.of());
                            host.cdp().send(t.sessionId, "Page.setInterceptFileChooserDialog", Map.of("enabled", true));
                        });
                        RpcChannel.Listener l = (method, params, session) -> {
                            if (!"Page.fileChooserOpened".equals(method)) return;
                            host.hub().add(host.hub().row("cdp", "file-dialog", "opened").put("method", params.path("mode").asText()));
                            @SuppressWarnings("unchecked")
                            List<String> pending = (List<String>) host.stateMap().remove("file-dialog.pending");
                            if (pending == null || !params.has("backendNodeId")) return;
                            new Thread(() -> {
                                try {
                                    host.cdp().send(session, "DOM.setFileInputFiles", Map.of("files", pending, "backendNodeId", params.path("backendNodeId").asInt()));
                                    host.hub().add(host.hub().row("cdp", "file-dialog", "answered").put("text", pending.size() + " file(s)"));
                                } catch (RuntimeException gone) {
                                    // The input went away.
                                }
                            }, "pkb-file-dialog").start();
                        };
                        host.cdp().channel().listen(l);
                    }
                }
                case BIDI -> {
                    if (host.stateMap().putIfAbsent("file-dialog.bidi", true) == null) {
                        host.bidiModules().subscribe(List.of("input.fileDialogOpened"), null);
                        host.bidi().listen((method, params, session) -> {
                            if (!"input.fileDialogOpened".equals(method)) return;
                            host.hub().add(host.hub().row("bidi", "file-dialog", "opened").put("method", params.path("multiple").asBoolean() ? "selectMultiple" : "selectSingle"));
                            @SuppressWarnings("unchecked")
                            List<String> pending = (List<String>) host.stateMap().remove("file-dialog.pending");
                            if (pending == null || !params.has("element")) return;
                            new Thread(() -> {
                                try {
                                    host.bidiModules().setFiles(params.path("context").asText(), BidiModules.p("sharedId", params.path("element").path("sharedId").asText()), pending);
                                    host.hub().add(host.hub().row("bidi", "file-dialog", "answered").put("text", pending.size() + " file(s)"));
                                } catch (RuntimeException gone) {
                                    // The input went away.
                                }
                            }, "pkb-file-dialog").start();
                        });
                    }
                }
                default -> throw new UnsupportedProtocolOperation("file-dialog", backend.id(), null);
            }
            return backend.id();
        });
        return ProtoJson.obj().put("op", "upload").put("via", served.value()).put("onDialog", true).put("files", files.size());
    }

    // ---- drop ----

    static ObjectNode drop(ProtoHost host, Map<String, String> f) throws Exception {
        String selector = f.get("selector");
        if (selector == null || selector.isBlank()) throw new IllegalArgumentException("error: drop needs --selector=<css>\nfix: drop --selector='#dropzone' --file=a.txt");
        List<String> files = f.containsKey("file") ? files(host, f.get("file")) : List.of();
        Map<String, String> data = new LinkedHashMap<>();
        if (f.containsKey("data")) {
            String raw = f.get("data");
            int colon = raw.indexOf(':');
            if (colon < 0) throw new IllegalArgumentException("error: --data is <mime>:<text>\nfix: drop --selector=#zone --data=text/plain:hello");
            data.put(raw.substring(0, colon), raw.substring(colon + 1));
        }
        if (files.isEmpty() && data.isEmpty()) throw new IllegalArgumentException("error: drop needs --file or --data\nfix: drop --selector=#zone --file=a.txt");
        List<Map<String, String>> payload = new ArrayList<>();
        for (String file : files) {
            Path p = Path.of(file);
            String type = Files.probeContentType(p);
            payload.add(Map.of("name", p.getFileName().toString(), "type", type == null ? "" : type, "b64", Base64.getEncoder().encodeToString(Files.readAllBytes(p))));
        }
        String call = DROP_JS + "(" + StorageCommands.json(selector) + "," + StorageCommands.json(payload) + "," + StorageCommands.json(data) + ")";
        BrowserProtocols.Served<String> served = host.protocols().serve("drop", f.get("via"), backend -> {
            switch (backend) {
                case PLAYWRIGHT -> {
                    return host.pw().call(c -> {
                        DropPayload drop = new DropPayload();
                        if (!files.isEmpty()) drop.setFiles(files.stream().map(Path::of).toArray(Path[]::new));
                        if (!data.isEmpty()) drop.setData(data);
                        PwBridge.page(c, f.get("page")).locator(selector).first().drop(drop);
                        return "dropped";
                    });
                }
                case BIDI -> {
                    // A sandbox realm (isolated from page scripts) builds the DataTransfer and dispatches the events.
                    return host.bidiModules().evaluate(ContextCommands.bidiContext(host, f), call, "pkb-drop", false).path("result").path("value").asText();
                }
                case CDP -> {
                    CdpSession.Target t = ContextCommands.target(host, f);
                    String frame = host.cdp().send(t.sessionId, "Page.getFrameTree", Map.of()).path("frameTree").path("frame").path("id").asText();
                    int world = host.cdp().send(t.sessionId, "Page.createIsolatedWorld", Map.of("frameId", frame, "worldName", "pkb-drop")).path("executionContextId").asInt();
                    return host.cdp().send(t.sessionId, "Runtime.evaluate", Map.of("expression", call, "contextId", world, "returnByValue", true)).path("result").path("value").asText();
                }
                default -> throw new UnsupportedProtocolOperation("drop", backend.id(), null);
            }
        });
        if (!"dropped".equals(served.value())) throw new IllegalArgumentException("error: drop: " + served.value() + "\nfix: check --selector");
        return ProtoJson.obj().put("op", "drop").put("via", served.via().id()).put("files", files.size()).put("data", data.size());
    }
}
