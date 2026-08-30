package com.nexusmarket.domain.valueobjects;

/**
 * Identificador de comprador. Basado en UUID; se genera automáticamente.
 */
public final class BuyerId extends AbstractIdentifier {

    private BuyerId(String value) {
        super(value);
    }

    private BuyerId() {
        super();
    }

    public static BuyerId of(String value) {
        return new BuyerId(value);
    }

    public static BuyerId generate() {
        return new BuyerId();
    }
}