package io.cucumber.utilities;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Step;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class GeneralUtilities {


    public static void waitTime(Long time)
    {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getStringTimeStamp(){
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        return now.format(formatter);
    }





}
