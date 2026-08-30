package com.nexusmarket.domain.valueobjects;

/**
 * Identificador de pedido. Basado en UUID; se genera automáticamente.
 */
public final class OrderId extends AbstractIdentifier {

    private OrderId(String value) {
        super(value);
    }

    private OrderId() {
        super();
    }

    public static OrderId of(String value) {
        return new OrderId(value);
    }

    public static OrderId generate() {
        return new OrderId();
    }
}