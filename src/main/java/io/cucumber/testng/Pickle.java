package io.cucumber.testng;

import io.pickleball.executions.ExecutionConfig;
import org.apiguardian.api.API;

import java.net.URI;
import java.util.List;

import static io.pickleball.configs.Constants.PRIORITY_TAG;

/**
 * Wraps CucumberPickle to avoid exposing it as part of the public api.
 */
@API(status = API.Status.STABLE)
public final class Pickle {

    private final io.cucumber.core.gherkin.Pickle pickle;

    Pickle(io.cucumber.core.gherkin.Pickle pickle) {
        this.pickle = pickle;
    }

    public io.cucumber.core.gherkin.Pickle getPickle() {
        return pickle;
    }

    public String getName() {
        return pickle.getName();
    }

    public int getScenarioLine() {
        return pickle.getScenarioLocation().getLine();
    }

    public int getLine() {
        return pickle.getLocation().getLine();
    }

    public List<String> getTags() {
        return pickle.getTags();
    }

    public int getPriority () {
        return ExecutionConfig.getPriority(pickle.getTags(), String.valueOf(getUri()) + " Line: " + getLine());
    }


    public URI getUri() {
        return pickle.getUri();
    }

}
