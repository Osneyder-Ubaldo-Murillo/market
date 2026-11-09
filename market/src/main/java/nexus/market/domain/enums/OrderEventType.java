package nexus.market.domain.enums;

/** Hecho registrado en el historial cronológico de un pedido. */
public enum OrderEventType {
    OPERATOR_ASSIGNED,
    PICKUP_REGISTERED,
    INCIDENT_REPORTED,
    INCIDENT_INSTRUCTION,
    INCIDENT_RESPONSIBLE_ASSIGNED,
    INCIDENT_RESOLVED,
    SHIPPING_CREATED,
    DISPATCHED,
    IN_TRANSIT,
    DELIVERED
}
