package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerMavenTestRunnerTest {
    @TempDir
    Path project;

    @Test
    void wrapperPrefersTheOsScriptThenTheWrapperJar() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = project.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(script, windows ? "@echo off\r\n" : "#!/bin/sh\n");
        List<String> command = ConsumerMavenTestRunner.command(project, "pkb_tags=@one");
        assertEquals(script.toString(), command.getFirst());
        assertTrue(command.contains("test"));

        Files.delete(script);
        Path jar = project.resolve(".mvn/wrapper/maven-wrapper.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "jar");
        List<String> jarCommand = ConsumerMavenTestRunner.command(project, "pkb_tags=@one");
        assertEquals("-jar", jarCommand.get(1));
        assertEquals(jar.toString(), jarCommand.get(2));

        Files.delete(jar);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ConsumerMavenTestRunner.wrapper(project)
        );
        assertTrue(failure.getMessage().contains("mvnw"));
        assertTrue(failure.getMessage().contains("machine-wide"));
    }
}
