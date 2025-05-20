package io.pickleball.cucumberutilities;

import static io.cucumber.gherkin.StringUtils.TRIM;
import static io.pickleball.stringutilities.Constants.sFlag2;

public class SourceParsing {

    public static String pretrim(String s) {
        return TRIM.matcher(s).replaceAll("") + sFlag2;
    }

}
