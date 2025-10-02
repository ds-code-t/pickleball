package tools.ds;


import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "tools.ds.calc")
@ConfigurationParameter(
        key = PLUGIN_PROPERTY_NAME,
        // built-ins + our custom plugin (fully-qualified name)
//        value = "pretty, summary, tools.ds.modkit.BootstrapPlugin"
        value = "pretty, summary, tools.ds.modkit.BootstrapPlugin"
)
//@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @wip")
public class RunCukesTest {
    // no code needed; annotations drive discovery & execution
}
