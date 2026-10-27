package nexus.market.adapters.in.rest.requests;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * El valor, codificado en UTF-8, no puede superar {@link #value()} bytes. Se usa para la
 * contraseña: BCrypt solo procesa los primeros 72 bytes y {@code @Size} cuenta caracteres,
 * no bytes (una "ñ" ocupa 2). Rechaza; nunca trunca.
 */
@Documented
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
@Target({ FIELD, PARAMETER, RECORD_COMPONENT })
@Retention(RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "no puede superar {value} bytes en UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MaxUtf8Bytes, String> {

        private int max;

        @Override
        public void initialize(MaxUtf8Bytes annotation) {
            this.max = annotation.value();
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
        }
    }
}
