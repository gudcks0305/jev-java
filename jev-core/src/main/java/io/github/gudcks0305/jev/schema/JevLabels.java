package io.github.gudcks0305.jev.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Independent yes/no questions, one per enum option, mapped to List or Set of that enum. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface JevLabels {
    String value();
    double threshold();
}
