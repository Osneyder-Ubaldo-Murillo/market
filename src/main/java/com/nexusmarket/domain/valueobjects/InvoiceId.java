package com.nexusmarket.domain.valueobjects;

/**
 * Identificador de factura. Basado en UUID; se genera automáticamente.
 */
public final class InvoiceId extends AbstractIdentifier {

    private InvoiceId(String value) {
        super(value);
    }

    private InvoiceId() {
        super();
    }

    public static InvoiceId of(String value) {
        return new InvoiceId(value);
    }

    public static InvoiceId generate() {
        return new InvoiceId();
    }
}