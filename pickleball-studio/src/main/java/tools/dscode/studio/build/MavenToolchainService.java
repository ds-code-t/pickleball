package tools.dscode.studio.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class MavenToolchainService {
    static final String MAVEN_VERSION = "3.9.16";
    private static final String RESOURCE_ROOT = "META-INF/pickleball/studio/tools/maven/" + MAVEN_VERSION;

    private final Path cacheRoot;

    MavenToolchainService() {
        this(Path.of(System.getProperty(
                "pickleball.studio.toolcache",
                Path.of(System.getProperty("user.home"), ".pickleball", "studio", "tools").toString()
        )));
    }

    MavenToolchainService(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    MavenRuntime prepare() {
        List<String> jars = classpathEntries();
        Path home = cacheRoot.resolve("maven").resolve(MAVEN_VERSION);
        Path lib = home.resolve("lib");
        Path conf = home.resolve("conf");
        try {
            Files.createDirectories(lib);
            Files.createDirectories(conf);
            for (String jar : jars) {
                extract(RESOURCE_ROOT + "/lib/" + jar, lib.resolve(jar));
            }
            return new MavenRuntime(home, lib);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to prepare Studio Maven " + MAVEN_VERSION, error);
        }
    }

    private List<String> classpathEntries() {
        InputStream resource = resource(RESOURCE_ROOT + "/classpath.txt");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            List<String> entries = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (!value.isEmpty()) {
                    entries.add(value);
                }
            }
            if (entries.isEmpty()) {
                throw new IllegalStateException("Bundled Studio Maven classpath is empty");
            }
            return entries;
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read bundled Studio Maven classpath", error);
        }
    }

    private void extract(String resourceName, Path target) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try (InputStream input = resource(resourceName)) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }

        if (Files.isRegularFile(target) && sha256(target).equals(sha256(temporary))) {
            Files.delete(temporary);
            return;
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private InputStream resource(String name) {
        InputStream input = MavenToolchainService.class.getClassLoader().getResourceAsStream(name);
        if (input == null) {
            throw new IllegalStateException("Bundled Studio Maven resource was not found: " + name);
        }
        return input;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
