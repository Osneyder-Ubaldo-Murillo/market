package com.nexusmarket.domain.valueobjects;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value Object inmutable que representa un documento de identidad (cédula,
 * NIT, pasaporte, etc.). Formato alfanumérico con guiones opcionales,
 * normalizado a mayúsculas. Debe ser único en la plataforma.
 */
public final class DocumentId {

    private static final Pattern DOCUMENT_PATTERN = Pattern.compile("^[A-Za-z0-9-]{4,20}$");

    private final String value;

    private DocumentId(String value) {
        this.value = value;
    }

    public static DocumentId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El documento de identidad no puede ser nulo ni vacío");
        }
        String normalized = value.trim().toUpperCase();
        if (!DOCUMENT_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Formato de documento de identidad inválido (alfanumérico, 4-20 caracteres): " + value);
        }
        return new DocumentId(normalized);
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
        DocumentId that = (DocumentId) o;
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