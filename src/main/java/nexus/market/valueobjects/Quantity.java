package com.nexusmarket.domain.valueobjects;

import java.util.Objects;

/**
 * Value Object inmutable que representa una cantidad entera no negativa.
 * Usado para existencias, reservas y cantidades de ítems.
 */
public final class Quantity {

    public static final Quantity ZERO = new Quantity(0);

    private final int value;

    private Quantity(int value) {
        this.value = value;
    }

    public static Quantity of(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("La cantidad no puede ser negativa");
        }
        return new Quantity(value);
    }

    public Quantity add(Quantity other) {
        Objects.requireNonNull(other, "other es obligatorio");
        return new Quantity(Math.addExact(value, other.value));
    }

    public Quantity subtract(Quantity other) {
        Objects.requireNonNull(other, "other es obligatorio");
        if (other.value > value) {
            throw new IllegalArgumentException("La cantidad resultante no puede ser negativa");
        }
        return new Quantity(value - other.value);
    }

    public boolean isZero() {
        return value == 0;
    }

    public boolean isGreaterThan(Quantity other) {
        Objects.requireNonNull(other, "other es obligatorio");
        return value > other.value;
    }

    public boolean isGreaterThanOrEqual(Quantity other) {
        Objects.requireNonNull(other, "other es obligatorio");
        return value >= other.value;
    }

    public boolean isLessThan(Quantity other) {
        Objects.requireNonNull(other, "other es obligatorio");
        return value < other.value;
    }

    public boolean isLessThanOrEqual(Quantity other) {
        Objects.requireNonNull(other, "other es obligatorio");
        return value <= other.value;
    }

    public int value() {
        return value;
    }

    public int getValue() {
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
        Quantity quantity = (Quantity) o;
        return value == quantity.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}