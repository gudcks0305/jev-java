package io.github.gudcks0305.jev.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A rubric score mapped to double/Double as the raw weighted zero-based level index. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface JevScore {
    String value();
    String[] levels();
}
