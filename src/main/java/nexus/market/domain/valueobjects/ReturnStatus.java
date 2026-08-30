package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado de la devolución ({@code Return}).
 */
public final class ReturnStatus extends DomainCatalog {

    public static final ReturnStatus REQUESTED = new ReturnStatus("REQUESTED", "Solicitado", "Devolución iniciada");
    public static final ReturnStatus APPROVED = new ReturnStatus("APPROVED", "Aprobado", "Aceptada por el vendedor");
    public static final ReturnStatus REJECTED = new ReturnStatus("REJECTED", "Rechazado", "Denegada");
    public static final ReturnStatus PROCESSED = new ReturnStatus("PROCESSED", "Procesado", "Completada");

    private ReturnStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static ReturnStatus requested() {
        return REQUESTED;
    }

    public static ReturnStatus approved() {
        return APPROVED;
    }

    public static ReturnStatus rejected() {
        return REJECTED;
    }

    public static ReturnStatus processed() {
        return PROCESSED;
    }
}