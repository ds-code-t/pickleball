package tools.dscode.engine.runtime;

import io.cucumber.java.en.Given;

import java.nio.file.Files;
import java.nio.file.Path;

/** Glue the cold subprocess JVM loads. The step body records that JVM's pid. */
public class SubprocessProbe {
    @Given("the subprocess records its pid to {string}")
    public String record(String path) throws Exception {
        String pid = Long.toString(ProcessHandle.current().pid());
        Files.writeString(Path.of(path), pid);
        return pid;
    }

    @Given("the subprocess fails with {string}")
    public void fail(String message) {
        throw new IllegalStateException(message);
    }
}
