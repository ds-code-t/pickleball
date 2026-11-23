package tools.dscode.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin CLI. A running controller gets one JSON line and this process exits.
 * It does not reference the rest of Pickleball, so a warm verb does not pay
 * for a new controller JVM. {@code controller-serve} is the only path that
 * loads {@link AgentController}.
 */
public final class PkbMain {
    /** The controller is absent or the socket failed. The launcher runs the verb in-process. */
    public static final int HOP_MISS = 75;
    private static final Pattern PID = Pattern.compile("\"pid\"\\s*:\\s*(\\d+)");
    private static final Pattern PORT = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VERSION = Pattern.compile("\"pickleballVersion\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern COMPLETE = Pattern.compile("\"complete\"\\s*:\\s*(true|false)");
    private static final String MAIN_CLASS_ENTRY = "tools/dscode/launcher/PkbMain.class";

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
            return roundTrip(handle, "stop", List.of(), false, false);
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
            if (controllerCommand(command)) {
                System.err.println("error: controller is not running");
                System.err.println("fix: controller-start");
                return 1;
            }
            return HOP_MISS;
        }
        String op = "controller-ping".equals(command) ? "ping" : "";
        return roundTrip(handle, op, java.util.Arrays.asList(argv), true, !controllerCommand(command));
    }

    private static int start(Path project, String[] argv) throws Exception {
        Handle existing = read(project);
        if (existing != null) {
            System.out.println("running=true pid=" + existing.pid + " port=" + existing.port);
            return 0;
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = controllerClasspath(project);
        if (!classpathHasPkbMain(classpath)) {
            System.err.println("error: controller classpath does not contain PkbMain");
            System.err.println("fix: export-guidance --warm");
            return 1;
        }
        Path argFile = project.resolve(".pickleball").resolve("agent-controller.classpath");
        Files.createDirectories(argFile.getParent());
        Files.writeString(argFile, quoteArg(classpath) + "\n");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-Dsun.stdout.encoding=UTF-8");
        command.add("-Dsun.stderr.encoding=UTF-8");
        command.add("-cp");
        command.add("@" + argFile.toAbsolutePath().normalize());
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

    private static int roundTrip(Handle handle, String op, List<String> argv, boolean printBody, boolean hopMiss) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), handle.port), 1000);
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
                if (!out.isEmpty()) writeUtf8(System.out, out.endsWith("\n") ? out : out + "\n");
                if (!err.isEmpty()) writeUtf8(System.err, err.endsWith("\n") ? err : err + "\n");
            } else if ("stop".equals(op)) {
                System.out.println("stopped");
            }
            return exit;
        } catch (Exception failure) {
            if (hopMiss) return HOP_MISS;
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
                    if (ch < 0x20 || ch > 0x7e) json.append(String.format("\\u%04x", (int) ch));
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
            int portNumber = Integer.parseInt(port.group(1));
            if (!processAlive(id) || !portOpen(portNumber)) return null;
            return new Handle(id, portNumber, token.group(1));
        } catch (Exception failure) {
            return null;
        }
    }

    static boolean controllerUp(Path project) {
        return read(project) != null;
    }

    /** Alive process. {@code /proc} is not used, so Windows and Linux agree. */
    static boolean processAlive(long pid) {
        if (pid <= 0) return false;
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isPresent();
    }

    static boolean portOpen(int port) {
        if (port <= 0 || port > 65535) return false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    /**
     * Classpath for the controller JVM. A wrapper {@code java.class.path} that
     * does not contain {@code PkbMain} is not used. Prefer {@code classpath.cache}
     * when it does, then this JVM's classpath, then the code source and loader URLs.
     */
    static String controllerClasspath(Path project) {
        return controllerClasspath(project, System.getProperty("java.class.path", ""));
    }

    static String controllerClasspath(Path project, String javaClassPath) {
        String cached = classpathFromCache(project);
        if (classpathHasPkbMain(cached)) return cached;
        if (classpathHasPkbMain(javaClassPath)) return javaClassPath;
        String loaded = classpathFromLoaders();
        if (classpathHasPkbMain(loaded)) return loaded;
        return "";
    }

    static boolean classpathHasPkbMain(String classpath) {
        if (classpath == null || classpath.isBlank()) return false;
        for (String raw : classpath.split(java.io.File.pathSeparator, -1)) {
            String entry = unquoteEntry(raw.trim());
            if (entry.isEmpty()) continue;
            try {
                if (entryHasPkbMain(Path.of(entry))) return true;
            } catch (RuntimeException ignored) {
                // A wrapper token that is not a path is not Pickleball.
            }
        }
        return false;
    }

    static boolean entryHasPkbMain(Path path) {
        if (path == null) return false;
        try {
            if (Files.isDirectory(path)) return Files.isRegularFile(path.resolve(MAIN_CLASS_ENTRY));
            if (!Files.isRegularFile(path)) return false;
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jar")) return false;
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(path.toFile())) {
                return jar.getEntry(MAIN_CLASS_ENTRY) != null;
            }
        } catch (Exception failure) {
            return false;
        }
    }

    private static boolean controllerCommand(String command) {
        return command != null && command.startsWith("controller-");
    }

    private static String classpathFromCache(Path project) {
        if (project == null) return "";
        for (Path file : cacheFiles(project)) {
            if (!Files.isRegularFile(file)) continue;
            try {
                String joined = readCacheClasspath(file);
                if (classpathHasPkbMain(joined)) return joined;
            } catch (Exception ignored) {
                // The next candidate, or the loader classpath, is the fallback.
            }
        }
        return "";
    }

    private static List<Path> cacheFiles(Path project) {
        List<Path> files = new ArrayList<>();
        Path root = project.resolve(".pickleball");
        String version = currentVersion(root.resolve("current.json"));
        if (version != null) {
            files.add(root.resolve("v").resolve(version).resolve("workbench").resolve("classpath.cache"));
        }
        files.add(root.resolve("workbench").resolve("classpath.cache"));
        return files;
    }

    private static String currentVersion(Path current) {
        if (!Files.isRegularFile(current)) return null;
        try {
            String json = Files.readString(current);
            Matcher version = VERSION.matcher(json);
            if (!version.find()) return null;
            String value = version.group(1).trim();
            if (!safeVersion(value)) return null;
            Matcher complete = COMPLETE.matcher(json);
            if (complete.find() && !Boolean.parseBoolean(complete.group(1))) return null;
            return value;
        } catch (Exception failure) {
            return null;
        }
    }

    private static boolean safeVersion(String value) {
        if (value.isBlank()) return false;
        if (value.contains("/") || value.contains("\\") || value.contains("..")) return false;
        if (value.contains(":") || value.startsWith(".")) return false;
        return !value.equals(".");
    }

    private static String readCacheClasspath(Path file) throws Exception {
        String text = Files.readString(file).trim();
        if (text.isEmpty()) return "";
        String first = text.lines().findFirst().orElse("");
        return unquoteEntry(first.trim());
    }

    private static String classpathFromLoaders() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        addCodeSource(PkbMain.class, entries);
        addLoaderUrls(PkbMain.class.getClassLoader(), entries);
        addLoaderUrls(Thread.currentThread().getContextClassLoader(), entries);
        return String.join(java.io.File.pathSeparator, entries);
    }

    private static void addCodeSource(Class<?> type, LinkedHashSet<String> entries) {
        try {
            if (type.getProtectionDomain().getCodeSource() == null
                    || type.getProtectionDomain().getCodeSource().getLocation() == null) return;
            Path path = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (Files.exists(path)) entries.add(path.toString());
        } catch (Exception ignored) {
            // A missing code source is not a classpath entry.
        }
    }

    private static void addLoaderUrls(ClassLoader loader, LinkedHashSet<String> entries) {
        ClassLoader current = loader;
        for (int guard = 0; current != null && guard < 8; guard++) {
            if (current instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    try {
                        Path path = Path.of(url.toURI()).toAbsolutePath().normalize();
                        if (Files.exists(path)) entries.add(path.toString());
                    } catch (Exception ignored) {
                        // Skip a URL that is not a file.
                    }
                }
            }
            current = current.getParent();
        }
    }

    private static String unquoteEntry(String text) {
        String body = text == null ? "" : text.trim();
        if (body.length() < 2 || !body.startsWith("\"") || !body.endsWith("\"")) return body;
        String inner = body.substring(1, body.length() - 1);
        StringBuilder out = new StringBuilder(inner.length());
        for (int index = 0; index < inner.length(); index++) {
            char current = inner.charAt(index);
            if (current == '\\' && index + 1 < inner.length()) out.append(inner.charAt(++index));
            else out.append(current);
        }
        return out.toString();
    }

    private static String quoteArg(String value) {
        String raw = value == null ? "" : value;
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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

    /** Bytes, not {@link java.io.PrintStream#print}, so a non-UTF-8 console cannot replace {@code é}. */
    private static void writeUtf8(java.io.PrintStream stream, String text) {
        if (stream == null || text == null || text.isEmpty()) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        try {
            stream.write(bytes);
            stream.flush();
        } catch (java.io.IOException failure) {
            stream.print(text);
        }
    }

    private record Handle(long pid, int port, String token) {
    }
}
