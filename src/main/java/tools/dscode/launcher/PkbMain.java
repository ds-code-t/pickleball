package tools.dscode.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin CLI. A running controller gets one JSON line and this process exits.
 * It does not reference the rest of Pickleball, so a warm verb does not pay
 * for a new controller JVM. {@code controller-serve} is the only path that
 * loads {@link AgentController}.
 */
public final class PkbMain {
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");
    private static final Pattern PORT = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]*)\"");

    private PkbMain() {
    }

    public static void main(String[] args) throws Exception {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** Returns the process exit code. Does not call {@code System.exit}. */
    public static int run(String[] args) throws Exception {
        String[] argv = args == null ? new String[0] : args;
        if (argv.length > 0 && "controller-serve".equals(argv[0])) {
            Class<?> type = Class.forName("tools.dscode.launcher.AgentController");
            type.getMethod("serve", String[].class).invoke(null, (Object) argv);
            return 0;
        }
        Path project = projectOf(argv);
        String command = argv.length == 0 ? "" : argv[0];
        if ("controller-start".equals(command)) {
            return start(project, argv);
        }
        Handle handle = read(project);
        if ("controller-stop".equals(command)) {
            if (handle == null) {
                System.out.println("stopped");
                return 0;
            }
            return roundTrip(handle, "stop", List.of(), false);
        }
        if ("controller-status".equals(command)) {
            if (handle == null) {
                System.out.println("running=false");
                return 0;
            }
            System.out.println("running=true pid=" + handle.pid + " port=" + handle.port);
            return 0;
        }
        if (handle == null) {
            int started = start(project, new String[]{"controller-start", project.toString()});
            if (started != 0) return started;
            handle = read(project);
            if (handle == null) {
                System.err.println("error: controller did not stay up");
                System.err.println("fix: controller-start");
                return 1;
            }
        }
        String op = "controller-ping".equals(command) ? "ping" : "";
        return roundTrip(handle, op, java.util.Arrays.asList(argv), true);
    }

    private static int start(Path project, String[] argv) throws Exception {
        Handle existing = read(project);
        if (existing != null) {
            System.out.println("running=true pid=" + existing.pid + " port=" + existing.port);
            return 0;
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("tools.dscode.launcher.PkbMain");
        command.add("controller-serve");
        command.add(project.toString());
        Path log = project.resolve(".pickleball").resolve("agent-controller.log");
        Files.createDirectories(log.getParent());
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            Handle handle = read(project);
            if (handle != null) {
                System.out.println("running=true pid=" + handle.pid + " port=" + handle.port);
                return 0;
            }
            if (!process.isAlive()) {
                System.err.println("error: controller exited " + process.exitValue());
                System.err.println("fix: see " + log);
                return 1;
            }
            Thread.sleep(50);
        }
        process.destroyForcibly();
        System.err.println("error: controller did not open a port");
        System.err.println("fix: controller-start");
        return 1;
    }

    private static int roundTrip(Handle handle, String op, List<String> argv, boolean printBody) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), handle.port), 2000);
            socket.setSoTimeout(120_000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println(request(handle.token, op, argv));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                System.err.println("error: controller closed the socket");
                System.err.println("fix: controller-start");
                return 1;
            }
            int exit = number(line, "exit", 1);
            if (printBody) {
                String out = stringField(line, "out");
                String err = stringField(line, "err");
                if (!out.isEmpty()) System.out.print(out.endsWith("\n") ? out : out + "\n");
                if (!err.isEmpty()) System.err.print(err.endsWith("\n") ? err : err + "\n");
            } else if ("stop".equals(op)) {
                System.out.println("stopped");
            }
            return exit;
        } catch (Exception failure) {
            System.err.println("error: controller unreachable: " + failure.getMessage());
            System.err.println("fix: controller-start");
            return 1;
        }
    }

    static String request(String token, String op, List<String> argv) {
        StringBuilder json = new StringBuilder();
        json.append("{\"token\":").append(quote(token));
        json.append(",\"op\":").append(quote(op));
        json.append(",\"argv\":[");
        if (argv != null) {
            boolean any = false;
            for (String arg : argv) {
                if (any) json.append(',');
                json.append(quote(arg));
                any = true;
            }
        }
        json.append("]}");
        return json.toString();
    }

    private static String quote(String value) {
        String raw = value == null ? "" : value;
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20) json.append(String.format("\\u%04x", (int) ch));
                    else json.append(ch);
                }
            }
        }
        json.append('"');
        return json.toString();
    }

    static Path projectOf(String[] argv) {
        if (argv != null) {
            for (int index = 1; index < argv.length; index++) {
                String token = argv[index];
                if (token == null || token.isBlank() || token.startsWith("-")) continue;
                Path path = Path.of(token);
                if (Files.isDirectory(path)) return path.toAbsolutePath().normalize();
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    static Path handleFile(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(".pickleball").resolve("agent-controller.json");
    }

    private static Handle read(Path project) {
        Path file = handleFile(project);
        if (!Files.isRegularFile(file)) return null;
        try {
            String text = Files.readString(file);
            Matcher pid = PID.matcher(text);
            Matcher port = PORT.matcher(text);
            Matcher token = TOKEN.matcher(text);
            if (!pid.find() || !port.find() || !token.find()) return null;
            long id = Long.parseLong(pid.group(1));
            if (!Files.isDirectory(Path.of("/proc/" + id))) return null;
            return new Handle(id, Integer.parseInt(port.group(1)), token.group(1));
        } catch (Exception failure) {
            return null;
        }
    }

    private static int number(String json, String field, int fallback) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find()) return fallback;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    /** Decodes the JSON string value of {@code field}. Nested objects are not used on this socket. */
    static String stringField(String json, String field) {
        String key = "\"" + field + "\"";
        int at = json.indexOf(key);
        if (at < 0) return "";
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) return "";
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) return "";
        StringBuilder value = new StringBuilder();
        for (int index = quote + 1; index < json.length(); index++) {
            char ch = json.charAt(index);
            if (ch == '"') return value.toString();
            if (ch != '\\' || index + 1 >= json.length()) {
                value.append(ch);
                continue;
            }
            char next = json.charAt(++index);
            switch (next) {
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (index + 4 >= json.length()) return value.toString();
                    value.append((char) Integer.parseInt(json.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> value.append(next);
            }
        }
        return value.toString();
    }

    private record Handle(long pid, int port, String token) {
    }
}
