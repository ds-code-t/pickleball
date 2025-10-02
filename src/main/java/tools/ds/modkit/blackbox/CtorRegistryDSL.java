// src/main/java/tools/ds/modkit/blackbox/CtorRegistryDSL.java
package tools.ds.modkit.blackbox;

import io.cucumber.core.backend.Backend;
import io.cucumber.core.backend.Glue;

import io.cucumber.core.runner.Options;
import io.cucumber.java.bs.A;
import tools.ds.modkit.coredefinitions.GeneralSteps;
import tools.ds.modkit.misc.DummySteps;
import tools.ds.modkit.trace.InstanceRegistry;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static tools.ds.modkit.blackbox.BlackBoxBootstrap.K_JAVABACKEND;
import static tools.ds.modkit.blackbox.BlackBoxBootstrap.K_RUNNER;
import static tools.ds.modkit.state.ScenarioState.getScenarioState;
import static tools.ds.modkit.util.Reflect.getProperty;
import static tools.ds.modkit.util.Reflect.invokeAnyMethod;

public final class CtorRegistryDSL {
    private CtorRegistryDSL() {
    }

    /* ================== Public API (thread-local) ================== */

    /**
     * Register ctor hooks for the given targets, storing instances in the per-thread registry.
     */
    public static void threadRegisterConstructed(List<?> targets, Object... extraKeys) {
        registerCommon(targets, false, extraKeys);
    }

    public static void threadRegisterConstructed(Class<?>... targets) {
        threadRegisterConstructed(Arrays.asList((Object[]) targets));
    }

    public static void threadRegisterConstructed(String... targets) {
        threadRegisterConstructed(Arrays.asList((Object[]) targets));
    }

    /* ================== Public API (global) ================== */

    /**
     * Register ctor hooks for the given targets, storing instances in the global registry.
     */
    public static void globalRegisterConstructed(List<?> targets, Object... extraKeys) {
        registerCommon(targets, true, extraKeys);
    }

    public static void globalRegisterConstructed(Class<?>... targets) {
        globalRegisterConstructed(Arrays.asList((Object[]) targets));
    }

    public static void globalRegisterConstructed(String... targets) {
        globalRegisterConstructed(Arrays.asList((Object[]) targets));
    }


    private static void registerCommon(List<?> targets, boolean global, Object... extraKeys) {
        if (targets == null || targets.isEmpty()) return;

        for (Object target : targets) {

            final String fqcn = toFqcn(target);
            if (fqcn == null || fqcn.isEmpty()) continue;


            Registry.register(
                    Plans.onCtor(fqcn, 0) // arg count is irrelevant for afterInstance (Weaver matches all ctors)
                            .afterInstance(self -> {
//                                if (!Registry.firstTimeForCtor(ctorKey, self)) return;
                                if (self == null) return;


                                if (self.getClass().getCanonicalName().equals(K_RUNNER)) {
                                    Options runnerOptions = (Options) getProperty(self, "runnerOptions");
                                    List<URI> currentGluePaths = (List<URI>) getProperty(runnerOptions, "glue");
                                    Glue glue = (Glue) getProperty(self, "glue");
                                    Collection<? extends Backend> backends = (Collection<? extends Backend>) getProperty(self, "backends");
                                    List<URI> newGluePaths = toGluePath(GeneralSteps.class);
                                    newGluePaths.removeAll(currentGluePaths);
                                    currentGluePaths.addAll(newGluePaths);
                                    List<URI> gluePathsToRemove = toGluePath(DummySteps.class);
                                    currentGluePaths.removeAll(gluePathsToRemove);
                                    for (Backend backend : backends) {
                                        backend.loadGlue(glue, newGluePaths);
                                    }
                                }

//                                if (self.getClass().getCanonicalName().equals(K_JAVABACKEND)) {
//                                    invokeAnyMethod(self,
//                                            "loadGlue",
//                                            getScenarioState().getRuntimeOptions().getGlue(),
//                                            toGluePath(GeneralSteps.class));
//                                }


                                // Build keys: Class, FQCN, plus any extras provided by the caller
                                List<Object> keys = new ArrayList<>(2 + extraKeys.length);
                                keys.add(self.getClass());
                                keys.add(self.getClass().getName());
                                for (Object k : extraKeys) if (k != null) keys.add(k);

                                Object[] arr = keys.toArray();
                                if (global) {
                                    InstanceRegistry.globalRegister(self, arr);
                                } else {
                                    InstanceRegistry.register(self, arr);
                                }
                            })
                            .build()
            );
        }
    }

    /**
     * Accepts Class, String (FQCN), or any object (uses its runtime class).
     */
    private static String toFqcn(Object t) {
        if (t == null) return null;
        if (t instanceof Class<?>) return ((Class<?>) t).getName();
        if (t instanceof CharSequence) return t.toString();
        return t.getClass().getName();
    }


    public static List<URI> toGluePath(Class<?>... classes) {
        List<URI> gluePaths = new ArrayList<>();

        for (Class<?> clazz : classes) {
            String pkg = clazz.getPackageName().replace('.', '/');
            try {
                gluePaths.add(new URI("classpath:/" + pkg));
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
        return gluePaths;
    }

}
