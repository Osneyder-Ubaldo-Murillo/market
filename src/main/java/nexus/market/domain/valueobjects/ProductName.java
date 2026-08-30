package nexus.market.domain.valueobjects;

import java.util.Objects;

/**
 * Value Object inmutable que representa el nombre de un {@code Product}.
 * Longitud entre 3 y 100 caracteres.
 */
public final class ProductName {

    private final String value;

    private ProductName(String value) {
        this.value = value;
    }

    public static ProductName of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El nombre del producto no puede ser nulo ni vacío");
        }
        String trimmed = value.trim();
        if (trimmed.length() < 3 || trimmed.length() > 100) {
            throw new IllegalArgumentException("El nombre del producto debe tener entre 3 y 100 caracteres");
        }
        return new ProductName(trimmed);
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
        ProductName that = (ProductName) o;
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