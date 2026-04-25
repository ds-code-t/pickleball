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
    public static final String MATCH_BREAK ="\u2410";
    public static final String META_FLAG = "\u206AMETA";
    public static final String BOOK_END = "\u241F";
    public static final String NEXT_SIBLING_STEP  = "\u207ANXT";
    public static final String PARSER_FLAG  = "\u207A-F";
    public static final String STEP_PREFIX = "\uFEFF\u200B\u00A0\u200C";
    public static final String NON_GLUE_STEP_PREFIX = STEP_PREFIX + "\uFEFF";
    public static final String ROOT_STEP = NON_GLUE_STEP_PREFIX + "-ROOT-STEP-";
    public static final String SCENARIO_STEP = NON_GLUE_STEP_PREFIX + "SCENARIO: ";
    public static final String INFO_STEP = STEP_PREFIX + "INFO: ";
    public static final String HARD_ERROR_STEP = STEP_PREFIX + "FAIL ERROR: ";
    public static final String SOFT_ERROR_STEP = STEP_PREFIX + "ERROR: ";


    // visible characters
//    public static final String SYMBOL_FOR_NULL = "\u2400";
//    public static final String SYMBOL_FOR_START_OF_HEADING = "\u2401";
//    public static final String SYMBOL_FOR_START_OF_TEXT = "\u2402";
//    public static final String SYMBOL_FOR_END_OF_TEXT = "\u2403";
//    public static final String SYMBOL_FOR_END_OF_TRANSMISSION = "\u2404";
//    public static final String SYMBOL_FOR_ENQUIRY = "\u2405";
//    public static final String SYMBOL_FOR_ACKNOWLEDGE = "\u2406";
//    public static final String SYMBOL_FOR_BELL = "\u2407";
//    public static final String SYMBOL_FOR_BACKSPACE = "\u2408";
//    public static final String SYMBOL_FOR_HORIZONTAL_TABULATION = "\u2409";
//    public static final String SYMBOL_FOR_LINE_FEED = "\u240A";
//    public static final String SYMBOL_FOR_VERTICAL_TABULATION = "\u240B";
//    public static final String SYMBOL_FOR_FORM_FEED = "\u240C";
//    public static final String SYMBOL_FOR_CARRIAGE_RETURN = "\u240D";
//    public static final String SYMBOL_FOR_SHIFT_OUT = "\u240E";
//    public static final String SYMBOL_FOR_SHIFT_IN = "\u240F";
//    public static final String SYMBOL_FOR_DATA_LINK_ESCAPE = "\u2410";
//    public static final String SYMBOL_FOR_DEVICE_CONTROL_ONE = "\u2411";
//    public static final String SYMBOL_FOR_DEVICE_CONTROL_TWO = "\u2412";
//    public static final String SYMBOL_FOR_DEVICE_CONTROL_THREE = "\u2413";
//    public static final String SYMBOL_FOR_DEVICE_CONTROL_FOUR = "\u2414";
//    public static final String SYMBOL_FOR_NEGATIVE_ACKNOWLEDGE = "\u2415";
//    public static final String SYMBOL_FOR_SYNCHRONOUS_IDLE = "\u2416";
//    public static final String SYMBOL_FOR_END_OF_TRANSMISSION_BLOCK = "\u2417";
//    public static final String SYMBOL_FOR_CANCEL = "\u2418";
//    public static final String SYMBOL_FOR_END_OF_MEDIUM = "\u2419";
//    public static final String SYMBOL_FOR_SUBSTITUTE = "\u241A";
//    public static final String SYMBOL_FOR_ESCAPE = "\u241B";
//    public static final String SYMBOL_FOR_FILE_SEPARATOR = "\u241C";
//    public static final String SYMBOL_FOR_GROUP_SEPARATOR = "\u241D";
//    public static final String SYMBOL_FOR_RECORD_SEPARATOR = "\u241E";
//    public static final String SYMBOL_FOR_UNIT_SEPARATOR = "\u241F";
//    public static final String SYMBOL_FOR_SPACE = "\u2420";
//    public static final String SYMBOL_FOR_DELETE = "\u2421";

//    public static final String SNOW_MAN = "\u2603";
//    public static final String UMBRELLA = "\u2602";
//    public static final String SUN = "\u2600";
//    public static final String CLOUD = "\u2601";
//    public static final String SKULL_AND_CROSSBONES = "\u2620";
//    public static final String RADIOACTIVE = "\u2622";
//    public static final String BIOHAZARD = "\u2623";
//    public static final String SMILING_FACE = "\u263A";
//    public static final String HEART = "\u2665";
//    public static final String SPADE = "\u2660";
//    public static final String CLUB = "\u2663";
//    public static final String DIAMOND = "\u2666";
//    public static final String CHECK_MARK = "\u2705";
//    public static final String AIRPLANE = "\u2708";
//    public static final String CHECK = "\u2713";
//    public static final String SPARKLES = "\u2728";
//    public static final String SNOWFLAKE = "\u2744";
//    public static final String HIGH_VOLTAGE = "\u26A1";
//    public static final String PLAY_BUTTON = "\u25B6";
//    public static final String STAR = "\u2B50";
//    public static final String HOT_BEVERAGE = "\u2615";
//    public static final String TELEPHONE = "\u260E";
//    public static final String FROWNING_FACE = "\u2639";
//    public static final String BLACK_STAR = "\u2605";

//    public static final String SCENARIO_STEP = "SCENARIO:";
    // public static final String ROOT_STEP = "ROOTSTEP";
    public static final UUID SKIP_LOGGING = new UUID(0L, 0xFFL);

    public static final String defaultMatchFlag = "\u207A-DEFAULT_DEFINITION_";

//    public static final String TABLE_KEY = "\u206A_TABLE_KEY";
//    public static final String DocString_KEY = "\u206A_DOCSTRING_KEY";

    public static final String SCENARIO_TAGS = "SCENARIO TAGS";
    public static final String COMPONENT_TAGS = "COMPONENT TAGS";
}
