package nexus.market.application;

import java.util.List;
import java.util.Objects;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import nexus.market.domain.ports.in.ProductCatalogUseCases;
import nexus.market.domain.models.Product;
import nexus.market.domain.services.ProductCatalogService;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.SellerId;
import nexus.market.domain.valueobjects.SellerSku;

/**
 * Servicio de aplicación del catálogo de productos: delega en
 * {@code ProductCatalogService}.
 */
@Service
@Profile("jpa")
public class ProductCatalogApplicationService implements ProductCatalogUseCases {

    private final ProductCatalogService delegate;

    public ProductCatalogApplicationService(ProductCatalogService delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate es obligatorio");
    }

    @Override
    public Product createProduct(SellerId sellerId, ProductName name, ProductDescription description,
                                 ProductType type, Money price, SellerSku sellerSku) {
        return delegate.createProduct(sellerId, name, description, type, price, sellerSku);
    }

    @Override
    public Product findBySellerAndSku(SellerId sellerId, SellerSku sellerSku) {
        return delegate.findBySellerAndSku(sellerId, sellerSku);
    }

    @Override
    public void publishProduct(ProductId productId) {
        delegate.publishProduct(productId);
    }

    @Override
    public List<Product> findBySeller(SellerId sellerId, String statusCode, int offset, int limit) {
        return delegate.findBySeller(sellerId, statusCode, offset, limit);
    }

    @Override
    public long countBySeller(SellerId sellerId, String statusCode) {
        return delegate.countBySeller(sellerId, statusCode);
    }

    @Override
    public void suspendProduct(ProductId productId) {
        delegate.suspendProduct(productId);
    }

    @Override
    public void discontinueProduct(ProductId productId) {
        delegate.discontinueProduct(productId);
    }

    @Override
    public Product addVariant(ProductId productId, String variant) {
        return delegate.addVariant(productId, variant);
    }

    @Override
    public Product removeVariant(ProductId productId, String variant) {
        return delegate.removeVariant(productId, variant);
    }

    @Override
    public void unpublishProduct(ProductId productId) {
        delegate.unpublishProduct(productId);
    }

    @Override
    public void updateDetails(ProductId productId, ProductName name, ProductDescription description,
                              SellerSku sellerSku) {
        delegate.updateDetails(productId, name, description, sellerSku);
    }

    @Override
    public void updatePrice(ProductId productId, Money newPrice) {
        delegate.updatePrice(productId, newPrice);
    }

    @Override
    public Product findById(ProductId productId) {
        return delegate.findById(productId);
    }

    @Override
    public List<Product> findPublished() {
        return delegate.findPublished();
    }

    @Override
    public List<Product> findPublished(String nameContains, int offset, int limit) {
        return delegate.findPublished(nameContains, offset, limit);
    }

    @Override
    public long countPublished(String nameContains) {
        return delegate.countPublished(nameContains);
    }
}
