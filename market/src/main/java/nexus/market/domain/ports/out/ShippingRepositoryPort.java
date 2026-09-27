package nexus.market.domain.ports.out;

import java.util.Optional;

import nexus.market.domain.models.Shipping;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ShippingId;

/**
 * Puerto de salida para la persistencia de {@link Shipping}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface ShippingRepositoryPort {

    Shipping save(Shipping shipping);

    Optional<Shipping> findById(ShippingId shippingId);

    Optional<Shipping> findByOrderId(OrderId orderId);
}