package tools.dscode.dolt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every Gradle dolt* task is a JavaExec of DoltCli. The task does not open a store. */
class DoltGradleForwardTest {
    @Test
    void everyDoltTaskIsAJavaExecOnDoltCli() throws Exception {
        String gradle = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("tasks\\.register\\('(dolt[A-Za-z0-9]+)'(?:,\\s*(JavaExec))?\\)").matcher(gradle);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
            assertEquals("JavaExec", matcher.group(2), matcher.group(1));
        }
        assertTrue(names.containsAll(List.of(
                "doltStatus", "doltHydrate", "doltExtract", "doltFeature", "doltHistory", "doltQuery",
                "doltRun", "doltRunClasspath", "doltEdit", "doltInstall", "doltServer", "doltSandbox", "doltMigrate"
        )), names.toString());
        for (String name : names) {
            int at = gradle.indexOf("tasks.register('" + name + "'");
            int next = gradle.indexOf("tasks.register(", at + 10);
            String block = next < 0 ? gradle.substring(at) : gradle.substring(at, next);
            assertTrue(block.contains("mainClass = 'tools.dscode.dolt.DoltCli'"), name);
            assertTrue(block.contains("classpath = doltForwardClasspath"), name);
            assertFalse(block.contains("sqlite"), name);
            assertFalse(block.contains("DriverManager"), name);
            assertFalse(block.contains("DoltStore"), name);
            assertFalse(block.contains("sql-server"), name);
        }
        assertTrue(gradle.contains("args = ['dolt', 'install']"));
        assertTrue(gradle.contains("args = ['dolt', 'server']"));
        assertTrue(gradle.contains("args = ['dolt', 'sandbox']"));
        assertTrue(gradle.contains("args = ['dolt', 'migrate']"));
        assertTrue(gradle.contains("args = ['dolt', 'run-classpath', '--out=' + out.get().asFile.absolutePath]"));
        assertTrue(gradle.contains("providers.gradleProperty('doltSandbox')"), gradle);
        assertFalse(gradle.contains("hasProperty('doltSandbox')"), gradle);
    }
}
