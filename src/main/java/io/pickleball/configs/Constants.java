package io.pickleball.configs;

import java.util.List;
import java.util.regex.Pattern;

public class Constants {
    public static final char flag1 = '\u2401';
    public static final char flag2 = '\u2402';
    public static final char flag3 = '\u2403';
    public static final String sFlag2 = Pattern.quote(String.valueOf(flag2));

    public static final String substitute = Pattern.quote(String.valueOf('\u2404'));
    public static final String boundry = Pattern.quote(String.valueOf('\u2405'));

    public static final char orSubstitue = '\u2404';
    public static final String metaSeparator = "\\s*" +orSubstitue + "\\s*";
    public static final String START_FLAG = flag3 + "START" + flag3;
    public static final String END_FLAG = flag3 + "END" + flag3;


    public static final String SCENARIO_TAGS = "Scenario Tags";
    public static final String COMPONENT_PATH = "Component Path";


    public static final String PRIORITY_TAG = "@priority-";


    public static final List<String> PREFIXES = List.of("given", "when", "then", "and", "but");


    public final static String SCENARIO = "SCENARIO";
    public final static String TEST = "TEST";


    public static final String COMPLETES = "ENDS";
    public static final String COMPLETED = "ENDED";
    public static final String PASSES = "PASSES";
    public static final String PASSED = "PASSED";
    public static final String FAILS = "FAILS";
    public static final String FAILED = "FAILED";
    public static final String SOFT_FAILS = "SOFT FAILS";
    public static final String SOFT_FAILED = "SOFT FAILED";
    public static final String HARD_FAILS = "HARD FAILS";
    public static final String HARD_FAILED = "HARD FAILED";


    public static final List<String> STATE_LIST = List.of(
            COMPLETES, COMPLETED, PASSES, PASSED, SOFT_FAILED, SOFT_FAILS, HARD_FAILED, HARD_FAILS, FAILS, FAILED
    );

//    public static final String ALL_STATES = String.join("|", STATE_LIST);

    public static final Pattern QUOTED_STRING_REGEX = Pattern.compile(
            "\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|`(?:\\\\.|[^`])*`");


}
