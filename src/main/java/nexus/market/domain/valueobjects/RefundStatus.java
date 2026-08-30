package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado del reembolso ({@code Refund}).
 */
public final class RefundStatus extends DomainCatalog {

    public static final RefundStatus PENDING = new RefundStatus("PENDING", "Pendiente", "Esperando procesamiento");
    public static final RefundStatus PROCESSED = new RefundStatus("PROCESSED", "Procesado", "Reembolso completado");
    public static final RefundStatus FAILED = new RefundStatus("FAILED", "Fallido", "Error en el reembolso");

    private RefundStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static RefundStatus pending() {
        return PENDING;
    }

    public static RefundStatus processed() {
        return PROCESSED;
    }

    public static RefundStatus failed() {
        return FAILED;
    }
}