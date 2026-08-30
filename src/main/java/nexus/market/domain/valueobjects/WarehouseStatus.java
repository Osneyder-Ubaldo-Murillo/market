package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado de la bodega ({@code Warehouse}).
 */
public final class WarehouseStatus extends DomainCatalog {

    public static final WarehouseStatus ACTIVE = new WarehouseStatus("ACTIVE", "Activa", "Bodega operativa");
    public static final WarehouseStatus INACTIVE = new WarehouseStatus("INACTIVE", "Inactiva", "Bodega fuera de servicio");

    private WarehouseStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static WarehouseStatus active() {
        return ACTIVE;
    }

    public static WarehouseStatus inactive() {
        return INACTIVE;
    }
}