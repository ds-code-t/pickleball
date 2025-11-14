package tools.dscode.common.annotations;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LifecycleHook {
    Phase value();                    // the phase
    int order() default Integer.MIN_VALUE; // optional; MIN_VALUE = "no explicit order"
}