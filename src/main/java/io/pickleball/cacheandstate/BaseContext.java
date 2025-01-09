package io.pickleball.cacheandstate;

import io.cucumber.core.runner.TestCase;

import java.util.*;


public class BaseContext implements io.cucumber.plugin.event.TestStep {

    protected TestCase parentTestCase = null;
    protected int descendantLevel = 0;
    protected int position = 0;

    @Override
    public String getCodeLocation() {
        return "";
    }

    @Override
    public UUID getId() {
        return null;
    }
}
