package tools.dscode.control.api;

/** Text and optional Gherkin argument for one dynamically created step. */
public record DynamicStepSpec(String text, String argument) {
    public DynamicStepSpec {
        text = text == null ? "" : text;
        argument = argument == null ? "" : argument;
    }

    public DynamicStepSpec(String text) {
        this(text, "");
    }
}
