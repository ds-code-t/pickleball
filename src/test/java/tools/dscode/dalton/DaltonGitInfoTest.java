package tools.dscode.dalton;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonGitInfoTest {
    @TempDir
    Path project;

    @Test
    void statusShowsCommitRemoteWithoutUserInfoAndADirtyChange() throws Exception {
        Assumptions.assumeTrue(gitExists(), "git binary is absent");
        String feature = "Feature: One\n  Scenario: A\n    Given ready\n";
        Path file = project.resolve("features/one.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, feature);
        Files.writeString(project.resolve(".gitignore"), ".pickleball/\n");
        git("init");
        git("config", "user.email", "dalton@example.com");
        git("config", "user.name", "Dalton");
        git("add", "features/one.feature", ".gitignore");
        git("commit", "-m", "one");
        git("remote", "add", "origin", "https://user:tok@github.com/o/r.git");
        assertEquals(0, dalton("hydrate", "--project=" + project).code);
        Capture clean = dalton("status", "--json", "--project=" + project);
        assertEquals(0, clean.code, clean.out + clean.err);
        assertTrue(clean.out.contains("\"githubRepo\":\"o/r\""), clean.out);
        assertTrue(clean.out.contains("\"remote\":\"https://github.com/o/r.git\""), clean.out);
        assertFalse(clean.out.contains("tok"), clean.out);
        assertTrue(clean.out.contains("\"dirty\":false"), clean.out);
        String head = git("rev-parse", "HEAD").trim();
        assertTrue(clean.out.contains("\"commit\":\"" + head + "\""), clean.out);
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            DaltonFiles.Row row = DaltonFiles.find(database.connection(), "features/one.feature");
            String hashed = git("hash-object", "features/one.feature").trim();
            assertEquals(hashed, row.gitBlob());
        }
        Files.writeString(file, feature + "\n");
        Capture dirty = dalton("status", "--json", "--project=" + project);
        assertTrue(dirty.out.contains("\"dirty\":true"), dirty.out);
        assertTrue(dirty.out.contains("\"changed\":1"), dirty.out);
    }

    @Test
    void missingGitLeavesFieldsEmptyAndWarnsOnce() throws Exception {
        Path bare = project.resolve("plain");
        Files.createDirectories(bare);
        java.io.ByteArrayOutputStream errBuffer = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream outBuffer = new java.io.ByteArrayOutputStream();
        java.io.PrintStream previous = System.err;
        System.setErr(new java.io.PrintStream(errBuffer));
        try {
            int code = DaltonCli.execute(
                    new String[] {"dalton", "status", "--json", "--project=" + bare},
                    new java.io.PrintStream(outBuffer),
                    new java.io.PrintStream(errBuffer));
            String out = outBuffer.toString(StandardCharsets.UTF_8);
            String err = errBuffer.toString(StandardCharsets.UTF_8);
            assertEquals(0, code, out + err);
            assertTrue(out.contains("\"commit\":\"\""), out);
            assertTrue(out.contains("\"githubRepo\":\"\""), out);
            assertTrue(count(err, "dalton git unavailable:") <= 1, err);
        } finally {
            System.setErr(previous);
        }
    }

    private static boolean gitExists() throws Exception {
        Process process = new ProcessBuilder("git", "--version").start();
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private String git(String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).directory(project.toFile()).start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("git timed out");
        }
        assertEquals(0, process.exitValue(), err);
        return out;
    }

    private Capture dalton(String... args) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        tokens.add("dalton");
        tokens.addAll(java.util.List.of(args));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = DaltonCli.execute(tokens.toArray(String[]::new), new java.io.PrintStream(out), new java.io.PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from >= 0) {
            int at = text.indexOf(token, from);
            if (at < 0) break;
            found++;
            from = at + token.length();
        }
        return found;
    }

    private record Capture(int code, String out, String err) {
    }
}
