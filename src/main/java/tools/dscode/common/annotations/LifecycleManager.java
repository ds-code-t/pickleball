package tools.dscode.common.annotations;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class LifecycleManager {

    private final Map<Phase, List<LifecycleHandler>> handlersByPhase = new EnumMap<>(Phase.class);
    private volatile boolean initialized = false;

    public void fire(Phase phase) {
        ensureInitialized();
        List<LifecycleHandler> handlers = handlersByPhase.getOrDefault(phase, List.of());
        for (LifecycleHandler h : handlers) {
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
            scanAndBuildRegistry();
            initialized = true;
        }
    }

    private void scanAndBuildRegistry() {
        for (Phase p : Phase.values()) {
            handlersByPhase.put(p, new ArrayList<>());
        }

        try (ScanResult scan = new ClassGraph()
                .enableClassInfo()
                .enableMethodInfo()
                .enableAnnotationInfo()
                .ignoreClassVisibility()
                .scan()) {

            ClassInfoList classes = scan.getClassesWithMethodAnnotation(LifecycleHook.class.getName());
            for (ClassInfo ci : classes) {
                for (MethodInfo mi : ci.getDeclaredMethodInfo()) {
                    if (!mi.hasAnnotation(LifecycleHook.class.getName())) continue;
                    Method method = ci.loadClass().getDeclaredMethod(mi.getName());
                    if (!java.lang.reflect.Modifier.isStatic(method.getModifiers()) ||
                            method.getParameterCount() != 0) {
                        // skip invalid methods; you could also log or throw
                        continue;
                    }
                    LifecycleHook ann = method.getAnnotation(LifecycleHook.class);
                    Phase phase = ann.value();
                    int order = ann.order();
                    handlersByPhase.get(phase).add(new LifecycleHandler(phase, order, method));
                }
            }
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

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
