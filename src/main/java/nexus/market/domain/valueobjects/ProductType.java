package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: tipo del {@code Product}. Un producto digital se
 * entrega inmediatamente después del pago y no requiere inventario ni despacho.
 */
public final class ProductType extends DomainCatalog {

    public static final ProductType PHYSICAL = new ProductType("PHYSICAL", "Físico", "Requiere inventario y despacho");
    public static final ProductType DIGITAL = new ProductType("DIGITAL", "Digital", "Entrega inmediata tras pago");

    private ProductType(String code, String name, String description) {
        super(code, name, description);
    }

    public static ProductType physical() {
        return PHYSICAL;
    }

    public static ProductType digital() {
        return DIGITAL;
    }
}