package com.nexusmarket.domain.valueobjects;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value Object inmutable que representa un NIT o RUT del {@code Seller}.
 * Debe ser único en la plataforma.
 */
public final class TaxId {

    private static final Pattern TAX_ID_PATTERN = Pattern.compile("^[0-9][0-9-]*$");

    private final String value;

    private TaxId(String value) {
        this.value = value;
    }

    public static TaxId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El NIT/RUT no puede ser nulo ni vacío");
        }
        String normalized = value.trim();
        if (!TAX_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Formato de NIT/RUT inválido (solo números y guiones): " + value);
        }
        if (normalized.length() < 6 || normalized.length() > 20) {
            throw new IllegalArgumentException("El NIT/RUT debe tener entre 6 y 20 caracteres");
        }
        return new TaxId(normalized);
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
        TaxId taxId = (TaxId) o;
        return value.equals(taxId.value);
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