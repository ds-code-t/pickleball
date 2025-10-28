package tools.dscode.common.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 2️⃣ Define the annotation that takes a list of those enums
@Retention(RetentionPolicy.RUNTIME) // accessible at runtime via reflection
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface DefinitionFlags {
    DefinitionFlag[] value(); // list (array) of enum constants
}
