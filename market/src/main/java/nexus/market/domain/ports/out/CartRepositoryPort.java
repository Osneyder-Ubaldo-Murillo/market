package nexus.market.domain.ports.out;

import java.util.Optional;

import nexus.market.domain.models.Cart;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.CartId;

/**
 * Puerto de salida para la persistencia de {@link Cart}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface CartRepositoryPort {

    Cart save(Cart cart);

    Optional<Cart> findById(CartId cartId);

    Optional<Cart> findByBuyerId(BuyerId buyerId);
}