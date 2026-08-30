package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado comercial del {@code Buyer}.
 */
public final class CommercialStatus extends DomainCatalog {

    public static final CommercialStatus ACTIVE = new CommercialStatus("ACTIVE", "Activo", "Comprador habilitado para comprar");
    public static final CommercialStatus BLOCKED = new CommercialStatus("BLOCKED", "Bloqueado", "Comprador suspendido");
    public static final CommercialStatus INACTIVE = new CommercialStatus("INACTIVE", "Inactivo", "Comprador sin actividad");

    private CommercialStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static CommercialStatus active() {
        return ACTIVE;
    }

    public static CommercialStatus blocked() {
        return BLOCKED;
    }

    public static CommercialStatus inactive() {
        return INACTIVE;
    }
}