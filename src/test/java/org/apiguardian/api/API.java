package org.apiguardian.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
        ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.MODULE,
        ElementType.PACKAGE,
        ElementType.TYPE
})
public @interface API {
    Status status();

    String since() default "";

    String[] consumers() default {};

    enum Status {
        STABLE,
        MAINTAINED,
        EXPERIMENTAL,
        DEPRECATED,
        INTERNAL
    }
}
