package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado de la factura ({@code Invoice}).
 */
public final class InvoiceStatus extends DomainCatalog {

    public static final InvoiceStatus ISSUED = new InvoiceStatus("ISSUED", "Emitida", "Factura generada");
    public static final InvoiceStatus CANCELLED = new InvoiceStatus("CANCELLED", "Anulada", "Factura cancelada");

    private InvoiceStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static InvoiceStatus issued() {
        return ISSUED;
    }

    public static InvoiceStatus cancelled() {
        return CANCELLED;
    }
}