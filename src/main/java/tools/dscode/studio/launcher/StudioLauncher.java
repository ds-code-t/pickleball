package tools.dscode.studio.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class StudioLauncher {
    private static final String STUDIO_RESOURCE = "/META-INF/pickleball/studio/pickleball-studio.jar";

    private StudioLauncher() {
    }

    static int launch(String[] args) throws IOException, InterruptedException {
        Path studioJar = extractStudio();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-jar");
        command.add(studioJar.toString());
        command.addAll(List.of(args));

        return new ProcessBuilder(command)
                .inheritIO()
                .start()
                .waitFor();
    }

    private static Path extractStudio() throws IOException {
        String version = StudioLauncher.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = "dev";
        }

        Path cacheRoot = Path.of(System.getProperty(
                "pickleball.studio.cache",
                Path.of(System.getProperty("user.home"), ".pickleball", "studio").toString()
        ));
        Path cacheDir = cacheRoot.resolve(version);

        InputStream bundledStudio = StudioLauncher.class.getResourceAsStream(STUDIO_RESOURCE);
        if (bundledStudio == null) {
            throw new IllegalStateException("Bundled Pickleball Studio application was not found.");
        }

        try (InputStream source = bundledStudio) {
            return cacheStudio(source, cacheDir);
        }
    }

    static Path cacheStudio(InputStream source, Path cacheDir) throws IOException {
        Files.createDirectories(cacheDir);

        Path temporary = Files.createTempFile(cacheDir, "pickleball-studio-", ".jar");
        boolean moved = false;
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            String digest = sha256(temporary);
            Path target = cacheDir.resolve("pickleball-studio-" + digest + ".jar");

            if (Files.isRegularFile(target)) {
                return target;
            }

            try {
                Files.move(temporary, target);
                moved = true;
            } catch (FileAlreadyExistsException race) {
                return target;
            }
            return target;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
