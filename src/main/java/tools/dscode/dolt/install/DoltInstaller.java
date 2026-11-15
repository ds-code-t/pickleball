package tools.dscode.dolt.install;

import tools.dscode.dolt.DoltLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves and installs the pinned Dolt binary. The binary is never shaded into the jar.
 * Pickleball {@code dolt install} is this class. It is not the Dolt binary's own CLI.
 */
public final class DoltInstaller {
    public static final String VERSION = DoltReleaseCatalog.VERSION;
    public static final String OFFLINE_ERROR =
            "Dolt " + VERSION + " is not installed and could not be downloaded";
    public static final String OFFLINE_FIX =
            "run \"pickleball dolt install\" with network access, set pkb_dolt_bin to a dolt "
                    + VERSION + " binary, or set pkb_dolt=false to run file-only";

    private static final AtomicBoolean VERSION_WARNED = new AtomicBoolean(false);

    public enum Source {
        PROPERTY, CACHE, PATH, MISSING
    }

    public record Resolved(Path path, Source source, String version, String warning) {
        public boolean found() {
            return source != Source.MISSING && path != null;
        }
    }

    private DoltInstaller() {
    }

    public static Resolved resolve() {
        return resolve(explicitBin(), cacheBinary(cacheRoot(), DoltPlatform.osArch()), pathCandidates());
    }

    static Resolved resolve(Path explicit, Path cached, List<Path> pathBins) {
        if (explicit != null) {
            String version = readVersion(explicit);
            String warning = null;
            if (version == null || !VERSION.equals(version)) {
                warning = "pkb_dolt_bin reports dolt " + (version == null ? "unknown" : version)
                        + ", expected " + VERSION;
                if (VERSION_WARNED.compareAndSet(false, true)) {
                    System.err.println("warn: " + warning);
                }
            }
            return new Resolved(explicit, Source.PROPERTY, version, warning);
        }
        if (cached != null && Files.isRegularFile(cached)) {
            String version = readVersion(cached);
            if (VERSION.equals(version)) {
                return new Resolved(cached, Source.CACHE, version, null);
            }
        }
        for (Path candidate : pathBins) {
            if (candidate == null || !Files.isRegularFile(candidate)) continue;
            String version = readVersion(candidate);
            if (VERSION.equals(version)) {
                return new Resolved(candidate, Source.PATH, version, null);
            }
        }
        return new Resolved(null, Source.MISSING, null, null);
    }

    public static Resolved requireInstalled(Path project) throws IOException {
        Resolved resolved = resolve();
        if (resolved.found()) return resolved;
        if (!autoInstall()) {
            throw offline(project, "pkb_dolt_auto_install=false");
        }
        try {
            Path installed = installLocked(project, false, null);
            return new Resolved(installed, Source.CACHE, VERSION, null);
        } catch (IOException failure) {
            throw offline(project, failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    public static Path install(Path project, boolean force, Path dir) throws IOException {
        return installLocked(project, force, dir);
    }

    /**
     * Installs a local archive. Tests use this so they do not touch the network.
     * A checksum mismatch deletes the incoming copy and throws.
     */
    public static Path installArchive(Path project, Path archive, String expectedSha256, Path dir, String osArch)
            throws IOException {
        Path root = dir == null ? cacheRoot() : dir;
        Path incoming = root.resolve("incoming");
        Files.createDirectories(incoming);
        Path copy = incoming.resolve(archive.getFileName().toString());
        Files.copy(archive, copy, StandardCopyOption.REPLACE_EXISTING);
        try {
            return unpackVerified(project, copy, expectedSha256, root, osArch);
        } finally {
            Files.deleteIfExists(copy);
        }
    }

    private static Path installLocked(Path project, boolean force, Path dir) throws IOException {
        Path root = dir == null ? cacheRoot() : dir;
        Files.createDirectories(root);
        Path lockPath = root.resolve(".install.lock");
        try (var channel = java.nio.channels.FileChannel.open(lockPath,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
            try (var ignored = channel.lock()) {
                DoltReleaseCatalog.Asset asset = DoltReleaseCatalog.forCurrentPlatform();
                Path binary = binaryIn(root, DoltPlatform.osArch());
                if (!force && Files.isRegularFile(binary) && VERSION.equals(readVersion(binary))) {
                    DoltLog.log(project, "install", "dolt", "cache hit " + binary);
                    return binary;
                }
                Path incoming = root.resolve("incoming");
                Files.createDirectories(incoming);
                Path download = incoming.resolve(asset.fileName());
                try {
                    download(URI.create(asset.url()), download);
                    return unpackVerified(project, download, asset.sha256(), root, asset.osArch());
                } catch (IOException failure) {
                    Files.deleteIfExists(download);
                    throw failure;
                } finally {
                    Files.deleteIfExists(download);
                }
            }
        }
    }

    private static Path unpackVerified(Path project, Path archive, String expectedSha256, Path root, String osArch)
            throws IOException {
        String actual = sha256(archive);
        if (!expectedSha256.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(archive);
            throw new IOException("SHA-256 mismatch for " + archive.getFileName()
                    + ": expected " + expectedSha256 + " got " + actual);
        }
        Path unpack = root.resolve("unpack-" + System.nanoTime());
        Files.createDirectories(unpack);
        try {
            String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".zip")) unpackZip(archive, unpack);
            else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) unpackTarGz(archive, unpack);
            else throw new IOException("Unsupported Dolt archive: " + name);
            Path found = findBinary(unpack);
            if (found == null) throw new IOException("Dolt archive did not contain a dolt binary");
            Path destination = binaryIn(root, osArch);
            Files.createDirectories(destination.getParent());
            Files.copy(found, destination, StandardCopyOption.REPLACE_EXISTING);
            markExecutable(destination);
            DoltLog.log(project, "install", "dolt", "installed " + destination);
            return destination;
        } finally {
            deleteTree(unpack);
        }
    }

    static void unpackZip(Path archive, Path dest) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = safeChild(dest, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zip.transferTo(out);
                    }
                    if (isBinaryName(target)) markExecutable(target);
                }
                zip.closeEntry();
            }
        }
    }

    static void unpackTarGz(Path archive, Path dest) throws IOException {
        ProcessBuilder builder = new ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(),
                "-C", dest.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException("tar exited " + code + ": " + new String(output, StandardCharsets.UTF_8));
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("tar interrupted", failure);
        }
    }

    private static Path safeChild(Path root, String name) throws IOException {
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Archive entry escapes the unpack directory: " + name);
        }
        return target;
    }

    private static Path findBinary(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(DoltInstaller::isBinaryName)
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean isBinaryName(Path path) {
        String name = path.getFileName().toString();
        return "dolt".equals(name) || "dolt.exe".equalsIgnoreCase(name);
    }

    private static void markExecutable(Path binary) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(binary);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.OWNER_READ);
            Files.setPosixFilePermissions(binary, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            binary.toFile().setExecutable(true);
        }
    }

    static void download(URI url, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try (InputStream input = url.toURL().openStream()) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 is not available", failure);
        }
    }

    public static String readVersion(Path binary) {
        if (binary == null || !Files.isRegularFile(binary)) return null;
        ProcessBuilder builder = new ProcessBuilder(binary.toAbsolutePath().toString(), "version");
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return parseVersion(new String(output, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            return null;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static String parseVersion(String text) {
        if (text == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static Path cacheRoot() {
        String override = DoltLog.setting("pkb_dolt_tool_cache", "PKB_DOLT_TOOL_CACHE");
        if (override != null) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".pickleball", "tools", "dolt", VERSION);
    }

    public static Path cacheBinary(Path root, String osArch) {
        return binaryIn(root, osArch);
    }

    private static Path binaryIn(Path root, String osArch) {
        String name = osArch.startsWith("windows") ? "dolt.exe" : "dolt";
        return root.resolve(osArch).resolve(name);
    }

    private static Path explicitBin() {
        String raw = DoltLog.setting("pkb_dolt_bin", "PKB_DOLT_BIN");
        if (raw == null) return null;
        return Path.of(raw.trim());
    }

    public static boolean autoInstall() {
        String raw = DoltLog.setting("pkb_dolt_auto_install", "PKB_DOLT_AUTO_INSTALL");
        if (raw == null) return true;
        return !("false".equalsIgnoreCase(raw) || "0".equals(raw) || "off".equalsIgnoreCase(raw)
                || "no".equalsIgnoreCase(raw));
    }

    static List<Path> pathCandidates() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return List.of();
        String name = DoltPlatform.binaryName();
        List<Path> found = new ArrayList<>();
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            found.add(Path.of(dir).resolve(name));
        }
        return found;
    }

    public static String offlineMessage(String reason) {
        String why = reason == null || reason.isBlank() ? "unknown" : reason;
        return "error: " + OFFLINE_ERROR + " (" + why + ")\nfix: " + OFFLINE_FIX + "\n";
    }

    private static IOException offline(Path project, String reason) {
        DoltLog.log(project, "install", "dolt", "offline " + reason);
        return new IOException(offlineMessage(reason).strip());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Visible for tests that need a stable warning reset. */
    static void resetVersionWarning() {
        VERSION_WARNED.set(false);
    }

    public static int cli(List<String> tokens, java.io.PrintStream out, java.io.PrintStream err) {
        boolean check = tokens.contains("--check");
        boolean force = tokens.contains("--force");
        Path dir = flagPath(tokens, "--dir");
        Path project = project(tokens);
        try {
            if (check) {
                Resolved resolved = resolve();
                if (!resolved.found()) {
                    err.print(offlineMessage("nothing installed"));
                    return 1;
                }
                out.println("dolt binary: " + resolved.path().toAbsolutePath()
                        + " source=" + resolved.source().name().toLowerCase(Locale.ROOT)
                        + " version=" + (resolved.version() == null ? "unknown" : resolved.version()));
                if (resolved.warning() != null) err.println("warn: " + resolved.warning());
                return 0;
            }
            Path installed = install(project, force, dir);
            out.println(installed.toAbsolutePath());
            return 0;
        } catch (IOException failure) {
            String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            if (message.startsWith("error:")) err.println(message);
            else err.print(offlineMessage(message));
            return 1;
        }
    }

    private static Path flagPath(List<String> tokens, String name) {
        String prefix = name + "=";
        for (String token : tokens) {
            if (token.startsWith(prefix)) return Path.of(token.substring(prefix.length()));
        }
        return null;
    }

    public static Path project(List<String> tokens) {
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.startsWith("--project=")) {
                return Path.of(token.substring("--project=".length())).toAbsolutePath().normalize();
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    /** Test helper: sorted so failures print a stable missing-file list. */
    public static List<String> pinnedPlatforms() {
        List<String> keys = new ArrayList<>(List.of(
                "linux-amd64", "linux-arm64", "darwin-amd64", "darwin-arm64", "windows-amd64"));
        keys.sort(Comparator.naturalOrder());
        return keys;
    }
}
