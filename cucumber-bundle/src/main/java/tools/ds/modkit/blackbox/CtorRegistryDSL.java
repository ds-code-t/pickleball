package tools.ds.modkit.blackbox;

import tools.ds.modkit.runtime.InstanceRegistry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static tools.ds.modkit.blackbox.Plans.onCtor;

/**
 * Keeps the exact public API you already use:
 *   - threadRegisterConstructed({@code List<?>} targets, Object... extraKeys)
 *   - globalRegisterConstructed({@code List<?>} targets, Object... extraKeys)
 *   - overloads for Class... and String...
 *
 * After a target's ctor finishes, the created instance is registered under:
 *   - its Class
 *   - its FQCN
 *   - any caller-provided extra keys
 */
public final class CtorRegistryDSL {
    private CtorRegistryDSL() {}

    // ---------------- Thread-local ----------------
    public static void threadRegisterConstructed(List<?> targets, Object... extraKeys) { registerCommon(targets, false, extraKeys); }
    public static void threadRegisterConstructed(Class<?>... targets)  { threadRegisterConstructed(Arrays.asList((Object[]) targets)); }
    public static void threadRegisterConstructed(String... targets)    { threadRegisterConstructed(Arrays.asList((Object[]) targets)); }

    // ---------------- Global ----------------
    public static void globalRegisterConstructed(List<?> targets, Object... extraKeys) { registerCommon(targets, true, extraKeys); }
    public static void globalRegisterConstructed(Class<?>... targets) { globalRegisterConstructed(Arrays.asList((Object[]) targets)); }
    public static void globalRegisterConstructed(String... targets)   { globalRegisterConstructed(Arrays.asList((Object[]) targets)); }

    // ---------------- Helpers ----------------
    private static void registerCommon(List<?> targets, boolean global, Object... extraKeys) {
        if (targets == null || targets.isEmpty()) return;

        for (Object t : targets) {
            final String fqcn = toFqcn(t);
            if (fqcn == null || fqcn.isBlank()) continue;

            Registry.register(
                    onCtor(fqcn, 0) // match type; advice handles all arg-counts
                            .afterInstance(self -> {
                                if (self == null) return;
                                List<Object> keys = new ArrayList<>(2 + (extraKeys == null ? 0 : extraKeys.length));
                                keys.add(self.getClass());
                                keys.add(self.getClass().getName());
                                if (extraKeys != null) for (Object k : extraKeys) if (k != null) keys.add(k);

                                Object[] arr = keys.toArray();
                                if (global) InstanceRegistry.globalRegister(self, arr);
                                else        InstanceRegistry.register(self, arr);
                            })
                            .build()
            );
        }
    }

    private static String toFqcn(Object t) {
        if (t == null) return null;
        if (t instanceof Class<?>) return ((Class<?>) t).getName();
        if (t instanceof CharSequence) return t.toString();
        return t.getClass().getName();
    }

    /** Optional helper if you still need glue paths elsewhere. */
    public static List<URI> toGluePath(Class<?>... classes) {
        List<URI> out = new ArrayList<>();
        for (Class<?> c : classes) {
            String pkg = c.getPackageName().replace('.', '/');
            try { out.add(new URI("classpath:/" + pkg)); }
            catch (URISyntaxException e) { throw new RuntimeException(e); }
        }
        return out;
    }
}
