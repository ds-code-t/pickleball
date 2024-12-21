package io.pickleball.configs;

import java.util.List;
import java.util.regex.Pattern;

public class Constants {
    public static final char flag1 = '\u2401';
    public static final char flag2 = '\u2402';
    public static final String sFlag2 = Pattern.quote(String.valueOf(flag2));
    public static final char flag3 = '\u2403';


    public static final String SCENARIO_TAGS = "Scenario Tags";
    public static final String COMPONENT_PATH = "Component Path";
//    public static final String COMPONENT_EXAMPLES = "* COMPONENT_EXAMPLES" + stepFlag;
//    public static final String PASSED_EXAMPLES = stepFlag + "PASSED_EXAMPLES";
//    public static final String COMPONENT_TAG =  "@_cOmponent-q8437qasd";


    public static final String PRIORITY_TAG =  "@priority-";


    public static final List<String> PREFIXES = List.of("given", "when", "then" , "and", "but");

}
