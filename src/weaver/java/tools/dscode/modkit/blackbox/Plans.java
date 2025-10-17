package tools.dscode.modkit.blackbox;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;

import java.util.Objects;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * DSL for describing method/constructor weaving plans.
 *
 * Public surface intentionally matches your original:
 *  - Functional interfaces (ArgMutator, ReturnMutator, etc.)
 *  - Builders: Plans.on(...), Plans.onCtor(...)
 *  - Immutable plan objects: MethodPlan, CtorPlan
 *
 * NEW: MethodPlan and CtorPlan expose:
 *   DynamicType.Builder<Object> apply(Builder<Object> b, TypeDescription type, TypePool pool)
 * which wires ByteBuddy matchers and attaches MethodAdvice / CtorAdvice.
 */
public final class Plans {
    private Plans() {}

    // ---------- Functional contracts (unchanged surface) ----------
    @FunctionalInterface public interface ArgMutator { void mutate(Object[] args) throws Throwable; }
    @FunctionalInterface public interface ReturnMutator { Object mutate(Object[] args, Object ret, Throwable thrown) throws Throwable; }
    @FunctionalInterface public interface ThrowableMutator { Throwable mutate(Object[] args, Object ret, Throwable thrown) throws Throwable; }
    @FunctionalInterface public interface BypassDecider { boolean bypass(Object[] args) throws Throwable; }
    @FunctionalInterface public interface BypassSupplier { Object supply(Object[] args) throws Throwable; }
    @FunctionalInterface public interface InstanceMutator { void mutate(Object self) throws Throwable; }
    @FunctionalInterface public interface ReturnMutatorSelf { Object mutate(Object self, Object[] args, Object ret, Throwable thrown) throws Throwable; }

    // ---------- Keys ----------
    public static String keyFor(String fqcn, String method, int argc) {
        return fqcn + "#" + method + "/" + argc;
    }
    public static String ctorKeyFor(String fqcn, int argc) {
        return fqcn + "#<init>/" + argc;
    }

    // ---------- Method plan ----------
    public static final class MethodPlan {
        public final String fqcn;
        public final String method;
        public final int argCount;
        /** Optional filter by fully-qualified return type. Null/empty means "any". */
        public final String returnTypeFqcn;

        public final ArgMutator before;
        public final ReturnMutator after;
        public final ThrowableMutator onThrow;
        public final BypassDecider bypass;
        public final BypassSupplier bypassReturn;
        public final ReturnMutatorSelf afterSelf;

        MethodPlan(
                String fqcn,
                String method,
                int argCount,
                String returnTypeFqcn,
                ArgMutator before,
                ReturnMutator after,
                ThrowableMutator onThrow,
                BypassDecider bypass,
                BypassSupplier bypassReturn,
                ReturnMutatorSelf afterSelf
        ) {
            this.fqcn = Objects.requireNonNull(fqcn, "fqcn");
            this.method = Objects.requireNonNull(method, "method");
            this.argCount = argCount;
            this.returnTypeFqcn = returnTypeFqcn;
            this.before = before;
            this.after = after;
            this.onThrow = onThrow;
            this.bypass = bypass;
            this.bypassReturn = bypassReturn;
            this.afterSelf = afterSelf;
        }

        public String key() { return keyFor(fqcn, method, argCount); }

        /** Non-breaking helper: true if return type filter is empty or matches the given FQCN. */
        public boolean matchesReturnType(String actualReturnTypeFqcn) {
            return returnTypeFqcn == null
                    || returnTypeFqcn.isEmpty()
                    || Objects.equals(returnTypeFqcn, actualReturnTypeFqcn);
        }

        /**
         * Apply this plan’s ByteBuddy wiring to the given builder IF it matches the provided type.
         * We match on:
         *  - exact type name (fqcn)
         *  - method name (method)
         *  - parameter count (argCount)
         *  - optional return type fqcn
         *
         * The actual advice behavior is in MethodAdvice; it looks up this plan at runtime via Registry.
         */
        public DynamicType.Builder<Object> apply(DynamicType.Builder<Object> builder,
                                                 TypeDescription type,
                                                 TypePool pool) {
            if (!type.getName().equals(fqcn)) {
                return builder; // not this type
            }

            var matcher = named(method).and(takesArguments(argCount));

            // enforce return type if provided
            if (returnTypeFqcn != null && !returnTypeFqcn.isEmpty()) {
                matcher = matcher.and(returns(named(returnTypeFqcn)));
            }

            // (Optional) small debug: how many declared methods match by name?
            int declaredNameMatches = 0;
            try {
                declaredNameMatches = (int) type.getDeclaredMethods().filter(named(method)).size();
            } catch (Throwable t) {
                System.out.println("[Plans][MethodPlan][warn] Could not count declared matches for "
                        + fqcn + "#" + method + ": " + t);
            }
            System.out.println("[Plans][MethodPlan] apply to " + fqcn + "#" + method
                    + "(" + argCount + ")"
                    + (returnTypeFqcn != null && !returnTypeFqcn.isEmpty() ? " returns " + returnTypeFqcn : "")
                    + ", declared name matches=" + declaredNameMatches);

            // Wire the advice – it will pull the concrete plan by key at runtime.
            return builder.method(matcher).intercept(Advice.to(MethodAdvice.class));
        }

        // ---------- Builder (unchanged DSL) ----------
        public static final class Builder {
            private final String fqcn;
            private final String method;
            private final int argc;

            private String returnTypeFqcn;
            private ArgMutator before;
            private ReturnMutator after;
            private ThrowableMutator onThrow;
            private BypassDecider bypass;
            private BypassSupplier bypassReturn;
            private ReturnMutatorSelf afterSelf;

            public Builder(String fqcn, String method, int argc) {
                this.fqcn = Objects.requireNonNull(fqcn, "fqcn");
                this.method = Objects.requireNonNull(method, "method");
                this.argc = argc;
            }

            public Builder returns(String returnTypeFqcn)                 { this.returnTypeFqcn = returnTypeFqcn; return this; }
            public Builder before(ArgMutator m)                           { this.before = m; return this; }
            public Builder after(ReturnMutator m)                         { this.after = m; return this; }
            public Builder afterSelf(ReturnMutatorSelf m)                 { this.afterSelf = m; return this; }
            public Builder onThrow(ThrowableMutator m)                    { this.onThrow = m; return this; }
            public Builder around(BypassDecider d, BypassSupplier s)      { this.bypass = d; this.bypassReturn = s; return this; }

            public MethodPlan build() {
                return new MethodPlan(
                        fqcn, method, argc, returnTypeFqcn,
                        before, after, onThrow, bypass, bypassReturn, afterSelf
                );
            }
        }
    }

    // ---------- Ctor plan ----------
    public static final class CtorPlan {
        public final String fqcn;
        public final int argCount;
        public final ArgMutator before;
        public final InstanceMutator afterInstance;

        CtorPlan(String fqcn, int argCount, ArgMutator before, InstanceMutator afterInstance) {
            this.fqcn = Objects.requireNonNull(fqcn, "fqcn");
            this.argCount = argCount;
            this.before = before;
            this.afterInstance = afterInstance;
        }

        public String key() { return ctorKeyFor(fqcn, argCount); }

        /**
         * Apply constructor advice to this type iff it matches our fqcn.
         * Note: our CtorAdvice routes both BEFORE (arg mutation) and AFTER-INSTANCE via Registry.
         */
        public DynamicType.Builder<Object> apply(DynamicType.Builder<Object> builder,
                                                 TypeDescription type,
                                                 TypePool pool) {
            if (!type.getName().equals(fqcn)) {
                return builder;
            }
            System.out.println("[Plans][CtorPlan] apply to " + fqcn + " <init>(" + argCount + ")");
            // We advise all ctors; CtorAdvice itself can inspect arg length if you want.
            return builder.visit(Advice.to(CtorAdvice.class).on(isConstructor()));
        }

        // ---------- Builder (unchanged DSL) ----------
        public static final class Builder {
            private final String fqcn;
            private final int argc;
            private ArgMutator before;
            private InstanceMutator afterInstance;

            public Builder(String fqcn, int argc) {
                this.fqcn = Objects.requireNonNull(fqcn, "fqcn");
                this.argc = argc;
            }

            public Builder before(ArgMutator m)                { this.before = m; return this; }
            public Builder afterInstance(InstanceMutator m)    { this.afterInstance = m; return this; }

            public CtorPlan build() {
                return new CtorPlan(fqcn, argc, before, afterInstance);
            }
        }
    }

    // ---------- DSL entrypoints (unchanged) ----------
    public static MethodPlan.Builder on(String fqcn, String method, int argc) { return new MethodPlan.Builder(fqcn, method, argc); }
    public static CtorPlan.Builder   onCtor(String fqcn, int argc)            { return new CtorPlan.Builder(fqcn, argc); }
}
