package tools.dscode.modkit.blackbox;

import java.util.Objects;

public final class Plans {
    private Plans() {}

    // Functional contracts (unchanged surface)
    @FunctionalInterface public interface ArgMutator { void mutate(Object[] args) throws Throwable; }
    @FunctionalInterface public interface ReturnMutator { Object mutate(Object[] args, Object ret, Throwable thrown) throws Throwable; }
    @FunctionalInterface public interface ThrowableMutator { Throwable mutate(Object[] args, Object ret, Throwable thrown) throws Throwable; }
    @FunctionalInterface public interface BypassDecider { boolean bypass(Object[] args) throws Throwable; }
    @FunctionalInterface public interface BypassSupplier { Object supply(Object[] args) throws Throwable; }
    @FunctionalInterface public interface InstanceMutator { void mutate(Object self) throws Throwable; }
    @FunctionalInterface public interface ReturnMutatorSelf { Object mutate(Object self, Object[] args, Object ret, Throwable thrown) throws Throwable; }

    // Keys
    public static String keyFor(String fqcn, String method, int argc) { return fqcn + "#" + method + "/" + argc; }
    public static String ctorKeyFor(String fqcn, int argc)           { return fqcn + "#<init>/" + argc; }

    // Method plan (unchanged builder API)
    public static final class MethodPlan {
        public final String fqcn, method;
        public final int argCount;
        public final String returnTypeFqcn;   // optional filter
        public final ArgMutator before;
        public final ReturnMutator after;
        public final ThrowableMutator onThrow;
        public final BypassDecider bypass;
        public final BypassSupplier bypassReturn;
        public final ReturnMutatorSelf afterSelf;

        MethodPlan(String fqcn, String method, int argCount, String returnTypeFqcn,
                   ArgMutator before, ReturnMutator after, ThrowableMutator onThrow,
                   BypassDecider bypass, BypassSupplier bypassReturn, ReturnMutatorSelf afterSelf) {
            this.fqcn = fqcn; this.method = method; this.argCount = argCount; this.returnTypeFqcn = returnTypeFqcn;
            this.before = before; this.after = after; this.onThrow = onThrow;
            this.bypass = bypass; this.bypassReturn = bypassReturn; this.afterSelf = afterSelf;
        }

        public String key() { return keyFor(fqcn, method, argCount); }

        public static final class Builder {
            private final String fqcn, method; private final int argc;
            private String returnTypeFqcn;
            private ArgMutator before; private ReturnMutator after; private ThrowableMutator onThrow;
            private BypassDecider bypass; private BypassSupplier bypassReturn;
            private ReturnMutatorSelf afterSelf;

            public Builder(String fqcn, String method, int argc) {
                this.fqcn = Objects.requireNonNull(fqcn);
                this.method = Objects.requireNonNull(method);
                this.argc = argc;
            }
            public Builder returns(String returnTypeFqcn)                 { this.returnTypeFqcn = returnTypeFqcn; return this; }
            public Builder before(ArgMutator m)                           { this.before = m; return this; }
            public Builder after(ReturnMutator m)                         { this.after = m; return this; }
            public Builder afterSelf(ReturnMutatorSelf m)                 { this.afterSelf = m; return this; }
            public Builder onThrow(ThrowableMutator m)                    { this.onThrow = m; return this; }
            public Builder around(BypassDecider d, BypassSupplier s)      { this.bypass = d; this.bypassReturn = s; return this; }

            public MethodPlan build() {
                return new MethodPlan(fqcn, method, argc, returnTypeFqcn, before, after, onThrow, bypass, bypassReturn, afterSelf);
            }
        }
    }

    // Ctor plan (unchanged builder API)
    public static final class CtorPlan {
        public final String fqcn; public final int argCount;
        public final ArgMutator before; public final InstanceMutator afterInstance;
        CtorPlan(String fqcn, int argCount, ArgMutator before, InstanceMutator afterInstance) {
            this.fqcn = fqcn; this.argCount = argCount; this.before = before; this.afterInstance = afterInstance;
        }
        public String key() { return ctorKeyFor(fqcn, argCount); }

        public static final class Builder {
            private final String fqcn; private final int argc;
            private ArgMutator before; private InstanceMutator afterInstance;
            public Builder(String fqcn, int argc) { this.fqcn = Objects.requireNonNull(fqcn); this.argc = argc; }
            public Builder before(ArgMutator m) { this.before = m; return this; }
            public Builder afterInstance(InstanceMutator m) { this.afterInstance = m; return this; }
            public CtorPlan build() { return new CtorPlan(fqcn, argc, before, afterInstance); }
        }
    }

    // DSL entrypoints (kept identical)
    public static MethodPlan.Builder on(String fqcn, String method, int argc) { return new MethodPlan.Builder(fqcn, method, argc); }
    public static CtorPlan.Builder   onCtor(String fqcn, int argc)            { return new CtorPlan.Builder(fqcn, argc); }
}
