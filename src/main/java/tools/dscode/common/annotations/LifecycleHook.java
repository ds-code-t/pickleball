package tools.dscode.common.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LifecycleHook {
    Phase value();                    // the phase
    int order() default Integer.MIN_VALUE; // optional; MIN_VALUE = "no explicit order"
}