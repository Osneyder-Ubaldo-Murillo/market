package nexus.market.domain.ports.out;

import java.util.Optional;

import nexus.market.domain.models.Buyer;
import nexus.market.domain.valueobjects.BuyerId;
import nexus.market.domain.valueobjects.UserId;

/**
 * Puerto de salida para la persistencia del perfil comercial {@link Buyer}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface BuyerRepositoryPort {

    Buyer save(Buyer buyer);

    Optional<Buyer> findById(BuyerId buyerId);

    Optional<Buyer> findByUserId(UserId userId);
}