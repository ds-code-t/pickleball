package io.cucumber.java;

import io.cucumber.core.backend.Lookup;
import io.cucumber.core.backend.ParameterInfo;
import io.cucumber.core.backend.StepDefinition;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;

import java.lang.reflect.Method;
import java.util.List;

import static java.util.Objects.requireNonNull;

public final class JavaStepDefinition extends AbstractGlueDefinition implements StepDefinition {

    public final String expression;
    public final Method method;
    public final List<ParameterInfo> parameterInfos;

    JavaStepDefinition(
            Method method,
            String expression,
            Lookup lookup
    ) {
        super(method, lookup);
        this.method = method;
        this.parameterInfos = JavaParameterInfo.fromMethod(method);
        this.expression = requireNonNull(expression, "cucumber-expression may not be null");

//        if(method.getReturnType().equals(MetaStepData.class))
//        {
//
//        }
    }

    @Override
    public void execute(Object[] args) {
        invokeMethod(args);
    }

    @Override
    public List<ParameterInfo> parameterInfos() {
        return parameterInfos;
    }

    @Override
    public String getPattern() {
        return expression;
    }

}
