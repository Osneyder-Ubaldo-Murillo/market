package com.nexusmarket.domain.valueobjects;

/**
 * Identificador de reembolso. Basado en UUID; se genera automáticamente.
 */
public final class RefundId extends AbstractIdentifier {

    private RefundId(String value) {
        super(value);
    }

    private RefundId() {
        super();
    }

    public static RefundId of(String value) {
        return new RefundId(value);
    }

    public static RefundId generate() {
        return new RefundId();
    }
}