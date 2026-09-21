package io.github.gudcks0305.jev.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A single choice mapped to an enum or Optional&lt;Enum&gt;. Enum names are wire labels. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface JevChoice {
    String value();
    /** Meaning of the extra no-match option, used only for Optional enum fields. */
    String noneDescription() default "None of the defined options applies";
}
