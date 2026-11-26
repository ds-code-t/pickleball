package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Copies {@code workbench-ui} into {@code META-INF/pickleball/workbench-ui}.
 * An empty lockfile skips npm. A lockfile that names packages runs {@code npm ci}
 * with the driver-bundle Node and a checksum-pinned npm CLI, then checks licenses.
 */
public final class WorkbenchUiPackager {
    static final String NPM_VERSION = "10.9.2";
    static final String NPM_SHA256 = "5cd1e5ab971ea6333f910bc2d50700167c5ef4e66da279b2a3efc874c6b116e4";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED = Set.of(
            "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC"
    );
    private static final Set<String> BANNED = Set.of("react", "react-dom", "electron", "tsx", "axe-core", "express");

    private WorkbenchUiPackager() {
    }

    public static void main(String[] args) {
        if (args == null || args.length < 2) {
            System.err.println("error: workbenchUi needs a project directory and an output directory");
            System.err.println("fix: run the workbenchUi Gradle task");
            System.exit(2);
        }
        Path cache = args.length >= 3 ? Path.of(args[2]) : Path.of(System.getProperty("user.home", ""), ".gradle");
        int code = packageUi(Path.of(args[0]), Path.of(args[1]), cache);
        if (code != 0) System.exit(code);
    }

    static int packageUi(Path project, Path output) {
        return packageUi(project, output, Path.of(System.getProperty("user.home", ""), ".gradle"));
    }

    static int packageUi(Path project, Path output, Path gradleUserHome) {
        Path source = project.resolve("workbench-ui");
        if (!Files.isDirectory(source)) {
            System.err.println("error: workbench-ui is missing");
            System.err.println("fix: keep workbench-ui/server.mjs in the project");
            return 2;
        }
        Path lock = source.resolve("package-lock.json");
        try {
            boolean packages = Files.isRegularFile(lock) && Files.readString(lock, StandardCharsets.UTF_8).contains("node_modules/");
            if (packages) {
                int installed = npmCi(source, gradleUserHome);
                if (installed != 0) return installed;
                int licensed = licenses(source.resolve("node_modules"));
                if (licensed != 0) return licensed;
            }
            copyTree(source, output.resolve("META-INF").resolve("pickleball").resolve("workbench-ui"), packages);
            return 0;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("error: " + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            System.err.println("fix: run the workbenchUi Gradle task");
            return 1;
        }
    }

    private static int npmCi(Path source, Path gradleUserHome) throws IOException, InterruptedException {
        Path node = PlaywrightNode.nodeBinary();
        if (node == null) {
            System.err.println("error: the Playwright Node binary is missing");
            System.err.println("fix: resolve com.microsoft.playwright:driver-bundle so the workbenchUi task can run npm");
            return 2;
        }
        Path cli = npmCli(gradleUserHome);
        if (cli == null) return 2;
        List<String> command = new ArrayList<>();
        command.add(node.toString());
        command.add(cli.toString());
        command.add("ci");
        command.add("--omit=dev");
        command.add("--ignore-scripts");
        String registry = registry();
        if (!registry.isBlank()) command.add("--registry=" + registry);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(source.toFile());
        builder.environment().put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            System.err.println("error: npm ci did not finish");
            System.err.println("fix: rerun the workbenchUi task");
            return 2;
        }
        if (process.exitValue() != 0) {
            System.err.println("error: npm ci failed");
            System.err.println("fix: check workbench-ui/package-lock.json and pkb_npm_registry");
            String tail = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
            if (!tail.isBlank()) System.err.println(tail.strip());
            return 2;
        }
        return 0;
    }

    private static Path npmCli(Path gradleUserHome) throws IOException, InterruptedException {
        Path cache = gradleUserHome.resolve("caches").resolve("pickleball").resolve("npm");
        Files.createDirectories(cache);
        Path tarball = cache.resolve("npm-" + NPM_VERSION + ".tgz");
        Path extract = cache.resolve("npm-" + NPM_VERSION);
        Path cli = extract.resolve("package").resolve("bin").resolve("npm-cli.js");
        if (!sameHash(tarball)) {
            Files.deleteIfExists(tarball);
            if (!download(tarball)) return null;
            if (!sameHash(tarball)) {
                Files.deleteIfExists(tarball);
                System.err.println("error: npm CLI checksum does not match");
                System.err.println("fix: delete the cached npm tarball and rerun workbenchUi");
                return null;
            }
            deleteTree(extract);
        }
        if (!Files.isRegularFile(cli)) {
            deleteTree(extract);
            Files.createDirectories(extract);
            ProcessBuilder tar = new ProcessBuilder("tar", "-xzf", tarball.toString(), "-C", extract.toString());
            tar.redirectErrorStream(true);
            Process process = tar.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0 || !Files.isRegularFile(cli)) {
                System.err.println("error: npm CLI tarball did not extract");
                System.err.println("fix: install tar and rerun the workbenchUi task");
                if (!output.isBlank()) System.err.println(output.strip());
                return null;
            }
        }
        return cli;
    }

    private static boolean download(Path tarball) throws IOException, InterruptedException {
        String base = registry();
        if (base.isBlank()) base = "https://registry.npmjs.org";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = base + "/npm/-/npm-" + NPM_VERSION + ".tgz";
        Path partial = tarball.resolveSibling(tarball.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<Path> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofFile(partial));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(partial);
            System.err.println("error: npm CLI download failed");
            System.err.println("fix: rerun workbenchUi when the registry is reachable, or place the pinned tarball in the Gradle cache");
            return false;
        }
        Files.move(partial, tarball, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static boolean sameHash(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return NPM_SHA256.equalsIgnoreCase(HexFormat.of().formatHex(digest));
        } catch (java.security.NoSuchAlgorithmException failure) {
            return false;
        }
    }

    private static String registry() {
        String property = System.getProperty("pkb_npm_registry");
        if (property != null && !property.isBlank()) return property.trim();
        String env = System.getenv("PKB_NPM_REGISTRY");
        return env == null ? "" : env.trim();
    }

    private static int licenses(Path modules) throws IOException {
        if (!Files.isDirectory(modules)) {
            System.err.println("error: npm ci did not create node_modules");
            System.err.println("fix: rerun the workbenchUi task");
            return 2;
        }
        List<Path> packages = new ArrayList<>();
        collect(modules, packages);
        for (Path pkg : packages) {
            Path file = pkg.resolve("package.json");
            if (!Files.isRegularFile(file)) continue;
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            String name = node.path("name").asText(pkg.getFileName().toString());
            if (BANNED.contains(name) || BANNED.contains(pkg.getFileName().toString())) {
                System.err.println("error: " + name + " is not packaged");
                System.err.println("fix: remove react, electron, tsx, axe-core, and express from workbench-ui");
                return 2;
            }
            String license = licenseOf(node);
            if (!allowed(license)) {
                System.err.println("error: license " + (license.isBlank() ? "(missing)" : license) + " for " + name + " is not allowed");
                System.err.println("fix: use MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC, or public domain");
                return 2;
            }
        }
        return 0;
    }

    private static void collect(Path modules, List<Path> packages) throws IOException {
        try (Stream<Path> children = Files.list(modules)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".")) continue;
                if (!Files.isDirectory(child)) continue;
                if (name.startsWith("@")) {
                    try (Stream<Path> scoped = Files.list(child)) {
                        for (Path pkg : scoped.toList()) {
                            if (Files.isDirectory(pkg)) packages.add(pkg);
                        }
                    }
                } else {
                    packages.add(child);
                }
            }
        }
        List<Path> nested = new ArrayList<>();
        for (Path pkg : packages) {
            Path inner = pkg.resolve("node_modules");
            if (Files.isDirectory(inner)) collect(inner, nested);
        }
        packages.addAll(nested);
    }

    private static String licenseOf(JsonNode node) {
        JsonNode license = node.get("license");
        if (license != null && license.isTextual()) return license.asText("").trim();
        if (license != null && license.isObject()) return license.path("type").asText("").trim();
        JsonNode licenses = node.get("licenses");
        if (licenses != null && licenses.isArray() && !licenses.isEmpty()) {
            JsonNode first = licenses.get(0);
            if (first.isTextual()) return first.asText("").trim();
            return first.path("type").asText("").trim();
        }
        return "";
    }

    static boolean allowed(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String text = raw.trim();
        if (text.toLowerCase(Locale.ROOT).contains(" and ")) {
            for (String part : text.split("(?i)\\s+AND\\s+")) {
                if (!allowed(part)) return false;
            }
            return true;
        }
        if (text.toLowerCase(Locale.ROOT).contains(" or ")) {
            for (String part : text.split("(?i)\\s+OR\\s+")) {
                if (allowed(part)) return true;
            }
            return false;
        }
        String token = text.replace("(", "").replace(")", "").trim();
        if ("public domain".equalsIgnoreCase(token)) return true;
        return ALLOWED.contains(token);
    }

    private static void copyTree(Path source, Path destination, boolean includeModules) throws IOException {
        if (Files.exists(destination)) deleteTree(destination);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                if (relative.getNameCount() == 0) continue;
                String first = relative.getName(0).toString();
                if (!includeModules && "node_modules".equals(first)) continue;
                Path target = destination.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
