package nexus.market.domain.ports.out;

import java.util.List;
import java.util.Optional;

import nexus.market.domain.models.Seller;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SellerStatus;
import nexus.market.domain.valueobjects.UserId;

/**
 * Puerto de salida para la persistencia del perfil comercial {@link Seller}.
 * Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface SellerRepositoryPort {

    Seller save(Seller seller);

    Optional<Seller> findById(SellerId sellerId);

    Optional<Seller> findByUserId(UserId userId);

    List<Seller> findByStatus(SellerStatus status);
}