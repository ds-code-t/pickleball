package io.cucumber.java.en;

import io.cucumber.java.StepDefinitionAnnotation;
import io.cucumber.java.StepDefinitionAnnotations;
import org.apiguardian.api.API;

import java.lang.annotation.*;


@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@StepDefinitionAnnotation
@Documented
@Repeatable(If.Ifs.class)
@API(status = API.Status.STABLE)
public @interface If {
    /**
     * A cucumber or regular expression.
     *
     * @return a cucumber or regular expression
     */
    String value();

    /**
     * Allows the use of multiple 'And's on a single method.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @StepDefinitionAnnotations
    @Documented
    @interface Ifs {
        If[] value();
    }
}
