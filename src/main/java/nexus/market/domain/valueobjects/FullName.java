package nexus.market.domain.valueobjects;

import java.util.Objects;

/**
 * Value Object inmutable que representa el nombre completo de una persona.
 * Longitud entre 2 y 100 caracteres.
 */
public final class FullName {

    private final String value;

    private FullName(String value) {
        this.value = value;
    }

    public static FullName of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El nombre completo no puede ser nulo ni vacío");
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.length() > 100) {
            throw new IllegalArgumentException("El nombre completo debe tener entre 2 y 100 caracteres");
        }
        return new FullName(trimmed);
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
        FullName fullName = (FullName) o;
        return value.equals(fullName.value);
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