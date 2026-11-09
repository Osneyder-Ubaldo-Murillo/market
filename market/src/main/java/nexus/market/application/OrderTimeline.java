package nexus.market.application;

import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import nexus.market.domain.enums.OrderEventType;
import nexus.market.domain.services.LogisticsService;
import nexus.market.domain.valueobjects.OrderId;

/**
 * Anota en el historial del pedido los pasos del envío (despacho, tránsito, entrega) con el usuario autenticado y su rol. Se usa
 * dentro de la transacción del caso de uso: si el paso falla, tampoco queda la anotación.
 */
@Component
@Profile("jpa")
public class OrderTimeline {

    private final LogisticsService logistics;
    private final AccessPolicy policy;

    public OrderTimeline(LogisticsService logistics, AccessPolicy policy) {
        this.logistics = Objects.requireNonNull(logistics, "logistics es obligatorio");
        this.policy = Objects.requireNonNull(policy, "policy es obligatorio");
    }

    public void record(OrderId orderId, OrderEventType type, String detail) {
        logistics.record(orderId, null, type, policy.currentUserId().orElse(null), policy.currentRoleCode(), detail);
    }
}
