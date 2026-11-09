package nexus.market.domain.models;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import nexus.market.domain.enums.OrderEventType;
import nexus.market.domain.valueobjects.OrderId;

/**
 * Entrada inmutable del historial cronológico de un pedido: qué pasó, quién lo hizo y cuándo. Nunca se modifica ni se borra;
 * resolver una novedad agrega una entrada, no reescribe las anteriores. {@code detail} no guarda datos sensibles.
 */
public record OrderEvent(String eventId, OrderId orderId, String incidentId, OrderEventType type, String actorId,
                         String actorRole, String detail, LocalDateTime createdAt) {

    public OrderEvent {
        Objects.requireNonNull(eventId, "eventId es obligatorio");
        Objects.requireNonNull(orderId, "orderId es obligatorio");
        Objects.requireNonNull(type, "type es obligatorio");
        Objects.requireNonNull(createdAt, "createdAt es obligatorio");
    }

    public static OrderEvent of(OrderId orderId, String incidentId, OrderEventType type, String actorId,
                                String actorRole, String detail) {
        return new OrderEvent(UUID.randomUUID().toString(), orderId, incidentId, type, actorId, actorRole,
                detail, LocalDateTime.now());
    }
}
