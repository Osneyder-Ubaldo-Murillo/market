package nexus.market.domain.ports.out;

import java.util.List;
import java.util.Optional;

import nexus.market.domain.models.Product;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductStatus;
import nexus.market.domain.valueobjects.SellerId;

/**
 * Puerto de salida para la persistencia de {@link Product} y consultas del
 * catálogo. Implementado por {@code adapters.out.persistence.mysql} (JPA).
 */
public interface ProductRepositoryPort {

    Product save(Product product);

    Optional<Product> findById(ProductId productId);

    List<Product> findBySellerId(SellerId sellerId);

    List<Product> findByStatus(ProductStatus status);

    List<Product> findPublished();

    /**
     * Una página del catálogo publicado, ordenada por nombre e id (orden estable). {@code nameContains}, si no es nulo,
     * filtra por texto contenido en el nombre sin distinguir mayúsculas.
     */
    List<Product> findPublished(String nameContains, int offset, int limit);

    long countPublished(String nameContains);
}