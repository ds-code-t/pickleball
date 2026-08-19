package tools.dscode.control.override;

@FunctionalInterface
public interface StepOverrideHandler {
    Object execute(StepOverrideContext context) throws Exception;
}
