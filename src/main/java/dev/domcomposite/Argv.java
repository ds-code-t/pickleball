package dev.domcomposite;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Maps a CLI command and a {@code /v1} JSON body onto argv.
 *
 * <p>{@code null}, {@code false}, and {@code ""} omit a flag. A number becomes
 * a decimal string. A scalar array is comma-joined. Objects and unknown keys
 * are ignored. This class does not run a command.
 */
public final class Argv {
    private static final String[][] SELECTOR_FLAGS = {
        {"pipeline", "pipeline"},
        {"expr", "expr"},
        {"selector", "selector"},
        {"pw", "pw"},
        {"css", "css"},
        {"xpath", "xpath"},
        {"by", "by"},
        {"frame", "frame"},
        {"shadow", "shadow"},
        {"view", "view"},
        {"filter", "filter"},
        {"extract", "extract"},
        {"mode", "mode"},
        {"testid", "testid"},
        {"cdp", "cdp"},
        {"session", "session"},
        {"session_dir", "session-dir"},
        {"timeout_ms", "timeout-ms"},
        {"desc", "desc"},
        {"stability_ms", "stability-ms"},
        {"wait_for", "wait-for"},
        {"screenshot_out", "screenshot-out"},
        {"snapshot_out", "snapshot-out"},
        {"visual_threshold", "visual-threshold"},
        {"preset", "preset"},
        {"force_hover", "force-hover"},
        {"force_focus", "force-focus"},
    };

    private Argv() {}

    /** {@code command} is {@code query} or {@code session/create}. */
    public static List<String> from(String command, JsonNode body) {
        JsonNode node = body == null || body.isNull() ? Json.MAPPER.createObjectNode() : body;
        List<String> argv = new ArrayList<>();
        String cmd = "";
        if (command != null) {
            for (String part : command.split("/")) {
                if (part.isEmpty()) continue;
                if (cmd.isEmpty()) cmd = part;
                argv.add(part);
            }
        }
        if (cmd.isEmpty()) return argv;
        switch (cmd) {
            case "capture" -> pushValues(argv, node, new String[][] {
                {"url", "url"},
                {"out", "out"},
                {"viewport_width", "viewport-width"},
                {"viewport_height", "viewport-height"},
                {"dpr", "dpr"},
                {"page_scale", "page-scale"},
                {"scroll_x", "scroll-x"},
                {"scroll_y", "scroll-y"},
                {"color_scheme", "color-scheme"},
                {"media", "media"},
                {"hit_budget", "hit-budget"},
                {"hit_cell", "hit-cell"},
                {"settle_ms", "settle-ms"},
                {"timeout_ms", "timeout-ms"},
            });
            case "info", "query", "recommend", "resolve", "validate" -> {
                String file = positional(node);
                if (file != null) argv.add(file);
            }
            default -> {
            }
        }
        switch (cmd) {
            case "capture" -> pushBools(argv, node, new String[][] {{"mobile", "mobile"}});
            case "query", "resolve", "click", "type", "scroll", "submit", "key", "dblclick",
                    "contextclick", "eval" -> {
                pushValues(argv, node, SELECTOR_FLAGS);
                pushBools(argv, node, new String[][] {
                    {"strict", "strict"},
                    {"all_frames", "all-frames"},
                    {"explain", "explain"},
                    {"describe", "describe"},
                    {"auto_wait", "auto-wait"},
                    {"promote_visual", "promote-visual"},
                });
                if ("type".equals(cmd)) {
                    pushValues(argv, node, new String[][] {{"text", "text"}});
                    pushBools(argv, node, new String[][] {
                        {"submit", "submit"},
                        {"append", "append"},
                    });
                }
                if ("key".equals(cmd)) {
                    pushValues(argv, node, new String[][] {{"key", "key"}});
                }
                if ("eval".equals(cmd)) {
                    pushValues(argv, node, new String[][] {{"source", "source"}, {"node_id", "node-id"}, {"node_ids", "node-ids"}});
                }
                if ("query".equals(cmd)) {
                    pushValues(argv, node, new String[][] {
                        {"pierce", "pierce"},
                        {"poll_ms", "poll-ms"},
                        {"within", "within"},
                        {"nesting", "nesting"},
                        {"nth", "nth"},
                        {"every", "every"},
                        {"point", "point"}
                    });
                    pushBools(argv, node, new String[][] {{"count_only", "count-only"}, {"geometry", "geometry"}});
                }
                if ("scroll".equals(cmd)) {
                    pushValues(argv, node, new String[][] {{"dx", "dx"}, {"dy", "dy"}});
                }
            }
            case "drag" -> {
                pushValues(argv, node, new String[][] {
                    {"session", "session"},
                    {"cdp", "cdp"},
                    {"timeout_ms", "timeout-ms"},
                    {"stability_ms", "stability-ms"},
                    {"wait_for", "wait-for"},
                    {"session_dir", "session-dir"},
                });
                pushBools(argv, node, new String[][] {{"auto_wait", "auto-wait"}});
                pushLocator(argv, node, "source", "source");
                pushLocator(argv, node, "target", "target");
            }
            case "recommend" -> {
                pushValues(argv, node, new String[][] {
                    {"pipeline", "pipeline"},
                    {"expr", "expr"},
                    {"visual_threshold", "visual-threshold"},
                    {"preset", "preset"},
                });
                if (scalar(field(node, "css")) != null) {
                    pushValues(argv, node, new String[][] {{"css", "css"}});
                } else {
                    pushValues(argv, node, new String[][] {{"selector", "selector"}});
                }
                pushBools(argv, node, new String[][] {
                    {"explain", "explain"},
                    {"promote_visual", "promote-visual"},
                });
            }
            case "validate" -> pushValues(argv, node, new String[][] {{"expected", "expected"}});
            case "conform" -> pushValues(argv, node, new String[][] {
                {"root", "root"},
                {"expected", "expected"},
                {"port", "port"},
                {"cross_port", "cross-port"},
                {"out_dir", "out-dir"},
            });
            case "serve" -> pushValues(argv, node, new String[][] {
                {"bind", "bind"},
                {"token", "token"},
            });
            case "session" -> {
                pushValues(argv, node, new String[][] {
                    {"id", "id"},
                    {"url", "url"},
                    {"cdp", "cdp"},
                    {"allow", "allow"},
                    {"profile", "profile"},
                    {"session_dir", "session-dir"},
                });
                pushBools(argv, node, new String[][] {
                    {"process", "process"},
                    {"headed", "headed"},
                    {"i_accept_local_attach", "i-accept-local-attach"},
                    {"follow", "follow"},
                });
            }
            case "attach" -> {
                pushValues(argv, node, new String[][] {
                    {"cdp", "cdp"},
                    {"id", "id"},
                    {"allow", "allow"},
                    {"session_dir", "session-dir"},
                });
                pushBools(argv, node, new String[][] {
                    {"i_accept_local_attach", "i-accept-local-attach"},
                });
            }
            case "desktop" -> {
                pushValues(argv, node, new String[][] {
                    {"backend", "backend"},
                    {"pid", "pid"},
                    {"name", "name"},
                    {"fixture", "fixture"},
                    {"id", "id"},
                    {"out", "out"},
                    {"frame_id", "frame-id"},
                    {"backend_node_id", "backend-node-id"},
                    {"content_hash", "content-hash"},
                    {"tolerance", "tolerance"},
                });
                pushValues(argv, node, SELECTOR_FLAGS);
                pushBools(argv, node, new String[][] {
                    {"strict", "strict"},
                    {"all_frames", "all-frames"},
                    {"explain", "explain"},
                    {"describe", "describe"},
                    {"auto_wait", "auto-wait"},
                    {"verify", "verify"},
                    {"scroll", "scroll"},
                    {"perform", "perform"},
                    {"promote_visual", "promote-visual"},
                    {"append", "append"},
                });
                pushValues(argv, node, new String[][] {{"text", "text"}, {"key", "key"}});
            }
            case "screenshot" -> pushValues(argv, node, new String[][] {
                {"out", "out"},
                {"session", "session"},
                {"cdp", "cdp"},
                {"css", "css"},
                {"selector", "selector"},
                {"desc", "desc"},
                {"mode", "mode"},
                {"session_dir", "session-dir"},
            });
            case "snapshot" -> {
                pushValues(argv, node, new String[][] {
                    {"out", "out"},
                    {"session", "session"},
                    {"cdp", "cdp"},
                    {"css", "css"},
                    {"selector", "selector"},
                    {"expr", "expr"},
                    {"desc", "desc"},
                    {"mode", "mode"},
                    {"filter", "filter"},
                    {"visual_threshold", "visual-threshold"},
                    {"preset", "preset"},
                    {"force_hover", "force-hover"},
                    {"force_focus", "force-focus"},
                    {"session_dir", "session-dir"},
                });
                pushBools(argv, node, new String[][] {{"promote_visual", "promote-visual"}});
            }
            default -> {
            }
        }
        if ("resolve".equals(cmd)) {
            // cdp is already in SELECTOR_FLAGS. One flag is what the CLI accepts.
            pushValues(argv, node, new String[][] {
                {"frame_id", "frame-id"},
                {"backend_node_id", "backend-node-id"},
                {"content_hash", "content-hash"},
                {"url", "url"},
                {"tolerance", "tolerance"},
            });
            pushBools(argv, node, new String[][] {
                {"verify", "verify"},
                {"scroll", "scroll"},
                {"perform", "perform"},
            });
        }
        return argv;
    }

    static boolean hasText(JsonNode body, String key) {
        JsonNode v = field(body, key);
        return v != null && v.isTextual() && !v.asText().isEmpty();
    }

    private static String positional(JsonNode body) {
        for (String key : new String[] {"path", "file"}) {
            JsonNode v = field(body, key);
            if (v != null && v.isTextual() && !v.asText().isEmpty()) return v.asText();
        }
        return null;
    }

    static JsonNode field(JsonNode body, String key) {
        if (body == null || !body.isObject()) return null;
        JsonNode direct = body.get(key);
        if (direct != null) return direct;
        String hyphen = key.replace('_', '-');
        if (!hyphen.equals(key)) return body.get(hyphen);
        return null;
    }

    private static void pushLocator(List<String> argv, JsonNode body, String key, String flag) {
        JsonNode value = field(body, key);
        if (value == null || value.isNull()) return;
        if (value.isObject()) {
            argv.add("--" + flag);
            argv.add(value.toString());
            return;
        }
        String text = scalar(value);
        if (text == null) return;
        argv.add("--" + flag);
        argv.add(text);
    }

    private static void pushValues(List<String> argv, JsonNode body, String[][] flags) {
        for (String[] flag : flags) {
            String text = scalar(field(body, flag[0]));
            if (text == null) continue;
            argv.add("--" + flag[1]);
            argv.add(text);
        }
    }

    private static void pushBools(List<String> argv, JsonNode body, String[][] flags) {
        for (String[] flag : flags) {
            JsonNode v = field(body, flag[0]);
            if (v != null && v.isBoolean() && v.booleanValue()) argv.add("--" + flag[1]);
        }
    }

    static String scalar(JsonNode v) {
        if (v == null || v.isNull() || v.isBoolean() || v.isObject()) return null;
        if (v.isNumber()) {
            if (v.isIntegralNumber()) return v.bigIntegerValue().toString();
            return v.decimalValue().stripTrailingZeros().toPlainString();
        }
        if (v.isTextual()) {
            String s = v.asText();
            return s.isEmpty() ? null : s;
        }
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : v) {
                String part = scalar(item);
                if (part != null) parts.add(part);
            }
            return parts.isEmpty() ? null : String.join(",", parts);
        }
        return null;
    }
}
