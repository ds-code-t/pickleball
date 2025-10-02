//// tools/ds/modkit/override/StepLoopOverride.java
//package tools.ds.modkit.override;
//
//import net.bytebuddy.implementation.bind.annotation.*;
//
//import java.lang.reflect.Constructor;
//import java.lang.reflect.Method;
//import java.time.Duration;
//import java.time.Instant;
//import java.util.List;
//import java.util.UUID;
//import java.util.concurrent.Callable;
//
//public final class StepLoopOverride {
//    private StepLoopOverride() {}
//
//    @RuntimeType
//    public static Object intercept(
//            @This Object self,
//            @FieldValue("testSteps") List<?> steps,
//            @Argument(0) Object bus,                       // io.cucumber.core.eventbus.EventBus
//            @SuperCall Callable<?> zuper                   // call original if desired
//    ) throws Exception {
//        if (!useCustomLoop(steps)) {
//            return zuper.call(); // keep original behavior
//        }
//
//        // --- reflect members we need from TestCase ---
//        Object executionMode = getField(self, "executionMode");
//        List<?> beforeHooks  = safeList(getField(self, "beforeHooks"));
//        List<?> afterHooks   = safeList(getField(self, "afterHooks"));
//
//        // --- emit start messages like original run() ---
//        invoke(self, "emitTestCaseMessage", bus);
//
//        Instant start = (Instant)  invoke(bus, "getInstant");
//        UUID    execId = (UUID)    invoke(bus, "generateId");
//        invoke(self, "emitTestCaseStarted", bus, start, execId);
//
//        Object state = newTestCaseState(self, bus, execId);
//        Object nextMode = executionMode;
//
//        // before hooks
//        for (Object h : beforeHooks) {
//            Object res = invoke(h, "run", self, bus, state, executionMode);
//            nextMode   = invoke(res, "next", nextMode);
//        }
//
//        // -------- your custom loop with skip/rewind logic --------
//        for (int i = 0; steps != null && i < steps.size();) {
//            Object step = steps.get(i);
//
//            if (shouldSkip(step)) { i++; continue; }
//            if (shouldRewind(step)) { i = Math.max(0, i - 1); continue; }
//
//            Object res = invoke(step, "run", self, bus, state, nextMode);
//            nextMode   = invoke(res, "next", nextMode);
//            i++;
//        }
//        // ---------------------------------------------------------
//
//        // after hooks
//        for (Object h : afterHooks) {
//            Object res = invoke(h, "run", self, bus, state, executionMode);
//            nextMode   = invoke(res, "next", nextMode);
//        }
//
//        // finish
//        Instant stop = (Instant) invoke(bus, "getInstant");
//        Duration dur = Duration.between(start, stop);
//
//        Object statusEnum  = invoke(invoke(state, "getStatus"), "name"); // String
//        Class<?> statusCls = load(self, "io.cucumber.plugin.event.Status");
//        Object status      = java.lang.Enum.valueOf((Class<Enum>) statusCls, (String) statusEnum);
//
//        Object error       = invoke(state, "getError");
//        Class<?> resultCls = load(self, "io.cucumber.plugin.event.Result");
//        Object  result     = resultCls.getConstructor(statusCls, Duration.class, Throwable.class)
//                .newInstance(status, dur, error);
//
//        invoke(self, "emitTestCaseFinished", bus, execId, stop, result);
//        return null; // run(EventBus) is void
//    }
//
//    // ---- knobs you implement however you like ----
//    private static boolean useCustomLoop(List<?> steps) { return true; }
//    private static boolean shouldSkip(Object step)       { return false; }
//    private static boolean shouldRewind(Object step)     { return false; }
//
//    // ---- tiny reflection helpers (no JPMS sugarcoating) ----
//    private static Object newTestCaseState(Object self, Object bus, UUID execId) throws Exception {
//        ClassLoader cl = self.getClass().getClassLoader();
//        Class<?> cls   = Class.forName("io.cucumber.core.runner.TestCaseState", false, cl);
//        Class<?> busCl = Class.forName("io.cucumber.core.eventbus.EventBus", false, cl);
//        Class<?> tcCl  = Class.forName("io.cucumber.core.runner.TestCase", false, cl);
//        Constructor<?> ctor = cls.getDeclaredConstructor(busCl, UUID.class, tcCl);
//        if (!ctor.canAccess(null)) ctor.setAccessible(true);
//        return ctor.newInstance(bus, execId, self);
//    }
//
//    private static Object getField(Object target, String name) throws Exception {
//        var f = findField(target.getClass(), name);
//        if (!f.canAccess(target)) f.setAccessible(true);
//        return f.get(target);
//    }
//
//    private static Object invoke(Object target, String name, Object... args) throws Exception {
//        Method m = findMethod(target.getClass(), name, args.length);
//        if (!m.canAccess(target)) m.setAccessible(true);
//        return m.invoke(target, args);
//    }
//
//    private static java.lang.reflect.Field findField(Class<?> c, String name) throws NoSuchFieldException {
//        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
//            try { return k.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
//        }
//        throw new NoSuchFieldException(name);
//    }
//
//    private static Method findMethod(Class<?> c, String name, int argc) throws NoSuchMethodException {
//        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
//            for (Method m : k.getDeclaredMethods()) {
//                if (m.getName().equals(name) && m.getParameterCount() == argc) return m;
//            }
//        }
//        throw new NoSuchMethodException(name + "/" + argc);
//    }
//
//    @SuppressWarnings("unchecked")
//    private static <T> List<T> safeList(Object o) { return (o instanceof List) ? (List<T>) o : java.util.List.of(); }
//
//    private static Class<?> load(Object anchor, String name) throws ClassNotFoundException {
//        return Class.forName(name, false, anchor.getClass().getClassLoader());
//    }
//}
