package nexus.market.domain.ports.out;

import java.util.List;
import java.util.Optional;

import nexus.market.domain.models.Order;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.OrderId;

/**
 * Puerto de salida para la persistencia de {@link Order}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface OrderRepositoryPort {

    Order save(Order order);

    Optional<Order> findById(OrderId orderId);

    List<Order> findByBuyerId(BuyerId buyerId);
}