package tools.dscode.common.annotations;

import tools.dscode.testengine.PickleballRunner;
import tools.dscode.testengine.DynamicSuiteBootstrap;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class LifecycleManager {

    private final Map<Phase, List<LifecycleHandler>> handlersByPhase = new EnumMap<>(Phase.class);
    private volatile boolean initialized = false;

    public void fire(Phase phase) {
        ensureInitialized();
        for (LifecycleHandler h : handlersByPhase.getOrDefault(phase, List.of())) {
            try {
                h.method.invoke(null);
            } catch (Exception e) {
                throw new RuntimeException("Error invoking lifecycle hook: " + h.method, e);
            }
        }
    }

    private void ensureInitialized() {
        if (initialized) return;
        synchronized (this) {
            if (initialized) return;
            buildRegistry();
            initialized = true;
        }
    }

    private void buildRegistry() {
        for (Phase p : Phase.values()) {
            handlersByPhase.put(p, new ArrayList<>());
        }

        Class<? extends PickleballRunner> suiteClass = DynamicSuiteBootstrap.getDiscoveredSuiteClass();
        if (suiteClass == null) {
            finalizeRegistry();
            return;
        }

        for (Method method : suiteClass.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() != 0) continue;

            LifecycleHook ann = method.getAnnotation(LifecycleHook.class);
            if (ann == null) continue;

            method.setAccessible(true);
            handlersByPhase
                    .get(ann.value())
                    .add(new LifecycleHandler(ann.value(), ann.order(), method));
        }

        finalizeRegistry();
    }

    private void finalizeRegistry() {
        for (Phase phase : Phase.values()) {
            List<LifecycleHandler> list = handlersByPhase.get(phase);
            list.sort((a, b) -> {
                boolean ea = a.order != Integer.MIN_VALUE;
                boolean eb = b.order != Integer.MIN_VALUE;
                if (ea && eb) return Integer.compare(a.order, b.order);
                if (ea) return -1;
                if (eb) return 1;
                return a.method.getName().compareTo(b.method.getName());
            });
            handlersByPhase.put(phase, List.copyOf(list));
        }
    }
}