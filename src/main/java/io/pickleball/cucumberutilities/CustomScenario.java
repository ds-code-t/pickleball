package io.pickleball.cucumberutilities;


import io.cucumber.java.Scenario;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class CustomScenario implements cucumber.api.scenario {
    private final Scenario delegate;

    public CustomScenario(Scenario scenario) {
        this.delegate = scenario;
    }

    @Override
    public Collection<String> getSourceTagNames() {
        return delegate.getSourceTagNames();
    }

    @Override
    public Result.Type getStatus() {
        switch (delegate.getStatus()) {
            case FAILED:
                return Result.Type.FAILED;
            case PASSED:
                return Result.Type.PASSED;
            case SKIPPED:
                return Result.Type.SKIPPED;
            case PENDING:
            case UNDEFINED:
                return Result.Type.UNDEFINED;
            default:
                return Result.Type.UNDEFINED;
        }
    }

    @Override
    public boolean isFailed() {
        return delegate.isFailed();
    }

    @Override
    public void embed(byte[] data, String mimeType) {
        delegate.attach(data, mimeType, "Embedded Data");
    }

    @Override
    public void write(String text) {
        delegate.log(text);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public String getUri() {
        return delegate.getUri().toString();
    }

    @Override
    public List<Integer> getLines() {
        Integer line = delegate.getLine();
        return line != null ? Collections.singletonList(line) : Collections.emptyList();
    }
}