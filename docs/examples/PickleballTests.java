package com.example.tests;

import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

/**
 * Project-level Pickleball test runner.
 *
 * Change this package and the glue package to match the consumer project.
 */
public class PickleballTests extends PickleballRunner {

    @Override
    public void globalTestDefaults() {
        PKB_props.glue("com.example.tests.steps");
        PKB_props.features("classpath:features");
        PKB_props.plugins("pretty");
    }
}
