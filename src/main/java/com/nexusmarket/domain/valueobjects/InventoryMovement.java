package com.nexusmarket.domain.valueobjects;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.nexusmarket.domain.enums.InventoryMovementType;

/**
 * Value Object inmutable que registra un cambio de existencias en un
 * {@code Inventory}. Todo movimiento queda en el historial del inventario.
 */
public final class InventoryMovement {

    private final InventoryMovementType type;
    private final Quantity quantity;
    private final LocalDateTime date;
    private final Map<String, Object> metadata;

    private InventoryMovement(InventoryMovementType type, Quantity quantity,
                              LocalDateTime date, Map<String, Object> metadata) {
        this.type = type;
        this.quantity = quantity;
        this.date = date;
        this.metadata = metadata;
    }

    public static InventoryMovement of(InventoryMovementType type, Quantity quantity,
                                       LocalDateTime date, Map<String, Object> metadata) {
        if (type == null) {
            throw new IllegalArgumentException("type es obligatorio");
        }
        if (quantity == null) {
            throw new IllegalArgumentException("quantity es obligatorio");
        }
        if (date == null) {
            throw new IllegalArgumentException("date es obligatorio");
        }
        Map<String, Object> metadataCopy = Collections.unmodifiableMap(
                metadata == null ? new HashMap<>() : new HashMap<>(metadata));
        return new InventoryMovement(type, quantity, date, metadataCopy);
    }

    public InventoryMovementType getType() {
        return type;
    }

    public Quantity getQuantity() {
        return quantity;
    }

    public LocalDateTime getDate() {
        return date;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        InventoryMovement that = (InventoryMovement) o;
        return type == that.type
                && quantity.equals(that.quantity)
                && date.equals(that.date)
                && metadata.equals(that.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, quantity, date, metadata);
    }

    @Override
    public String toString() {
        return type + " " + quantity + " @" + date;
    }
}