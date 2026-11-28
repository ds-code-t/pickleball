package tools.dscode.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Cross-process lock for a shared test resource. Gradle workers are separate JVMs,
 * so JUnit {@code @ResourceLock} does not cover them. The name is a file under
 * {@code pkb.test.lockDir} (default {@code build/pkb-test-locks}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@ExtendWith(SerialResourceLock.class)
public @interface SerialResource {
    String value();
}
