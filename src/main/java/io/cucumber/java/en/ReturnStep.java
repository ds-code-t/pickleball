package io.cucumber.java.en;


import io.cucumber.java.StepDefinitionAnnotation;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Retention(RUNTIME)
@Target(METHOD)
//@StepDefinitionAnnotation
@Documented
@API(status = Status.EXPERIMENTAL)
public @interface ReturnStep {
    String value();
}