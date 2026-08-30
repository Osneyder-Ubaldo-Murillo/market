package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado del {@code Inventory} por producto y bodega.
 */
public final class InventoryStatus extends DomainCatalog {

    public static final InventoryStatus ACTIVE = new InventoryStatus("ACTIVE", "Activo", "Disponible para venta");
    public static final InventoryStatus OUT_OF_STOCK = new InventoryStatus("OUT_OF_STOCK", "Sin Stock", "Agotado");
    public static final InventoryStatus DAMAGED = new InventoryStatus("DAMAGED", "Dañado", "No disponible para venta");

    private InventoryStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static InventoryStatus active() {
        return ACTIVE;
    }

    public static InventoryStatus outOfStock() {
        return OUT_OF_STOCK;
    }

    public static InventoryStatus damaged() {
        return DAMAGED;
    }
}