package nexus.market.domain.ports.out;

import java.util.Optional;

import nexus.market.domain.models.Return;
import nexus.market.domain.valueobjects.OrderId;
import nexus.market.domain.valueobjects.ReturnId;

/**
 * Puerto de salida para la persistencia de {@link Return}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface ReturnRepositoryPort {

    Return save(Return aReturn);

    Optional<Return> findById(ReturnId returnId);

    Optional<Return> findByOrderId(OrderId orderId);
}