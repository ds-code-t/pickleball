package tools.dscode.common.annotations;



public class Engine {

    private final LifecycleManager lifecycle = new LifecycleManager();

    public void run() {
        lifecycle.fire(Phase.BEFORE_RUN);
        try {
            // ... main work ...
            lifecycle.fire(Phase.AFTER_RUN);
        } catch (Exception e) {
            lifecycle.fire(Phase.ON_ERROR);
            throw e;
        }
    }
}
