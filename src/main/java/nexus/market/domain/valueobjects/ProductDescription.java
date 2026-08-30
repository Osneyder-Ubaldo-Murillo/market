package nexus.market.domain.valueobjects;

import java.util.Objects;

/**
 * Value Object inmutable que representa la descripción de un {@code Product}.
 * Longitud entre 10 y 500 caracteres.
 */
public final class ProductDescription {

    private final String value;

    private ProductDescription(String value) {
        this.value = value;
    }

    public static ProductDescription of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("La descripción del producto no puede ser nula ni vacía");
        }
        String trimmed = value.trim();
        if (trimmed.length() < 10 || trimmed.length() > 500) {
            throw new IllegalArgumentException("La descripción del producto debe tener entre 10 y 500 caracteres");
        }
        return new ProductDescription(trimmed);
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
        ProductDescription that = (ProductDescription) o;
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