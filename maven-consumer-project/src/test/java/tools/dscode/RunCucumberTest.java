package tools.dscode;

import org.junit.platform.suite.api.*;
import static io.cucumber.core.options.Constants.GLUE_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectPackages("features") // <-- instead of @SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "tools.dscode.steps")
public class RunCucumberTest {}
