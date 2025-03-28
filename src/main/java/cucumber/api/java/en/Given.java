package cucumber.api.java.en;

import io.cucumber.java.StepDefinitionAnnotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.annotation.Documented;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@StepDefinitionAnnotation
@Documented
@Deprecated
public @interface Given {
    /**
     * @return a regular expression
     */
    String value();

    /**
     * @return max amount of milliseconds this is allowed to run for. 0 (default) means no restriction.
     */
    long timeout() default 0;
}

