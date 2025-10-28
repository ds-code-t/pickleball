package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.options.CommandlineOptionsParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.order.PickleOrder;
import io.cucumber.core.runtime.BackendServiceLoader;
import io.cucumber.core.runtime.CucumberExecutionContext;
import io.cucumber.core.runtime.FeatureSupplier;
import io.cucumber.core.runtime.ObjectFactoryServiceLoader;
import io.cucumber.core.runtime.ObjectFactorySupplier;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.core.runtime.SingletonObjectFactorySupplier;
import io.cucumber.core.runtime.SingletonRunnerSupplier;
import io.cucumber.core.runtime.TimeServiceEventBus;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.lang.System.out;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.toList;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class PredefinedSteps {

    public static final io.cucumber.core.runner.PickleStepTestStep rootStep = CucumberObjects.createStepFromText(ROOT_STEP, "tools.dscode.coredefinitions");

    static {
        invokeAnyMethod(rootStep, "setNoLogging", true);
    }
//    public static final io.cucumber.core.runner.PickleStepTestStep rootStep = ((List<PickleStepTestStep>)getProperty(getTestCase("@___ROOTSTEP_"), "newSteps")).getFirst();
//
//    // 1) Initialize singleton Runner (CLI-style)
//    static Runner predefinedStepRunner = RunnerExtrasSupport.getOrInitRunner(
//            "--tags", "@___ROOTSTEP_", "--glue", "tools.dscode.coredefinitions",
//            "classpath:_internal_use_features"
//    );
//
//    // 2) Load features/pickles
//    var features = RunnerExtrasSupport.loadFeatures();
//    var pickles  = RunnerExtrasSupport.collectPickles(features);
//
//    // 3) Create a TestCase for a pickle (ITD on Runner)
//    TestCase tc = RunnerExtrasSupport.createTestCase(runner, pickles.getFirst());
//
//    // 4) Build a matched step from its text
//    PickleStepTestStep step = RunnerExtrasSupport.createStepFromText(
//            runner, pickles.getFirst(), "Given I am logged in"
//    );


}
