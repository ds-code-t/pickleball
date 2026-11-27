package glue;

import io.cucumber.java.en.Given;
import tools.dscode.engine.match.ForeignStepHosts;
import tools.dscode.engine.match.JavaStepHost;

/** Step that loads project glue through the scenario's warm loader. */
public class ScenarioProbe {
    @Given("the warm scenario loads glue into {string}")
    public void load(String path) throws Exception {
        ClassLoader loader = JavaStepHost.selectLoader("glue.Mark", ForeignStepHosts.project());
        Class<?> type = Class.forName("glue.Mark", true, loader);
        type.getMethod("write", String.class).invoke(null, path);
    }
}
