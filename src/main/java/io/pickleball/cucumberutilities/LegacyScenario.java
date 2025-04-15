package io.pickleball.cucumberutilities;


import cucumber.api.Result;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import cucumber.api.Scenario;
import io.cucumber.core.runner.TestCaseState;
import java.util.ArrayList;


public class LegacyScenario implements Scenario {
    private final TestCaseState testCaseState;

    public LegacyScenario(TestCaseState testCaseState) {
        this.testCaseState = testCaseState;
    }

    @Override
    public Collection<String> getSourceTagNames() {
        return testCaseState.getSourceTagNames();
    }

    @Override
    public Result.Type getStatus() {
        switch (testCaseState.getStatus()) {
            case PASSED:
                return Result.Type.PASSED;
            case FAILED:
                return Result.Type.FAILED;
            case SKIPPED:
                return Result.Type.SKIPPED;
            case PENDING:
                return Result.Type.PENDING;
            case UNDEFINED:
                return Result.Type.UNDEFINED;
            case AMBIGUOUS:
                return Result.Type.AMBIGUOUS;
            default:
                return Result.Type.UNDEFINED;
        }
    }

    @Override
    public boolean isFailed() {
        return testCaseState.isFailed();
    }

    @Override
    public void embed(byte[] data, String mimeType) {
        testCaseState.attach(data, mimeType, "Embedded Data");
    }

    @Override
    public void write(String text) {
        testCaseState.log(text);
    }

    @Override
    public String getName() {
        return testCaseState.getName();
    }

    @Override
    public String getId() {
        return testCaseState.getId();
    }

    @Override
    public String getUri() {
        return testCaseState.getUri().toString();
    }

    @Override
    public List<Integer> getLines() {
        Integer line = testCaseState.getLine();
        return line != null ? Collections.singletonList(line) : new ArrayList<>();
    }
}