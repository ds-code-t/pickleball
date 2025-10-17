package tools.dscode.modkit.blackbox;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class CtorAdvice {
    private CtorAdvice() {}

    @Advice.OnMethodEnter
    static void enter(@Advice.Origin("#t") String typeName,
                      @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args)
            throws Throwable {
        final String key = Plans.ctorKeyFor(typeName, args == null ? 0 : args.length);
        final Plans.CtorPlan p = Registry.ctorPlan(key);
        if (p != null && p.before != null) p.before.mutate(args);
    }

    @Advice.OnMethodExit
    static void exit(@Advice.Origin("#t") String typeName,
                     @Advice.This(optional = true) Object self) throws Throwable {
        for (Plans.CtorPlan p : Registry.allCtorPlans()) {
            if (p.fqcn.equals(typeName) && p.afterInstance != null) p.afterInstance.mutate(self);
        }
    }
}
