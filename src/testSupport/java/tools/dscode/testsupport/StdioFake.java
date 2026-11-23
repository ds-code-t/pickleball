package tools.dscode.testsupport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Composite stdio fake. The product launches a platform script ({@code .cmd}
 * on Windows, a shell script on POSIX). The script starts this class. Tests
 * do not call {@code Files.setPosixFilePermissions} on Windows. On POSIX the
 * owner execute bit is set, because that is how the script is launched.
 * No Python.
 */
public final class StdioFake {
    public record Request(String id, List<String> argv) {
    }

    public record Reply(int exit, String stdout, String stderr) {
        /** Matches the Python fakes that append a newline inside {@code stdout}. */
        public static Reply json(String body) {
            return new Reply(0, body == null ? "" : body + "\n", "");
        }

        public static Reply of(int exitCode, String stdout, String stderr) {
            return new Reply(exitCode, stdout == null ? "" : stdout, stderr == null ? "" : stderr);
        }
    }

    @FunctionalInterface
    interface Handler {
        Reply on(Request request) throws IOException;
    }

    private StdioFake() {
    }

    /**
     * Writes the launcher and returns the path the product should execute.
     * {@code mode} selects the canned stdio behavior. Later arguments are
     * mode parameters (log path, flags).
     */
    public static Path install(Path path, String mode, String... args) throws IOException {
        boolean windows = PortableProcess.windows();
        Path launcher = windows ? PortableProcess.batchPath(path) : path;
        if (launcher.getParent() != null) Files.createDirectories(launcher.getParent());
        Path argFile = launcher.resolveSibling(launcher.getFileName().toString() + ".javaargs");
        List<String> javaArgs = new ArrayList<>();
        javaArgs.add("-Dfile.encoding=UTF-8");
        javaArgs.add("-cp");
        javaArgs.add(classpath());
        javaArgs.add(StdioFake.class.getName());
        javaArgs.add(mode);
        if (args != null) {
            for (String arg : args) javaArgs.add(arg == null ? "" : arg);
        }
        writeArgFile(argFile, javaArgs);
        String java = PortableProcess.javaBin().toAbsolutePath().toString();
        String file = argFile.toAbsolutePath().toString();
        if (windows) {
            Files.writeString(launcher, "@\"" + java + "\" @\"" + file + "\"\r\n", StandardCharsets.UTF_8);
        } else {
            Files.writeString(launcher, "#!/bin/sh\nexec " + shQuote(java) + " @" + shQuote(file) + "\n", StandardCharsets.UTF_8);
            PortableProcess.markExecutable(launcher);
        }
        return launcher;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("stdio fake mode is missing");
        StdioPrograms.run(args);
    }

    static void serve(Handler handler) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            Request request = parse(line.trim());
            Reply reply = handler.on(request);
            out.write(encode(request.id(), reply));
            out.write('\n');
            out.flush();
        }
    }

    static void logArgv(Path log, List<String> argv) throws IOException {
        if (log == null) return;
        append(log, String.join(" ", argv));
    }

    static void logJson(Path log, List<String> argv) throws IOException {
        if (log == null) return;
        append(log, argvJson(argv));
    }

    static String flag(List<String> argv, String name) {
        for (int i = 0; i < argv.size(); i++) {
            if (name.equals(argv.get(i)) && i + 1 < argv.size()) return argv.get(i + 1);
        }
        return "";
    }

    static boolean has(List<String> argv, String token) {
        return argv.contains(token);
    }

    static String joined(List<String> argv) {
        return String.join(" ", argv);
    }

    static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (ch < 0x20) quoted.append(String.format("\\u%04x", (int) ch));
                    else quoted.append(ch);
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static String argvJson(List<String> argv) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) json.append(", ");
            json.append(quote(argv.get(i)));
        }
        return json.append(']').toString();
    }

    private static void append(Path log, String line) throws IOException {
        String text = line.endsWith("\n") ? line : line + "\n";
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String encode(String id, Reply reply) {
        return "{\"id\":" + quote(id)
                + ",\"exit\":" + reply.exit()
                + ",\"stdout\":" + quote(reply.stdout())
                + ",\"stderr\":" + quote(reply.stderr())
                + "}";
    }

    private static String classpath() {
        try {
            var location = StdioFake.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) return Path.of(location.toURI()).toString();
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // Fall through to the test classpath.
        }
        return System.getProperty("java.class.path");
    }

    private static void writeArgFile(Path file, List<String> arguments) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String argument : arguments) body.append(quoteArg(argument)).append('\n');
        Files.writeString(file, body.toString(), StandardCharsets.UTF_8);
    }

    /** Java argument-file quoting. Backslash and quote are escaped inside quotes. */
    private static String quoteArg(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        String text = value == null ? "" : value;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> quoted.append(ch);
            }
        }
        return quoted.append('"').toString();
    }

    private static String shQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static Request parse(String line) {
        Cursor cursor = new Cursor(line);
        if (cursor.next() != '{') throw new IllegalArgumentException("stdio request is not an object");
        String id = "";
        List<String> argv = List.of();
        if (cursor.peek() == '}') return new Request(id, argv);
        while (true) {
            String key = cursor.string();
            if (cursor.next() != ':') throw new IllegalArgumentException("stdio request is missing a colon");
            if ("id".equals(key) && cursor.peek() == '"') id = cursor.string();
            else if ("argv".equals(key) && cursor.peek() == '[') argv = cursor.stringArray();
            else cursor.skipValue();
            char separator = cursor.next();
            if (separator == '}') break;
            if (separator != ',') throw new IllegalArgumentException("stdio request is missing a comma");
        }
        return new Request(id, argv);
    }

    private static final class Cursor {
        private final String text;
        private int index;

        private Cursor(String text) {
            this.text = text;
        }

        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private char peek() {
            skip();
            return index < text.length() ? text.charAt(index) : 0;
        }

        private char next() {
            skip();
            if (index >= text.length()) throw new IllegalArgumentException("stdio request ended early");
            return text.charAt(index++);
        }

        private String string() {
            if (next() != '"') throw new IllegalArgumentException("stdio request expected a string");
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char ch = text.charAt(index++);
                if (ch == '"') return value.toString();
                if (ch != '\\') {
                    value.append(ch);
                    continue;
                }
                if (index >= text.length()) throw new IllegalArgumentException("stdio request has a broken escape");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) throw new IllegalArgumentException("stdio request has a short unicode escape");
                        value.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("stdio request has an unknown escape");
                }
            }
            throw new IllegalArgumentException("stdio request has an unterminated string");
        }

        private List<String> stringArray() {
            if (next() != '[') throw new IllegalArgumentException("argv is not an array");
            List<String> values = new ArrayList<>();
            if (peek() == ']') {
                next();
                return List.copyOf(values);
            }
            while (true) {
                values.add(string());
                char separator = next();
                if (separator == ']') break;
                if (separator != ',') throw new IllegalArgumentException("argv array is missing a comma");
            }
            return List.copyOf(values);
        }

        private void skipValue() {
            char ch = peek();
            if (ch == '"') {
                string();
                return;
            }
            if (ch == '{') {
                next();
                if (peek() == '}') {
                    next();
                    return;
                }
                while (true) {
                    string();
                    if (next() != ':') throw new IllegalArgumentException("object is missing a colon");
                    skipValue();
                    char separator = next();
                    if (separator == '}') break;
                    if (separator != ',') throw new IllegalArgumentException("object is missing a comma");
                }
                return;
            }
            if (ch == '[') {
                next();
                if (peek() == ']') {
                    next();
                    return;
                }
                while (true) {
                    skipValue();
                    char separator = next();
                    if (separator == ']') break;
                    if (separator != ',') throw new IllegalArgumentException("array is missing a comma");
                }
                return;
            }
            if (ch == 't' || ch == 'f' || ch == 'n') {
                while (index < text.length() && Character.isLetter(text.charAt(index))) index++;
                return;
            }
            while (index < text.length() && "0123456789+-.eE".indexOf(text.charAt(index)) >= 0) index++;
        }
    }
}
