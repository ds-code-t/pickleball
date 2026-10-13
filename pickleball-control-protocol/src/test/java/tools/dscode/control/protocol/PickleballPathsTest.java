package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickleballPathsTest {
    @TempDir
    Path temp;

    @Test
    void classpathPrefixesShareOneResource() {
        for (String configured : new String[] {
                "features",
                "file:features",
                "FILE:features",
                "classpath:features",
                "Classpath:features"
        }) {
            PickleballPaths.Parsed parsed = PickleballPaths.parse(configured);
            assertEquals(PickleballPaths.Kind.CLASSPATH, parsed.kind(), configured);
            assertEquals("features", parsed.classpathResource(), configured);
            assertEquals("classpath:features", PickleballPaths.toCucumberFeatureUri(configured), configured);
        }
    }

    @Test
    void fileSlashIsAClasspathResource() {
        PickleballPaths.Parsed parsed = PickleballPaths.parse("file:/opt/x/configs");
        assertEquals(PickleballPaths.Kind.CLASSPATH, parsed.kind());
        assertEquals("opt/x/configs", parsed.classpathResource());
        assertEquals("classpath:opt/x/configs", PickleballPaths.toCucumberFeatureUri("file:/opt/x/configs"));
    }

    @Test
    void slashPathsAreClasspathResources() {
        assertEquals("qa/configs", PickleballPaths.parse("qa/configs").classpathResource());
        assertEquals(
                "src/test/resources/qa/configs",
                PickleballPaths.parse("src/test/resources/qa/configs").classpathResource()
        );
        assertEquals(
                "classpath:src/test/resources/qa/configs",
                PickleballPaths.toCucumberFeatureUri("src/test/resources/qa/configs")
        );
    }

    @Test
    void wdFileRelativeStaysARelativeCucumberFile() {
        PickleballPaths.Parsed parsed = PickleballPaths.parse("wd-file:features");
        assertEquals(PickleballPaths.Kind.FILE_SYSTEM, parsed.kind());
        assertFalse(parsed.absoluteFile());
        assertEquals(temp.resolve("features").normalize(), PickleballPaths.toFile(parsed, temp));
        assertEquals("file:features", PickleballPaths.toCucumberFeatureUri("wd-file:features"));
        assertEquals("file:features", PickleballPaths.toCucumberFeatureUri("WD-FILE:features"));
    }

    @Test
    void wdFileAbsoluteIsTheFilesystemRoot() {
        PickleballPaths.Parsed parsed = PickleballPaths.parse("wd-file:/opt/x/configs");
        assertTrue(parsed.absoluteFile());
        assertEquals(Path.of("/opt/x/configs").normalize(), PickleballPaths.toFile(parsed, temp));
        assertEquals("file:/opt/x/configs", PickleballPaths.toCucumberFeatureUri("wd-file:/opt/x/configs"));
    }

    @Test
    void bareSlashPathIsAbsoluteOnEveryOperatingSystem() {
        PickleballPaths.Parsed parsed = PickleballPaths.parse("/opt/x/configs");
        assertEquals(PickleballPaths.Kind.FILE_SYSTEM, parsed.kind());
        assertTrue(parsed.absoluteFile());
        assertEquals(Path.of("/opt/x/configs").normalize(), PickleballPaths.toFile(parsed, temp));
        assertEquals("file:/opt/x/configs", PickleballPaths.toCucumberFeatureUri("/opt/x/configs"));
    }

    @Test
    void bareAbsolutePathStaysOnTheFilesystem() throws Exception {
        Path feature = Files.createFile(temp.resolve("sample.feature"));
        String text = feature.toAbsolutePath().normalize().toString();
        PickleballPaths.Parsed parsed = PickleballPaths.parse(text);
        assertEquals(PickleballPaths.Kind.FILE_SYSTEM, parsed.kind());
        assertTrue(parsed.absoluteFile());
        assertEquals(
                "file:" + text.replace('\\', '/'),
                PickleballPaths.toCucumberFeatureUri(text)
        );
    }

    @Test
    void cucumberTranslationIsAppliedOnce() {
        String once = PickleballPaths.toCucumberFeatureUri("wd-file:features");
        assertEquals("file:features", once);
        assertEquals("classpath:features", PickleballPaths.toCucumberFeatureUri(once));
        assertEquals(
                "classpath:features,file:features",
                PickleballPaths.toCucumberFeatures("features, wd-file:features")
        );
    }

    @Test
    void blankIsAnEmptyClasspathResource() {
        PickleballPaths.Parsed parsed = PickleballPaths.parse(null);
        assertEquals(PickleballPaths.Kind.CLASSPATH, parsed.kind());
        assertEquals("", parsed.classpathResource());
        assertNull(parsed.fileText());
    }
}
