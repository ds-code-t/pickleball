package io.cucumber.core.runner;

import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.order.PickleOrder;
import io.cucumber.core.runtime.FeatureSupplier;
import io.cucumber.messages.types.PickleStepArgument;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static io.cucumber.core.runner.GlobalState.getGherkinMessagesPickle;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

/**
 * Rich functional API attached to a particular Runner / RuntimeOptions / Runtime trio.
 * All helpers reuse the cached environment stored in RunnerRuntimeRegistry.
 */
public final class RunnerRuntimeContext {

    public final Runner runner;
    public final RuntimeOptions runtimeOptions;
    public final io.cucumber.core.runtime.Runtime runtime;
    public final CachingGlue glue;

    RunnerRuntimeContext(
            Runner runner,
            RuntimeOptions runtimeOptions,
            io.cucumber.core.runtime.Runtime runtime
    ) {
        this.runner = runner;
        this.runtimeOptions = runtimeOptions;
        this.runtime = runtime;
        this.glue = (CachingGlue) getProperty(runner, "glue");
    }

    // ───────────────────────── Features ─────────────────────────

    public List<Feature> loadFeatures() {
        FeatureSupplier fs = (FeatureSupplier) getProperty(runtime, "featureSupplier");
        return fs.get();
    }

    // ───────────────────────── Pickles ─────────────────────────

    public List<Pickle> collectPickles() {
        List<Feature> features = loadFeatures();
        Predicate<Pickle> filter = new Filters(runtimeOptions);
        int limit = runtimeOptions.getLimitCount();
        PickleOrder order = runtimeOptions.getPickleOrder();

        return features.stream()
                .flatMap(f -> f.getPickles().stream())
                .filter(filter)
                .collect(Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> order.orderPickles(list).stream()
                ))
                .limit(limit > 0 ? limit : Integer.MAX_VALUE)
                .toList();
    }

    // ───────────────────────── Woven Runner helpers ─────────────────────────
    // REMINDER: Runner implements RunnerExtras via AspectJ ITD.
    // We must cast through Object for javac, final class.

    private RunnerExtras extras() {
        return (RunnerExtras) (Object) runner;
    }

    public PickleStepTestStep createStepFromText(Pickle pickle, String stepText) {
        return extras().createStepForText(pickle, stepText);
    }

    public TestCase createTestCase(Pickle pickle) {
        System.out.println("@@createTestCase1-pickle.getName(): " + pickle.getName());
        return extras().createTestCase(pickle);
    }

    /** Shorthand: get first matching pickle in this context (after filters/order). */
    public Pickle firstPickle() {
        return collectPickles().stream().findFirst().orElse(null);
    }

    // ───────────────────────── NEW: definition match helpers ─────────────────────────




    public static PickleStepDefinitionMatch findPickleStepDefinitionMatch(io.cucumber.core.gherkin.Step step, String... gluePaths) {
        RunnerRuntimeContext context = io.cucumber.core.runner.RunnerRuntimeRegistry.getOrInit(gluePaths);
        System.out.println("@@context.runtimeOptions.getGlue(): " + context.runtimeOptions.getGlue());
        System.out.println("@@getGherkinMessagesPickle(): " + getGherkinMessagesPickle().getName());
        System.out.println("@@step: " + step.getText());
        return (PickleStepDefinitionMatch) invokeAnyMethod(context.runner, "matchStepToStepDefinition", getGherkinMessagesPickle(), step);
    }


//
//    public static PickleStepDefinitionMatch matchForText(String stepText, String... gluePaths) {
//        return matchForText(stepText, null, gluePaths);
//    }
//    public static PickleStepDefinitionMatch matchForText(String stepText, PickleStepArgument pickleStepArgument, String... gluePaths) {
//       Pickle pickle = createOneStepGherkinMessagesPickle(stepText, pickleStepArgument);
//       RunnerRuntimeContext context = io.cucumber.core.runner.RunnerRuntimeRegistry.getOrInit(gluePaths);
//        PickleStepTestStep step = context.runner.createStepForText(pickle, stepText);
//        return step.getDefinitionMatch();
//    }

//    public PickleStepDefinitionMatch matchForText(Pickle pickle, String stepText) {
//        if (pickle == null) {
//            throw new IllegalArgumentException("pickle must not be null");
//        }
//        PickleStepTestStep step = extras().createStepForText(pickle, stepText);
//        return step.getDefinitionMatch();
//    }


//    public PickleStepDefinitionMatch matchForText(String stepText) {
//        return matchForText(getGherkinMessagesPickle(), stepText);
//    }
//
//
//    public static PickleStepDefinitionMatch matchTextWithGlue(String stepText, String... gluePaths) {
//        java.util.Objects.requireNonNull(stepText, "stepText");
//
//        java.util.List<String> args = new java.util.ArrayList<>();
//        if (gluePaths != null) {
//            for (String g : gluePaths) {
//                if (g != null && !g.isBlank()) {
//                    args.add("--glue");
//                    args.add(g.trim()); // repeat --glue per path (Cucumber CLI style)
//                }
//            }
//        }
//
//        RunnerRuntimeContext ctx = io.cucumber.core.runner.RunnerRuntimeRegistry
//                .getOrInit(args.toArray(new String[0]));
//
//        // Reuse the instance helper that uses the first available pickle in this context
//        return ctx.matchForText(stepText);
//    }


}
