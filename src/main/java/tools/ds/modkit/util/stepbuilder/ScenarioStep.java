//package tools.ds.modkit.util.stepbuilder;
//
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.plugin.event.Argument;
//import io.cucumber.plugin.event.PickleStepTestStep;
//import io.cucumber.plugin.event.Step;
//import io.cucumber.plugin.event.StepArgument;
//
//import java.net.URI;
//import java.util.List;
//import java.util.UUID;
//
//public class ScenarioStep implements PickleStepTestStep {
//    public final Pickle delegate;
//
//    public ScenarioStep(Pickle pickle)
//    {
//        delegate = pickle;
//    }
//
//    @Override
//    public String getPattern() {
//        return delegate.getName();
//    }
//
//    @Override
//    public Step getStep() {
//        return null;
//    }
//
//    @Override
//    public List<Argument> getDefinitionArgument() {
//        return List.of();
//    }
//
//    @Override
//    public StepArgument getStepArgument() {
//        return null;
//    }
//
//    @Override
//    public int getStepLine() {
//        return delegate.getScenarioLocation().getLine();
//    }
//
//    @Override
//    public URI getUri() {
//        return delegate.getUri();
//    }
//
//    @Override
//    public String getStepText() {
//        return delegate.getName();
//    }
//
//    @Override
//    public String getCodeLocation() {
//        return "";
//    }
//
//    @Override
//    public UUID getId() {
//        return UUID.fromString(delegate.getId());
//    }
//}
