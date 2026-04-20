package io.cucumber.core.runner;

import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.gherkin.StepType;
import io.cucumber.core.stepexpression.Argument;
import io.cucumber.plugin.event.Location;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Collections;
import java.util.Optional;

public final class PickleStepDefinitionMatches {

    private PickleStepDefinitionMatches() {
    }

    public static PickleStepDefinitionMatch fromStaticZeroArgMethod(Method method) {
        try {
            method.setAccessible(true);

            String pattern = method.getName();
            String location = method.getDeclaringClass().getName() + "." + method.getName() + "()";

            StepDefinition stepDefinition = (StepDefinition) Proxy.newProxyInstance(
                    StepDefinition.class.getClassLoader(),
                    new Class<?>[]{StepDefinition.class},
                    new ZeroArgStaticStepDefinitionHandler(method, pattern, location)
            );

            return new PickleStepDefinitionMatch(
                    Collections.<Argument>emptyList(),
                    stepDefinition,
                    URI.create("memory:/synthetic.feature"),
                    new DummyStep(method.getName())
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to construct PickleStepDefinitionMatch for method: " + method, e);
        }
    }

    private static final class ZeroArgStaticStepDefinitionHandler implements InvocationHandler {
        private final Method target;
        private final String pattern;
        private final String location;

        private ZeroArgStaticStepDefinitionHandler(Method target, String pattern, String location) {
            this.target = target;
            this.pattern = pattern;
            this.location = location;
        }

        @Override
        public Object invoke(Object proxy, Method called, Object[] args) throws Throwable {
            switch (called.getName()) {
                case "execute":
                    target.invoke(null);
                    return null;
                case "parameterInfos":
                    return Collections.<ParameterInfo>emptyList();
                case "getPattern":
                    return pattern;
                case "getLocation":
                    return location;
                case "isDefinedAt":
                    return false;
                case "getSourceReference":
                    return Optional.empty();
                case "toString":
                    return "SyntheticStepDefinition[" + location + "]";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    throw new UnsupportedOperationException("Unsupported StepDefinition method: " + called);
            }
        }
    }

    private static final class DummyStep implements Step {
        private final String text;

        private DummyStep(String text) {
            this.text = text;
        }

        @Override
        public String getKeyword() {
            return "";
        }

        @Override
        public String getText() {
            return text;
        }

        @Override
        public int getLine() {
            return 1;
        }

        @Override
        public Location getLocation() {
            return null;
        }

        @Override
        public StepType getType() {
            return null;
        }

        @Override
        public String getPreviousGivenWhenThenKeyword() {
            return "";
        }

        @Override
        public String getId() {
            return "synthetic-step";
        }

        @Override
        public io.cucumber.core.gherkin.Argument getArgument() {
            return null;
        }

    }


}