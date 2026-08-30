package com.nexusmarket.domain.valueobjects;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value Object inmutable que representa un correo electrónico con validación
 * de formato. Se normaliza a minúsculas y sin espacios para garantizar la
 * unicidad del {@code User}.
 */
public final class Email {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final String value;

    private Email(String value) {
        this.value = value;
    }

    public static Email of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El correo electrónico no puede ser nulo ni vacío");
        }
        String normalized = value.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Formato de correo electrónico inválido: " + value);
        }
        return new Email(normalized);
    }

    public String value() {
        return value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Email email = (Email) o;
        return value.equals(email.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}