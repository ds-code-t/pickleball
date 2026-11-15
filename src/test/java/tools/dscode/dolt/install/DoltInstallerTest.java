package tools.dscode.dolt.install;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.DoltCli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltInstallerTest {
    @TempDir
    Path temp;

    @AfterEach
    void clearProperties() {
        System.clearProperty("pkb_dolt_bin");
        System.clearProperty("pkb_dolt_tool_cache");
        System.clearProperty("pkb_dolt_auto_install");
        System.clearProperty("pkb_dolt_log");
        DoltInstaller.resetVersionWarning();
    }

    @Test
    void resolutionOrderPrefersPropertyThenCacheThenExactPathVersion() throws Exception {
        Path match = script(temp.resolve("match"), "2.7.3");
        Path wrong = script(temp.resolve("wrong"), "9.9.9");
        Path cache = script(temp.resolve("cache/linux-amd64/dolt"), "2.7.3");
        DoltInstaller.Resolved property = DoltInstaller.resolve(match, cache, List.of(wrong));
        assertEquals(DoltInstaller.Source.PROPERTY, property.source());
        assertEquals("2.7.3", property.version());

        DoltInstaller.resetVersionWarning();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            DoltInstaller.Resolved warned = DoltInstaller.resolve(wrong, cache, List.of(match));
            DoltInstaller.Resolved second = DoltInstaller.resolve(wrong, cache, List.of(match));
            assertEquals(DoltInstaller.Source.PROPERTY, warned.source());
            assertTrue(warned.warning().contains("9.9.9"));
            String stderr = err.toString(StandardCharsets.UTF_8);
            assertEquals(stderr, second == null ? "" : stderr);
            assertEquals(1, stderr.split("warn:", -1).length - 1);
        } finally {
            System.setErr(previous);
        }

        DoltInstaller.Resolved cached = DoltInstaller.resolve(null, cache, List.of(wrong));
        assertEquals(DoltInstaller.Source.CACHE, cached.source());
        DoltInstaller.Resolved path = DoltInstaller.resolve(null, temp.resolve("missing"), List.of(match, wrong));
        assertEquals(DoltInstaller.Source.PATH, path.source());
        assertEquals(match, path.path());
        DoltInstaller.Resolved missing = DoltInstaller.resolve(null, temp.resolve("missing"), List.of(wrong));
        assertEquals(DoltInstaller.Source.MISSING, missing.source());
        assertFalse(missing.found());
    }

    @Test
    void checksumMismatchDeletesTheDownload() throws Exception {
        Path archive = tar(temp.resolve("good.tar.gz"), "dolt");
        String wrong = "0000000000000000000000000000000000000000000000000000000000000000";
        Path cache = temp.resolve("cache");
        IOException failure = assertThrows(IOException.class,
                () -> DoltInstaller.installArchive(temp.resolve("project"), archive, wrong, cache, "linux-amd64"));
        assertTrue(failure.getMessage().contains("SHA-256 mismatch"));
        assertFalse(Files.exists(cache.resolve("linux-amd64/dolt")));
        if (Files.isDirectory(cache.resolve("incoming"))) {
            try (var files = Files.list(cache.resolve("incoming"))) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void offlineMessageAndAutoInstallRefusal() {
        String text = DoltInstaller.offlineMessage("connection refused");
        assertEquals("error: Dolt 2.7.3 is not installed and could not be downloaded (connection refused)\n"
                + "fix: run \"pickleball dolt install\" with network access, set pkb_dolt_bin to a dolt 2.7.3 binary, "
                + "or set pkb_dolt=false to run file-only\n", text);
        System.setProperty("pkb_dolt_auto_install", "false");
        System.setProperty("pkb_dolt_tool_cache", temp.resolve("empty-cache").toString());
        IOException failure = assertThrows(IOException.class,
                () -> DoltInstaller.requireInstalled(temp.resolve("project")));
        assertTrue(failure.getMessage().contains("Dolt 2.7.3 is not installed and could not be downloaded"));
        assertTrue(failure.getMessage().contains("pkb_dolt_auto_install=false"));
        assertTrue(failure.getMessage().contains("pickleball dolt install"));
    }

    @Test
    void installCheckPrintsALocalArchiveAndLogsTheInstall() throws Exception {
        Path archive = tar(temp.resolve("dolt.tar.gz"), "dolt");
        String sha = DoltInstaller.sha256(archive);
        Path cache = temp.resolve("cache");
        Path project = temp.resolve("project");
        System.setProperty("pkb_dolt_log", "install");
        Path installed = DoltInstaller.installArchive(project, archive, sha, cache, DoltPlatform.osArch());
        assertEquals("2.7.3", DoltInstaller.readVersion(installed));
        String log = Files.readString(project.resolve(".pickleball/dolt/dolt.log"));
        assertTrue(log.contains("sandbox=main"));
        assertTrue(log.startsWith("20") || log.contains("T"));
        System.setProperty("pkb_dolt_bin", installed.toString());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DoltCli.execute(new String[]{"dolt", "install", "--check", "--project=" + project},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(0, code);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("source=property"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("version=2.7.3"));
    }

    @Test
    void zipArchiveUnpacks() throws Exception {
        Path root = temp.resolve("zip-src");
        Files.createDirectories(root);
        Path archive = temp.resolve("dolt.zip");
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("dolt-windows-amd64/bin/dolt.exe"));
            zip.write("#!/bin/sh\nprintf '%s\\n' 'dolt version 2.7.3'\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path cache = temp.resolve("zip-cache");
        Path installed = DoltInstaller.installArchive(temp.resolve("project"), archive,
                DoltInstaller.sha256(archive), cache, "windows-amd64");
        assertEquals("dolt.exe", installed.getFileName().toString());
        assertTrue(Files.isRegularFile(installed));
    }

    @Test
    void pinnedCatalogHasFiveAssets() {
        assertEquals(5, DoltInstaller.pinnedPlatforms().size());
        for (String platform : DoltInstaller.pinnedPlatforms()) {
            DoltReleaseCatalog.Asset asset = DoltReleaseCatalog.require(platform);
            assertTrue(asset.url().contains("v2.7.3"));
            assertEquals(64, asset.sha256().length());
        }
    }

    private static Path script(Path path, String version) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nprintf '%s\\n' 'dolt version " + version + "'\n");
        path.toFile().setExecutable(true);
        return path;
    }

    private static Path tar(Path archive, String binaryName) throws Exception {
        Path pack = archive.getParent().resolve(archive.getFileName() + "-src");
        Path bin = pack.resolve("bundle/bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve(binaryName), "#!/bin/sh\nprintf '%s\\n' 'dolt version 2.7.3'\n");
        bin.resolve(binaryName).toFile().setExecutable(true);
        ProcessBuilder builder = new ProcessBuilder("tar", "-czf", archive.toAbsolutePath().toString(),
                "-C", pack.toAbsolutePath().toString(), "bundle");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        int code = process.waitFor();
        if (code != 0) throw new IllegalStateException(new String(output, StandardCharsets.UTF_8));
        return archive;
    }
}
