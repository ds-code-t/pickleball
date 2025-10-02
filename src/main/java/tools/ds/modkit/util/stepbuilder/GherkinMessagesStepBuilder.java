//package tools.ds.modkit.util.stepbuilder;
//
//import io.cucumber.core.gherkin.Argument;
//import io.cucumber.core.gherkin.Step;
//import io.cucumber.core.gherkin.StepType;
//import io.cucumber.messages.types.PickleDocString;
//import io.cucumber.messages.types.PickleStep;
//import io.cucumber.messages.types.PickleStepArgument;
//import io.cucumber.messages.types.PickleTable;
//import io.cucumber.plugin.event.Location;
//
//import tools.ds.modkit.util.Reflect;
//
//import java.util.Optional;
//
///**
// * Builds a Step that mirrors an existing one but substitutes a new PickleStep.
// * Avoids constructing GherkinMessagesStep directly; instead implements Step and
// * constructs the proper Argument (docstring/table) reflectively.
// */
//public final class GherkinMessagesStepBuilder {
//
//    private GherkinMessagesStepBuilder() {}
//
//    /**
//     * Clone a Step but substitute a different PickleStep.
//     *
//     * Preserves from {@code original}: keyword, location, line, type, previous GWT keyword.
//     * Replaces from {@code newPickle}: id, text, and argument (doc string / data table).
//     */
//    public static Step cloneWithPickleStep(Step original, PickleStep newPickle) {
//        if (original == null) throw new IllegalArgumentException("original is null");
//        if (newPickle == null) throw new IllegalArgumentException("newPickle is null");
//
//        final String keyword = original.getKeyword();
//        final Location location = original.getLocation();
//        final StepType type = original.getType();
//        final String prevGwt = original.getPreviousGivenWhenThenKeyword();
//
//        // Build the proper Argument (doc string or data table) the same way GherkinMessagesStep does.
//        final Argument argument = buildArgument(newPickle, location);
//        System.out.println("@@newPickle getClass: "+ newPickle.getClass());
//        System.out.println("@@newPickle: "+ newPickle);
//
//        return new Impl(keyword, location, type, prevGwt, newPickle, argument);
//    }
//
//    /** Mirrors GherkinMessagesStep.extractArgument(..) but uses reflection to reach internal classes. */
//    private static Argument buildArgument(PickleStep pickleStep, Location location) {
//        Optional<PickleStepArgument> argOpt = pickleStep.getArgument();
//        if (argOpt.isEmpty()) return null;
//
//        PickleStepArgument psa = argOpt.get();
//
//        // If doc string present → new GherkinMessagesDocStringArgument(docString, location.getLine() + 1)
//        if (psa.getDocString().isPresent()) {
//            PickleDocString ds = psa.getDocString().get();
//            try {
//                Class<?> docArgClz = Class.forName("io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument");
//                // ctor(PickleDocString, int)
//                Object arg = docArgClz.getDeclaredConstructor(PickleDocString.class, int.class)
//                        .newInstance(ds, location.getLine() + 1);
//                return (Argument) arg;
//            } catch (ReflectiveOperationException e) {
//                // Best-effort fallback: try method-based factories if present
//                Object maybe = Reflect.invokeAnyMethod(
//                        load("io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument"),
//                        "of",
//                        ds, location.getLine() + 1
//                );
//                if (maybe instanceof Argument a) return a;
//                throw new IllegalStateException("Could not construct GherkinMessagesDocStringArgument", e);
//            }
//        }
//
//        // If data table present → new GherkinMessagesDataTableArgument(table, location.getLine() + 1)
//        if (psa.getDataTable().isPresent()) {
//            PickleTable table = psa.getDataTable().get();
//            try {
//                Class<?> tblArgClz = Class.forName("io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument");
//                Object arg = tblArgClz.getDeclaredConstructor(PickleTable.class, int.class)
//                        .newInstance(table, location.getLine() + 1);
//                return (Argument) arg;
//            } catch (ReflectiveOperationException e) {
//                Object maybe = Reflect.invokeAnyMethod(
//                        load("io.cucumber.core.gherkin.messages.GherkinMessagesDataTableArgument"),
//                        "of",
//                        table, location.getLine() + 1
//                );
//                if (maybe instanceof Argument a) return a;
//                throw new IllegalStateException("Could not construct GherkinMessagesDataTableArgument", e);
//            }
//        }
//
//        return null;
//    }
//
//    private static Class<?> load(String fqcn) {
//        try { return Class.forName(fqcn); }
//        catch (ClassNotFoundException e) { return null; }
//    }
//
//    /** Concrete Step impl that delegates static parts to original’s values and dynamic parts to new PickleStep. */
//    private static final class Impl implements Step {
//        private final String keyword;
//        private final Location location;
//        private final StepType type;
//        private final String previousGwtKeyword;
//        private final PickleStep pickle;
//        private final Argument argument;
//
//        Impl(String keyword,
//             Location location,
//             StepType type,
//             String previousGwtKeyword,
//             PickleStep pickle,
//             Argument argument) {
//            this.keyword = keyword;
//            this.location = location;
//            this.type = type;
//            this.previousGwtKeyword = previousGwtKeyword;
//            this.pickle = pickle;
//            this.argument = argument;
//        }
//
//        // --- io.cucumber.plugin.event.Step ---
//        @Override public String getKeyword() { return keyword; }
//        @Override public int getLine() { return location.getLine(); }
//        @Override public Location getLocation() { return location; }
//        @Override public String getText() { return pickle.getText(); }
//
//        // --- io.cucumber.core.gherkin.Step ---
//        @Override public StepType getType() { return type; }
//        @Override public String getPreviousGivenWhenThenKeyword() { return previousGwtKeyword; }
//        @Override public String getId() { return pickle.getId(); }
//        @Override public Argument getArgument() { return argument; }
//    }
//
//}
