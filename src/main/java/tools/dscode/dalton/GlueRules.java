package tools.dscode.dalton;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java glue annotations stay single string literals. A successful change
 * compiles and stops the warm worker so the next run starts clean. A compile
 * failure leaves the fingerprint and the worker alone. JS and TS under
 * {@code glue/js/} are source only. There is no JS/TS step runtime.
 */
final class GlueRules {
    private static final Pattern ANNOTATION = Pattern.compile("@(?:Given|When|Then|And|But)\\b");

    private GlueRules() {
    }

    static void validate(Path project, String path, byte[] body) {
        if (path == null || !path.endsWith(".java") || body == null) return;
        String text = new String(body, StandardCharsets.UTF_8);
        Matcher matcher = ANNOTATION.matcher(text);
        while (matcher.find()) {
            if (inComment(text, matcher.start())) continue;
            int open = after(text, matcher.end());
            if (open >= text.length() || text.charAt(open) != '(') {
                throw new IllegalArgumentException("step annotation must be a single string literal");
            }
            int close = matching(text, open);
            if (!literal(text.substring(open + 1, close))) {
                throw new IllegalArgumentException("step annotation must be a single string literal");
            }
        }
    }

    static String afterWrite(Path project, String path) {
        if (path == null || !DaltonEdit.isGluePath(path)) return "";
        if (!path.endsWith(".java")) {
            return "js/ts source only; no step runtime";
        }
        try {
            DaltonRun.Plan plan = DaltonRun.plan(project);
            if (!plan.compile()) return "glue fingerprint unchanged";
            if (plan.compileCommand().isEmpty()) {
                return "glue saved; no project wrapper; prior worker kept";
            }
            if (compile(project, plan.compileCommand()) != 0) {
                return "compile failed; prior worker kept";
            }
            Path state = project.resolve(".pickleball").resolve("dalton");
            Files.createDirectories(state);
            Files.writeString(state.resolve(DaltonRun.FINGERPRINT), plan.fingerprint(), StandardCharsets.UTF_8);
            DaltonRun.stop(project);
            return "glue compiled; worker restarted";
        } catch (Exception failure) {
            return "compile failed; prior worker kept";
        }
    }

    private static int compile(Path project, List<String> command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        if (!process.waitFor(20, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            return 1;
        }
        return process.exitValue();
    }

    private static boolean inComment(String text, int at) {
        int line = text.lastIndexOf('\n', at) + 1;
        String prefix = text.substring(line, at).trim();
        return prefix.startsWith("//") || prefix.startsWith("*") || prefix.startsWith("/*");
    }

    private static int after(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        return cursor;
    }

    private static int matching(String text, int open) {
        int depth = 0;
        boolean string = false;
        boolean escape = false;
        for (int index = open; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (string) {
                if (escape) escape = false;
                else if (ch == '\\') escape = true;
                else if (ch == '"') string = false;
                continue;
            }
            if (ch == '"') {
                string = true;
                continue;
            }
            if (ch == '(') depth++;
            else if (ch == ')') {
                depth--;
                if (depth == 0) return index;
            }
        }
        throw new IllegalArgumentException("step annotation must be a single string literal");
    }

    private static boolean literal(String inside) {
        String body = inside.trim();
        if (body.startsWith("value")) {
            String rest = body.substring("value".length()).trim();
            if (rest.startsWith("=")) body = rest.substring(1).trim();
        }
        if (body.startsWith("\"\"\"")) {
            int end = body.indexOf("\"\"\"", 3);
            return end >= 0 && body.substring(end + 3).trim().isEmpty();
        }
        if (!body.startsWith("\"")) return false;
        boolean escape = false;
        for (int index = 1; index < body.length(); index++) {
            char ch = body.charAt(index);
            if (escape) {
                escape = false;
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (ch == '"') return body.substring(index + 1).trim().isEmpty();
        }
        return false;
    }
}
