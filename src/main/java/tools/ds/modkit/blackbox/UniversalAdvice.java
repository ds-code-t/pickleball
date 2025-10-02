//package tools.ds.modkit.blackbox;
//
//import net.bytebuddy.asm.Advice;
//import net.bytebuddy.implementation.bytecode.assign.Assigner;
//
//public final class UniversalAdvice {
//    private UniversalAdvice() {}
//
//    /** State passed from enter -> exit when we skip the original (bypass). */
//    static final class EnterState {
//        final boolean bypass; final Object bypassValue;
//        EnterState(boolean bypass, Object bypassValue) { this.bypass = bypass; this.bypassValue = bypassValue; }
//    }
//
//    /* ---------------------------- Methods ---------------------------- */
//
//    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
//    static EnterState enter(
//            @Advice.Origin("#t") String typeName,
//            @Advice.Origin("#m") String methodName,
//            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args
//    ) throws Throwable {
//        final String key = Plans.keyFor(typeName, methodName, args == null ? 0 : args.length);
//        final Plans.MethodPlan p = Registry.methodPlan(key);
//        if (p == null) return null;
//
//        // Decide bypass first
//        if (p.bypass != null && p.bypass.bypass(args)) {
//            Object value = (p.bypassReturn != null) ? p.bypassReturn.supply(args) : null;
//            return new EnterState(true, value); // non-null => skip original
//        }
//
//        // Mutate args before original
//        if (p.before != null) p.before.mutate(args);
//
//        return null; // proceed with original
//    }
//
//    @Advice.OnMethodExit(onThrowable = Throwable.class)
//    static void exit(
//            @Advice.Origin("#t") String typeName,
//            @Advice.Origin("#m") String methodName,
//            @Advice.AllArguments Object[] args,
//            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret,
//            @Advice.Thrown(readOnly = false) Throwable thrown,
//            @Advice.Enter EnterState state
//    ) throws Throwable {
//        final String key = Plans.keyFor(typeName, methodName, args == null ? 0 : args.length);
//        final Plans.MethodPlan p = Registry.methodPlan(key);
//        if (p == null) return;
//
//        if (state != null && state.bypass) {
//            // We skipped the original: supply value and clear exception
//            ret = state.bypassValue;
//            thrown = null;
//            return;
//        }
//
//        // If there was a throwable, let the plan adjust it; returning null swallows it
//        if (thrown != null && p.onThrow != null) {
//            thrown = p.onThrow.mutate(args, ret, thrown);
//        }
//
//        // Post-process return (even if an exception was thrown, ret may be undefined)
//        if (p.after != null) {
//            ret = p.after.mutate(args, ret, thrown);
//        }
//    }
//
//    /* ---------------------------- Constructors ---------------------------- */
//
//    @Advice.OnMethodEnter
//    static void ctorEnter(
//            @Advice.Origin("#t") String typeName,
//            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args
//    ) throws Throwable {
//        final String key = Plans.ctorKeyFor(typeName, args == null ? 0 : args.length);
//        final Plans.CtorPlan p = Registry.ctorPlan(key);
//        if (p == null) return;
//        if (p.before != null) p.before.mutate(args);
//    }
//
//    @Advice.OnMethodExit
//    static void ctorExit(
//            @Advice.Origin("#t") String typeName,
//            @Advice.This(optional = true) Object self
//    ) throws Throwable {
//        final String key = Plans.ctorKeyFor(typeName, /* argCount unknown on exit */ 0); // Lookup by fqcn only
//        // Simple heuristic: run afterInstance for any ctor plan on this type.
//        // If you want arg-count-specific afterInstance, store it elsewhere or pass via @Advice.Enter.
//        for (Plans.CtorPlan p : Registry.allCtorPlans()) {
//            if (p.fqcn.equals(typeName) && p.afterInstance != null) {
//                p.afterInstance.mutate(self);
//            }
//        }
//    }
//}
