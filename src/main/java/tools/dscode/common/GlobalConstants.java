package tools.dscode.common;

import java.util.UUID;

public class GlobalConstants {

    public static final String ALWAYS_RUN = "ALWAYS RUN";
    public static final String RUN_IF_SCENARIO_PASSING = "RUN IF SCENARIO PASSING";
    public static final String RUN_IF_SCENARIO_FAILED = "RUN IF SCENARIO FAILED";
    public static final String RUN_IF_SCENARIO_HARD_FAILED = "RUN IF SCENARIO HARD FAILED";
    public static final String RUN_IF_SCENARIO_SOFT_FAILED = "RUN IF SCENARIO SOFT FAILED";
    public static final String IGNORE_FAILURES = "IGNORE FAILURES";
    public static final String LOG_FAILURES_BUT_CONTINUE_SCENARIO = "LOG FAILURES BUT CONTINUE SCENARIO";
    public static final String RUN_IF_SCENARIO_FINISHED = "RUN IF SCENARIO FINISHED";
    public static final String AND_SCENARIO_COMPLETE = "(?: AND SCENARIO FINISHED)?";
    public static final String AND_IGNORE_FAILURES = "(?: AND IGNORE FAILURES)?";

    public static final String K_RUNTIME = "io.cucumber.core.runtime.Runtime";
    public static final String K_FEATUREPARSER = "io.cucumber.core.feature.FeatureParser";
    public static final String K_FEATURESUPPLIER = "io.cucumber.core.runtime.FeaturePathFeatureSupplier";
    public static final String K_TEST_CASE = "io.cucumber.core.runner.TestCase";
    public static final String K_PICKLE = "io.cucumber.core.gherkin.messages.GherkinMessagesPickle";
    public static final String K_SCENARIO = "io.cucumber.messages.types.Scenario";
    public static final String K_RUNNER = "io.cucumber.core.runner.Runner";
    public static final String K_JAVABACKEND = "io.cucumber.java.JavaBackend";

    // Tag mangling helpers
//    public static final String TAG_PREFIX = "@__COMPONENT_";
    public static final String COMPONENT_TAG_META_CHAR = "%";
//    public static final String COMPONENT_TAG_PREFIX =  "@__COMPONENT_";
    // public static final String SCENARIO_TAG_PREFIX = TAG_PREFIX +
    // "SCENARIO_";

    // Invisible meta flag injected into EncodingParser output then stripped at
    // PickleStep#getText
//    public static final String RETURN_STEP_FLAG = "^\u206A -_";
    public static final String MATCH_START = "\u206A ";
    public static final String META_FLAG = "\u206AMETA";
    public static final String BOOK_END = "\u241F";
    public static final String NEXT_SIBLING_STEP  = "\u207ANXT";
    public static final String PARSER_FLAG  = "\u207A-F";
    public static final String STEP_PREFIX = "\uFEFF\u200B\u00A0\u200C";
    public static final String ROOT_STEP = STEP_PREFIX + "-ROOT-STEP-";
    public static final String INFO_STEP = STEP_PREFIX + "INFO: ";
    public static final String HARD_ERROR_STEP = STEP_PREFIX + "FAIL ERROR: ";
    public static final String SOFT_ERROR_STEP = STEP_PREFIX + "ERROR: ";
//    public static final String SCENARIO_STEP = STEP_PREFIX + "SCENARIO: ";
    public static final String SCENARIO_STEP = "SCENARIO:";
    // public static final String ROOT_STEP = "ROOTSTEP";
    public static final UUID SKIP_LOGGING = new UUID(0L, 0xFFL);

    public static final String defaultMatchFlag = "\u207A-DEFAULT_DEFINITION_";

    public static final String TABLE_KEY = "\u206A_TABLE_KEY";
    public static final String DocString_KEY = "\u206A_DOCSTRING_KEY";

    public static final String SCENARIO_TAGS = "SCENARIO TAGS";
    public static final String COMPONENT_TAGS = "COMPONENT TAGS";
}
