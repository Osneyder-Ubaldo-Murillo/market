package nexus.market.domain.valueobjects;

/**
 * Catálogo de negocio: estado del envío ({@code Shipping}).
 */
public final class ShippingStatus extends DomainCatalog {

    public static final ShippingStatus PREPARING = new ShippingStatus("PREPARING", "Preparación", "En proceso de alistamiento");
    public static final ShippingStatus DISPATCHED = new ShippingStatus("DISPATCHED", "Despachado", "Salida de la bodega");
    public static final ShippingStatus IN_TRANSIT = new ShippingStatus("IN_TRANSIT", "En Tránsito", "En camino al destino");
    public static final ShippingStatus DELIVERED = new ShippingStatus("DELIVERED", "Entregado", "Llegada confirmada");

    private ShippingStatus(String code, String name, String description) {
        super(code, name, description);
    }

    public static ShippingStatus preparing() {
        return PREPARING;
    }

    public static ShippingStatus dispatched() {
        return DISPATCHED;
    }

    public static ShippingStatus inTransit() {
        return IN_TRANSIT;
    }

    public static ShippingStatus delivered() {
        return DELIVERED;
    }
}