package tools.dscode.common.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation to indicate a glue/steps class should be ignored by our
 * ByteBuddy interception when Cucumber scans step definitions
 * (MethodScanner#scan).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IgnoreDefinition {
}
