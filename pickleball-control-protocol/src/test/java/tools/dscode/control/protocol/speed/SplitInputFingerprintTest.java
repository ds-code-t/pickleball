package tools.dscode.control.protocol.speed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SplitInputFingerprintTest {
    @TempDir
    Path root;

    @Test
    void resourceJavaAndBuildFingerprintsMoveIndependently() throws Exception {
        Path project = root.resolve("project");
        Files.createDirectories(project.resolve("src/test/resources/features"));
        Files.createDirectories(project.resolve("src/test/java"));
        Files.writeString(project.resolve("pom.xml"), "<project>one</project>\n");
        Files.writeString(project.resolve("src/test/resources/features/a.feature"), "Feature: one\n");
        Files.writeString(project.resolve("src/test/java/A.java"), "class A {}\n");
        SplitInputFingerprint.Parts first = SplitInputFingerprint.capture(project);
        SplitInputFingerprint.store(root, first);
        assertEquals(first, SplitInputFingerprint.load(root));
        assertEquals(SplitInputFingerprint.Mode.SKIP, SplitInputFingerprint.decide(first, SplitInputFingerprint.capture(project)));

        Files.writeString(project.resolve("src/test/resources/features/a.feature"), "Feature: two\n");
        SplitInputFingerprint.Parts resources = SplitInputFingerprint.capture(project);
        assertEquals(SplitInputFingerprint.Mode.RESOURCES, SplitInputFingerprint.decide(first, resources));
        assertEquals(first.java(), resources.java());
        assertEquals(first.build(), resources.build());

        Files.writeString(project.resolve("src/test/java/A.java"), "class A { int x; }\n");
        SplitInputFingerprint.Parts java = SplitInputFingerprint.capture(project);
        assertEquals(SplitInputFingerprint.Mode.FULL, SplitInputFingerprint.decide(resources, java));

        Files.writeString(project.resolve("pom.xml"), "<project>two</project>\n");
        SplitInputFingerprint.Parts build = SplitInputFingerprint.capture(project);
        assertNotEquals(java.build(), build.build());
        assertEquals(SplitInputFingerprint.Mode.FULL, SplitInputFingerprint.decide(java, build));
        assertEquals(SplitInputFingerprint.Mode.SKIP, SplitInputFingerprint.decide(null, build));
    }

    @Test
    void racySameSizeRewriteChangesTheFingerprint() throws Exception {
        Path project = root.resolve("racy");
        Path file = project.resolve("src/test/resources/features/a.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Feature: one\n");
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        Files.setLastModifiedTime(file, now);
        String first = SplitInputFingerprint.capture(project).resources();
        Files.writeString(file, "Feature: two\n");
        Files.setLastModifiedTime(file, now);
        String second = SplitInputFingerprint.capture(project).resources();
        assertNotEquals(first, second);

        FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 60_000L);
        Files.writeString(file, "Feature: one\n");
        Files.setLastModifiedTime(file, old);
        String outside = SplitInputFingerprint.capture(project).resources();
        Files.writeString(file, "Feature: two\n");
        Files.setLastModifiedTime(file, old);
        assertEquals(outside, SplitInputFingerprint.capture(project).resources());
    }

    @Test
    void storedRacyTokenStaysWhenTheWindowCloses() throws Exception {
        Path project = root.resolve("stable");
        Path file = project.resolve("src/test/resources/features/a.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "Feature: one\n");
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(file, FileTime.fromMillis(now));
        SplitInputFingerprint.Observation first = SplitInputFingerprint.observe(project, root, now);
        SplitInputFingerprint.store(root, first);
        SplitInputFingerprint.Observation later = SplitInputFingerprint.observe(project, root, now + 5_000L);
        assertEquals(first.parts(), later.parts());
        assertEquals(SplitInputFingerprint.Mode.SKIP, SplitInputFingerprint.decide(first.parts(), later.parts()));

        Files.writeString(file, "Feature: two\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(now));
        SplitInputFingerprint.Observation rewritten = SplitInputFingerprint.observe(project, root, now);
        assertEquals(SplitInputFingerprint.Mode.RESOURCES, SplitInputFingerprint.decide(first.parts(), rewritten.parts()));
    }

    @Test
    void listedFilesUseTheSameStamp() throws Exception {
        Path project = root.resolve("listed");
        Path file = project.resolve("src/test/java/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class A {}\n");
        String direct = SplitInputFingerprint.digestFiles(project, List.of(file));
        assertEquals(direct, SplitInputFingerprint.capture(project).java());
        assertFalse(direct.isBlank());
        assertTrue(SplitInputFingerprint.generationHash(SplitInputFingerprint.capture(project)).length() >= 32);
    }
}
