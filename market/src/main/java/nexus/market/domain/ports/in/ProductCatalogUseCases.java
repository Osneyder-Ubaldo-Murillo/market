package nexus.market.domain.ports.in;

import java.util.List;

import nexus.market.domain.models.Product;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.SellerId;

/**
 * Puerto de entrada (caso de uso) del catálogo de productos. Delega en
 * {@code ProductCatalogService}.
 */
public interface ProductCatalogUseCases {

    Product createProduct(SellerId sellerId, ProductName name, ProductDescription description,
                          ProductType type, Money price);

    void publishProduct(ProductId productId);

    void unpublishProduct(ProductId productId);

    void updatePrice(ProductId productId, Money newPrice);

    /** Cambia el nombre y la descripción de un producto existente. */
    void updateDetails(ProductId productId, ProductName name, ProductDescription description);

    Product findById(ProductId productId);

    List<Product> findPublished();

    /** Una página del catálogo publicado (ordenada por nombre e id); {@code nameContains} filtra por nombre si no es nulo. */
    List<Product> findPublished(String nameContains, int offset, int limit);

    long countPublished(String nameContains);
}
