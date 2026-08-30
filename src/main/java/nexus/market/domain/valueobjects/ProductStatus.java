package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado de visibilidad del {@code Product}.
 */
public final class ProductStatus extends DomainCatalog {

    public static final ProductStatus ACTIVE = new ProductStatus("ACTIVE", "Activo", "Producto visible en catálogo");
    public static final ProductStatus INACTIVE = new ProductStatus("INACTIVE", "Inactivo", "Producto oculto");
    public static final ProductStatus OUT_OF_STOCK = new ProductStatus("OUT_OF_STOCK", "Sin Stock", "Producto agotado");

    private ProductStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static ProductStatus active() {
        return ACTIVE;
    }

    public static ProductStatus inactive() {
        return INACTIVE;
    }

    public static ProductStatus outOfStock() {
        return OUT_OF_STOCK;
    }
}