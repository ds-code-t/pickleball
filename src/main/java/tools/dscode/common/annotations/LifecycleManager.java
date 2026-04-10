package tools.dscode.common.annotations;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        List<String> scanRoots = consumerClasspathDirectories();
        if (scanRoots.isEmpty()) {
            finalizeRegistry();
            return;
        }

        try (ScanResult scan = new ClassGraph()
                .overrideClasspath(scanRoots)
                .disableNestedJarScanning()
                .enableClassInfo()
                .enableMethodInfo()
                .enableAnnotationInfo()
                .scan()) {

            ClassInfoList classes = scan.getClassesWithMethodAnnotation(LifecycleHook.class.getName());
            for (ClassInfo ci : classes) {
                Class<?> clazz = ci.loadClass();

                for (MethodInfo mi : ci.getDeclaredMethodInfo()) {
                    if (!mi.hasAnnotation(LifecycleHook.class.getName())) continue;
                    if (!mi.isStatic()) continue;
                    if (mi.getParameterInfo().length != 0) continue;

                    Method method = clazz.getDeclaredMethod(mi.getName());
                    LifecycleHook ann = method.getAnnotation(LifecycleHook.class);
                    if (ann == null) continue;

                    Phase phase = ann.value();
                    int order = ann.order();
                    handlersByPhase.get(phase).add(new LifecycleHandler(phase, order, method));
                }
            }
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
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

    private static List<String> consumerClasspathDirectories() {
        String cp = System.getProperty("java.class.path", "");
        if (cp.isBlank()) return List.of();

        Set<String> dirs = new LinkedHashSet<>();
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry == null || entry.isBlank()) continue;

            File f = new File(entry).getAbsoluteFile();
            if (!f.isDirectory()) continue;

            String path = normalizePath(f);
            if (looksLikeConsumerOutputDir(path)) {
                dirs.add(path);
            }
        }
        return new ArrayList<>(dirs);
    }

    private static boolean looksLikeConsumerOutputDir(String path) {
        String p = path.replace('\\', '/');
        return p.endsWith("/target/classes")
                || p.endsWith("/target/test-classes")
                || p.contains("/build/classes/java/main")
                || p.contains("/build/classes/java/test")
                || p.contains("/out/production/")
                || p.contains("/out/test/");
    }

    private static String normalizePath(File f) {
        return f.getAbsolutePath();
    }
}