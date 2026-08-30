package nexus.market.domain.valueobjects;

import java.util.Objects;

/**
 * Value Object inmutable que representa la razón social de un {@code Seller}.
 * Longitud mínima de 3 caracteres.
 */
public final class BusinessName {

    private final String value;

    private BusinessName(String value) {
        this.value = value;
    }

    public static BusinessName of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("La razón social no puede ser nula ni vacía");
        }
        String trimmed = value.trim();
        if (trimmed.length() < 3) {
            throw new IllegalArgumentException("La razón social debe tener al menos 3 caracteres");
        }
        if (trimmed.length() > 200) {
            throw new IllegalArgumentException("La razón social no puede superar los 200 caracteres");
        }
        return new BusinessName(trimmed);
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
        BusinessName that = (BusinessName) o;
        return value.equals(that.value);
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