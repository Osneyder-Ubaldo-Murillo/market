package com.nexusmarket.domain.valueobjects;

/**
 * Identificador de bodega. Basado en UUID; se genera automáticamente.
 */
public final class WarehouseId extends AbstractIdentifier {

    private WarehouseId(String value) {
        super(value);
    }

    private WarehouseId() {
        super();
    }

    public static WarehouseId of(String value) {
        return new WarehouseId(value);
    }

    public static WarehouseId generate() {
        return new WarehouseId();
    }
}