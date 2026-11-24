package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domengine.desktop.GridDrivers;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code setup desktop-driver --driver=<name> [--dir=<path>] [--confirm]} and {@code setup ocr --runtime=onnx ...}:
 * the only way pickleball installs an opt-in component. It always prints the license, source, pinned version,
 * and checksum first. Without {@code --confirm} it stops there and installs nothing. With it, a download is
 * checksum-verified before it lands in the cache dir; an Appium driver is installed with the user's own
 * {@code appium} command. Nothing else in pickleball calls this.
 */
final class SetupCommand {
    static final ObjectMapper JSON = new ObjectMapper();

    private SetupCommand() {
    }

    static int run(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String subject = positional(parsed.forwarded());
        if ("desktop-driver".equals(subject)) return desktopDriver(parsed, out, err);
        if ("ocr".equals(subject)) return OcrSetup.run(parsed, out, err);
        err.println(WorkbenchCliHelp.structured("setup needs desktop-driver or ocr",
                "setup desktop-driver --driver=flaui|novawindows|winappdriver|mac2 [--dir=<path>] [--confirm], or setup ocr --runtime=onnx [--dir=<path>] [--confirm]").stripTrailing());
        return 2;
    }

    static int desktopDriver(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String name = value(parsed.forwarded(), "--driver");
        Optional<GridDrivers.Driver> driver = GridDrivers.byName(name);
        if (driver.isEmpty()) {
            err.println(WorkbenchCliHelp.structured("setup desktop-driver needs --driver=flaui, novawindows, winappdriver, or mac2",
                    "setup desktop-driver --driver=flaui [--dir=<path>] [--confirm]").stripTrailing());
            return 2;
        }
        GridDrivers.Driver d = driver.get();
        Path dir = dir(parsed, "desktop-drivers").resolve(d.name()).resolve(d.version());
        ObjectNode body = JSON.createObjectNode();
        body.put("ok", true).put("op", "setup").put("component", "desktop-driver").put("driver", d.name())
                .put("version", d.version()).put("license", d.license()).put("source", d.source())
                .put("os", d.family()).put("dir", dir.toString());
        if (!d.artifact().isBlank()) body.put("artifact", d.artifact()).put("sha256", d.sha256());
        else body.put("command", d.install());
        if (!d.needs().isEmpty()) body.put("needs", String.join(",", d.needs()));
        out.println("license: " + d.license() + " (" + d.source() + ")");
        if (!confirmed(parsed.forwarded())) {
            body.put("installed", false);
            body.put("next", "re-run with --confirm to install " + d.name() + " " + d.version());
            out.println(body);
            return 0;
        }
        try {
            if (!d.artifact().isBlank()) {
                Path file = fetchVerified(URI.create(d.artifact()), d.sha256(), dir);
                body.put("installed", true).put("file", file.toString());
            } else {
                List<String> command = List.of(d.install().split(" "));
                Process process = new ProcessBuilder(command).inheritIO().start();
                int code = process.waitFor();
                if (code != 0) throw new IOException(d.install() + " exited " + code);
                body.put("installed", true);
            }
            Files.writeString(dir.resolve("LICENSE-NOTICE.txt"), d.name() + " " + d.version() + " " + d.license() + " " + d.source() + "\n");
            out.println(body);
            return 0;
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            err.println(WorkbenchCliHelp.structured("setup " + d.name() + " failed: " + failure.getMessage(),
                    d.artifact().isBlank() ? "install Node and Appium (npm i -g appium), then re-run" : "check the network or download " + d.artifact() + " yourself").stripTrailing());
            return 1;
        }
    }

    /** Downloads to a temp file, checks SHA-256 against the pinned value, and only then moves it into {@code dir}. */
    static Path fetchVerified(URI source, String sha256, Path dir) throws IOException {
        Files.createDirectories(dir);
        String name = Path.of(source.getPath()).getFileName().toString();
        Path partial = Files.createTempFile(dir, name, ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = open(source); var outFile = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, n);
                    outFile.write(buffer, 0, n);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equalsIgnoreCase(sha256)) throw new IOException("checksum mismatch for " + name + ": expected " + sha256 + ", got " + actual);
            Path target = dir.resolve(name);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static InputStream open(URI source) throws IOException {
        if ("file".equals(source.getScheme())) return Files.newInputStream(Path.of(source));
        try {
            var client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
            var response = client.send(java.net.http.HttpRequest.newBuilder(source).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for " + source);
            return response.body();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }

    static Path dir(WorkbenchCommandLine.Parsed parsed, String leaf) {
        String explicit = value(parsed.forwarded(), "--dir");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.home"), ".pickleball", "opt-in", leaf);
    }

    static boolean confirmed(String[] args) {
        if (args == null) return false;
        for (String token : args) if ("--confirm".equals(token) || "--confirm=true".equals(token)) return true;
        return false;
    }

    static String positional(String[] args) {
        if (args == null) return "";
        // forwarded() starts with the command and the project; the subject is the first bare word after them.
        for (int i = 2; i < args.length; i++) {
            String token = args[i];
            if (token != null && !token.isBlank() && !token.startsWith("--")) return token.trim().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    static String value(String[] args, String name) {
        if (args == null) return null;
        for (String token : args) if (token != null && token.startsWith(name + "=")) return token.substring(name.length() + 1);
        return null;
    }
}
