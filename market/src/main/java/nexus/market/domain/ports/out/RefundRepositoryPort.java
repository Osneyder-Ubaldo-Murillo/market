package nexus.market.domain.ports.out;

import java.util.List;
import java.util.Optional;

import nexus.market.domain.models.Refund;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.RefundId;

/**
 * Puerto de salida para la persistencia de {@link Refund}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface RefundRepositoryPort {

    Refund save(Refund refund);

    Optional<Refund> findById(RefundId refundId);

    List<Refund> findByOrderId(OrderId orderId);
}