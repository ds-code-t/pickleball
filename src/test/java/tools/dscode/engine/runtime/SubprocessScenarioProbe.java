package tools.dscode.engine.runtime;

import io.cucumber.java.en.Given;
import tools.dscode.Values;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Glue for one multi-step scenario in the cold child. The instance field and the
 * scenario object must still be there on the next step of that same scenario.
 */
public class SubprocessScenarioProbe {
    private String held;

    @Given("the subprocess scenario stores {string}")
    public String store(String value) {
        held = value;
        Values.saveScenarioObject("held", value);
        return Long.toString(ProcessHandle.current().pid());
    }

    @Given("the subprocess scenario reads into {string}")
    public String read(String path) throws Exception {
        Object saved = Values.getScenarioObject("held");
        String line = String.valueOf(held) + "|" + String.valueOf(saved) + "|" + ProcessHandle.current().pid();
        Files.writeString(Path.of(path), line);
        return line;
    }

    @Given("the subprocess scenario fails with {string}")
    public void fail(String message) {
        throw new IllegalStateException(message + " pid=" + ProcessHandle.current().pid());
    }
}
