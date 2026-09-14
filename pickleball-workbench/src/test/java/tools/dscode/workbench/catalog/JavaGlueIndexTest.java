package tools.dscode.workbench.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaGlueIndexTest {
    @TempDir
    Path project;

    @Test
    void findsConsumerGivenAnnotation() throws Exception {
        Path java = project.resolve("src/test/java/com/example/Steps.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, """
                package com.example;
                import io.cucumber.java.en.Given;
                public class Steps {
                    @Given("a user is logged in")
                    public void loggedIn() {}
                }
                """);
        JavaGlueIndex index = JavaGlueIndex.scan(project);
        JavaGlueIndex.Match match = index.find("Given a user is logged in").orElseThrow();
        assertEquals("com.example.Steps", match.className());
        assertEquals("loggedIn", match.methodName());
        assertTrue(match.snippet().contains("@Given"));
    }
}