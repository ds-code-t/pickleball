package tools.dscode;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.core.options.Constants.GLUE_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
//@SelectPackages("features") // <-- instead of @SelectClasspathResource("features")
@ConfigurationParameter(
        key = GLUE_PROPERTY_NAME,
        value = "tools.dscode.steps,tools.dscode.zzzqqq")
//@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@RunnerClassTag")
public class RunCucumberTest {}
