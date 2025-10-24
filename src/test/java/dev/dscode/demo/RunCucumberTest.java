package dev.dscode.demo;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.FEATURES_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features") // looks under src/test/resources/features
@ConfigurationParameter(key = FEATURES_PROPERTY_NAME, value = "classpath:features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME,     value = "dev.dscode.demo.steps")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@smoke and not @wip")
@ConfigurationParameter(
        key = PLUGIN_PROPERTY_NAME,
        value = "pretty,summary,html:target/cucumber-report.html,junit:target/cucumber-report.xml"
)
public class RunCucumberTest { }
