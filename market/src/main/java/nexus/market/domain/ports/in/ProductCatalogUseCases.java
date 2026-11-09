package nexus.market.domain.ports.in;

import java.util.List;

import nexus.market.domain.models.Product;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SellerSku;

/**
 * Puerto de entrada (caso de uso) del catálogo de productos. Delega en
 * {@code ProductCatalogService}.
 */
public interface ProductCatalogUseCases {

    /** Crea el producto; {@code sellerSku} nulo genera un código automático. Duplicado en el vendedor: 409. */
    Product createProduct(SellerId sellerId, ProductName name, ProductDescription description,
                          ProductType type, Money price, SellerSku sellerSku);

    /** El producto del vendedor con ese código comercial. */
    Product findBySellerAndSku(SellerId sellerId, SellerSku sellerSku);

    void publishProduct(ProductId productId);

    void unpublishProduct(ProductId productId);

    /** Una página de los productos del vendedor en cualquier estado; {@code statusCode} opcional filtra por estado. */
    List<Product> findBySeller(SellerId sellerId, String statusCode, int offset, int limit);

    long countBySeller(SellerId sellerId, String statusCode);

    /** {@code ACTIVE → SUSPENDED}: retiro temporal; publicar de nuevo lo reactiva. */
    void suspendProduct(ProductId productId);

    /** Retiro definitivo (estado final {@code DISCONTINUED}). */
    void discontinueProduct(ProductId productId);

    Product addVariant(ProductId productId, String variant);

    Product removeVariant(ProductId productId, String variant);

    void updatePrice(ProductId productId, Money newPrice);

    /** Cambia el nombre y la descripción de un producto existente. */
    void updateDetails(ProductId productId, ProductName name, ProductDescription description, SellerSku sellerSku);

    Product findById(ProductId productId);

    List<Product> findPublished();

    /** Una página del catálogo publicado (ordenada por nombre e id); {@code nameContains} filtra por nombre si no es nulo. */
    List<Product> findPublished(String nameContains, int offset, int limit);

    long countPublished(String nameContains);
}
