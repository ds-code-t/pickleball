//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Step;
//import io.cucumber.gherkin.GherkinDialect;
//import io.cucumber.messages.types.PickleStep;
//import io.cucumber.messages.types.PickleStepArgument;
//import io.cucumber.messages.types.PickleStepType;
//import io.cucumber.plugin.event.Location;
//
//import java.lang.reflect.Constructor;
//import java.lang.reflect.Field;
//import java.net.URI;
//import java.util.ArrayList;
//import java.util.List;
//import java.util.Objects;
//import java.util.UUID;
//
///**
// * Package-scoped utility for cloning core Cucumber step objects.
// *
// * Put this class in the io.cucumber.core.runner package so we can call
// * package-private constructors/methods on PickleStepTestStep and access its hooks.
// */
//public final class CucumberCloneUtils {
//
//    private static final String GMS_FQN = "io.cucumber.core.gherkin.messages.GherkinMessagesStep";
//
//    private CucumberCloneUtils() {}
//
//    /* =========================
//     * PickleStep (messages)
//     * ========================= */
//    public static PickleStep clonePickleStep(PickleStep original) {
//        Objects.requireNonNull(original, "original PickleStep");
//        PickleStepArgument arg = original.getArgument().orElse(null);
//        List<String> astIdsCopy = new ArrayList<>(original.getAstNodeIds());
//        String idCopy = original.getId();
//        PickleStepType typeCopy = original.getType().orElse(null);
//        String textCopy = original.getText();
//        return new PickleStep(arg, astIdsCopy, idCopy, typeCopy, textCopy);
//    }
//
//    /* =========================
//     * Step (possibly GherkinMessagesStep)
//     * ========================= */
//    /**
//     * If the given Step is the private GherkinMessagesStep, clone it; otherwise return it as-is.
//     * Requires a GherkinDialect because the constructor needs it.
//     */
//    public static Step cloneStepIfGherkinMessages(Step original, GherkinDialect dialect) {
//        Objects.requireNonNull(original, "original Step");
//        if (!original.getClass().getName().equals(GMS_FQN)) {
//            return original; // not a GherkinMessagesStep; nothing special to do
//        }
//
//        // Extract private pickleStep via reflection
//        PickleStep originalPickle = (PickleStep) readDeclaredField(original, "pickleStep");
//        PickleStep clonedPickle = clonePickleStep(originalPickle);
//
//        // Use public Step getters for the rest
//        String keyword = original.getKeyword();
//        String prevGwt = original.getPreviousGivenWhenThenKeyword();
//        Location location = original.getLocation();
//
//        try {
//            Class<?> gmsClass = Class.forName(GMS_FQN);
//            Constructor<?> ctor = gmsClass.getDeclaredConstructor(
//                    PickleStep.class, GherkinDialect.class, String.class, Location.class, String.class
//            );
//            ctor.setAccessible(true);
//            Object newInstance = ctor.newInstance(clonedPickle, dialect, prevGwt, location, keyword);
//            return (Step) newInstance;
//        } catch (ReflectiveOperationException e) {
//            throw new IllegalStateException("Failed to clone GherkinMessagesStep", e);
//        }
//    }
//
//    /* =========================
//     * PickleStepTestStep (runner)
//     * ========================= */
//    /**
//     * Deep-clone a PickleStepTestStep. If its nested Step is a GherkinMessagesStep, that is deep-cloned too.
//     */
//    public static PickleStepTestStep clonePickleStepTestStep(PickleStepTestStep original, GherkinDialect dialect) {
//        Objects.requireNonNull(original, "original PickleStepTestStep");
//
//        // Superclass TestStep holds the id; reflect it
//        UUID id = (UUID) readFieldFromHierarchy(original, "id");
//
//        URI uri = original.getUri();
//        Step step = original.getStep();
//        Step stepClone = cloneStepIfGherkinMessages(step, dialect);
//
//        // Package-private accessors; same package so we can call them directly
//        List<HookTestStep> before = new ArrayList<>(original.getBeforeStepHookSteps());
//        List<HookTestStep> after  = new ArrayList<>(original.getAfterStepHookSteps());
//        PickleStepDefinitionMatch def = original.getDefinitionMatch();
//
//        // Package-private constructor; same package so we can call it directly
//        return new PickleStepTestStep(id, uri, stepClone, before, after, def);
//    }
//
//    /* =========================
//     * Reflection helpers
//     * ========================= */
//    private static Object readDeclaredField(Object target, String fieldName) {
//        try {
//            Field f = target.getClass().getDeclaredField(fieldName);
//            f.setAccessible(true);
//            return f.get(target);
//        } catch (ReflectiveOperationException e) {
//            throw new IllegalStateException("Cannot read field '" + fieldName + "' from " + target.getClass(), e);
//        }
//    }
//
//    private static Object readFieldFromHierarchy(Object target, String fieldName) {
//        Class<?> c = target.getClass();
//        while (c != null && c != Object.class) {
//            try {
//                Field f = c.getDeclaredField(fieldName);
//                f.setAccessible(true);
//                return f.get(target);
//            } catch (NoSuchFieldException ignored) {
//                c = c.getSuperclass();
//            } catch (ReflectiveOperationException e) {
//                throw new IllegalStateException("Cannot read field '" + fieldName + "' from " + target.getClass(), e);
//            }
//        }
//        throw new IllegalStateException("Field '" + fieldName + "' not found in hierarchy of " + target.getClass());
//    }
//}
