package tools.dscode.common.annotations;

import java.lang.reflect.Method;

class LifecycleHandler {
    final Phase phase;
    final int order;
    final Method method;

    LifecycleHandler(Phase phase, int order, Method method) {
        this.phase = phase;
        this.order = order;
        this.method = method;
    }
}