package nexus.market.domain.enums;

/** Tipo de novedad logística reportada sobre un pedido. */
public enum IncidentType {
    PICKUP_DELAY,
    MISSING_PRODUCT,
    INCOMPLETE_QUANTITY,
    DAMAGED_PRODUCT,
    SELLER_WAREHOUSE_ISSUE,
    TRANSPORT_ISSUE,
    DELIVERY_DIFFICULTY,
    OTHER
}
