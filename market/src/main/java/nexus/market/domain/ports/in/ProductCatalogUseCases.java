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

    Product findById(ProductId productId);

    List<Product> findPublished();
}
