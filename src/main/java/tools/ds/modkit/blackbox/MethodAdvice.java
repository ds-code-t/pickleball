// src/main/java/tools/ds/modkit/blackbox/MethodAdvice.java
package tools.ds.modkit.blackbox;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import tools.ds.modkit.util.CallScope;

public final class MethodAdvice {
    private MethodAdvice() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    static Object[] enter(
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName,
            @Advice.This(optional = true) Object self, // <-- capture target
            @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args
    ) throws Throwable {
        CallScope.setSelf(self); // <-- stash for callbacks

        final String key = Plans.keyFor(typeName, methodName, args == null ? 0 : args.length);
        final Plans.MethodPlan p = Registry.methodPlan(key);
        if (p == null) return null;

        if (p.bypass != null && p.bypass.bypass(args)) {
            Object value = (p.bypassReturn != null) ? p.bypassReturn.supply(args) : null;
            return new Object[]{ value }; // non-null => skip original
        }

        if (p.before != null) p.before.mutate(args);
        return null; // proceed
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(
            @Advice.Origin("#t") String typeName,
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] args,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object ret,
            @Advice.Thrown(readOnly = false) Throwable thrown,
            @Advice.Enter Object[] bypassCarrier
    ) throws Throwable {
        try {
            final String key = Plans.keyFor(typeName, methodName, args == null ? 0 : args.length);
            final Plans.MethodPlan p = Registry.methodPlan(key);
            if (p == null) return;

            if (bypassCarrier != null) {
                ret = bypassCarrier.length > 0 ? bypassCarrier[0] : null;
                thrown = null;
                return;
            }

            if (thrown != null && p.onThrow != null) {
                thrown = p.onThrow.mutate(args, ret, thrown);
            }

            if (p.after != null) {
                ret = p.after.mutate(args, ret, thrown);
            }
        } finally {
            CallScope.clear(); // always clear
        }
    }
}
