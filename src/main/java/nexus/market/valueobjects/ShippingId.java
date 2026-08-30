package com.nexusmarket.domain.valueobjects;

/**
 * Identificador de envío. Basado en UUID; se genera automáticamente.
 */
public final class ShippingId extends AbstractIdentifier {

    private ShippingId(String value) {
        super(value);
    }

    private ShippingId() {
        super();
    }

    public static ShippingId of(String value) {
        return new ShippingId(value);
    }

    public static ShippingId generate() {
        return new ShippingId();
    }
}