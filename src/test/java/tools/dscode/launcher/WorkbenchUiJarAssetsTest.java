package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link WorkbenchUiServer#start} serves the renderer from a jar resource.
 * The child classpath has no exploded {@code META-INF/pickleball/workbench-ui} directory.
 */
class WorkbenchUiJarAssetsTest {
    @Test
    void startServesHomeAndMonacoFromAJarOnlyClasspath(@TempDir Path root) throws Exception {
        Path jar = root.resolve("ui.jar");
        writeFixtureJar(jar);
        Path project = root.resolve("project");
        Files.createDirectories(project);
        Path out = root.resolve("out.txt");
        Path err = root.resolve("err.txt");
        Path args = root.resolve("child.args");
        Files.writeString(args, String.join("\n",
                "-cp",
                classpath(jar).replace('\\', '/'),
                "tools.dscode.launcher.WorkbenchUiJarAssetsTest",
                project.toAbsolutePath().toString().replace('\\', '/')) + "\n");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("@" + args.toAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        Process process = builder.start();
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        String stdout = Files.exists(out) ? Files.readString(out) : "";
        String stderr = Files.exists(err) ? Files.readString(err) : "";
        assertTrue(finished, stdout + "\n" + stderr);
        assertEquals(0, process.exitValue(), stdout + "\n" + stderr);
        assertTrue(stdout.contains("HTTP/1.1 200"), stdout);
        assertTrue(stdout.contains("loader-ok"), stdout);
        Path assets = project.resolve(".pickleball").resolve("workbench-ui").resolve("assets");
        assertTrue(Files.isDirectory(assets), stdout);
        try (Stream<Path> children = Files.list(assets)) {
            Path exploded = children.filter(Files::isDirectory).findFirst().orElseThrow();
            assertEquals(64, exploded.getFileName().toString().length());
            assertTrue(Files.isRegularFile(exploded.resolve("server.mjs")));
            String loader = Files.readString(exploded.resolve(
                    "node_modules/monaco-editor/min/vs/loader.js"));
            assertTrue(loader.contains("Released under the MIT license"), loader.substring(0, Math.min(loader.length(), 200)));
            assertEquals(exploded.getFileName().toString(),
                    Files.readString(exploded.resolve(".pkb-ui-assets")).trim());
        }
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]);
        try (WorkbenchUiServer.Handle handle = WorkbenchUiServer.start(project)) {
            String home = exchange(handle.port(), request("/?t=" + handle.token()));
            String loader = exchange(handle.port(), request(
                    "/node_modules/monaco-editor/min/vs/loader.js?t=" + handle.token()));
            System.out.println(status(home));
            System.out.println(status(loader));
            if (!status(home).startsWith("HTTP/1.1 200") || !home.contains("Scenarios")) {
                System.exit(3);
            }
            if (!status(loader).startsWith("HTTP/1.1 200") || !loader.contains("Released under the MIT license")) {
                System.exit(4);
            }
            Path server = explodedServer(project);
            var first = Files.getLastModifiedTime(server);
            handle.close();
            try (WorkbenchUiServer.Handle again = WorkbenchUiServer.start(project)) {
                String secondHome = exchange(again.port(), request("/?t=" + again.token()));
                if (!status(secondHome).startsWith("HTTP/1.1 200")) System.exit(5);
            }
            if (!first.equals(Files.getLastModifiedTime(server))) System.exit(6);
            System.out.println("loader-ok");
        }
    }

    private static Path explodedServer(Path project) throws IOException {
        Path assets = project.resolve(".pickleball").resolve("workbench-ui").resolve("assets");
        try (Stream<Path> children = Files.list(assets)) {
            return children.filter(Files::isDirectory)
                    .findFirst()
                    .orElseThrow()
                    .resolve("server.mjs");
        }
    }

    private static void writeFixtureJar(Path jar) throws IOException {
        Path ui = Path.of("workbench-ui");
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(jar))) {
            add(stream, "META-INF/pickleball/workbench-ui/server.mjs", ui.resolve("server.mjs"));
            add(stream, "META-INF/pickleball/workbench-ui/pages/index.html", ui.resolve("pages/index.html"));
            add(stream, "META-INF/pickleball/workbench-ui/node_modules/monaco-editor/min/vs/loader.js",
                    ui.resolve("node_modules/monaco-editor/min/vs/loader.js"));
        }
    }

    private static void add(JarOutputStream stream, String name, Path file) throws IOException {
        stream.putNextEntry(new JarEntry(name));
        Files.copy(file, stream);
        stream.closeEntry();
    }

    private static String classpath(Path jar) throws IOException {
        StringBuilder out = new StringBuilder(jar.toAbsolutePath().toString());
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) continue;
            String normalized = entry.replace('\\', '/');
            if (normalized.contains("/build/resources/main")) continue;
            if (normalized.endsWith("/workbench-ui") || normalized.contains("/workbench-ui/")) continue;
            Path path = Path.of(entry);
            if (Files.isDirectory(path) && Files.isRegularFile(path.resolve("META-INF/pickleball/workbench-ui/server.mjs"))) {
                continue;
            }
            out.append(java.io.File.pathSeparator).append(entry);
        }
        return out.toString();
    }

    private static String request(String path) {
        return "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    }

    private static String exchange(int port, String head) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.shutdownOutput();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(body);
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private static String status(String response) {
        int end = response.indexOf('\n');
        return end < 0 ? response : response.substring(0, end).trim();
    }
}
