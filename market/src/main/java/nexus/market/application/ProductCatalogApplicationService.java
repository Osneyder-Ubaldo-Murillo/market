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
                                 ProductType type, Money price) {
        return delegate.createProduct(sellerId, name, description, type, price);
    }

    @Override
    public void publishProduct(ProductId productId) {
        delegate.publishProduct(productId);
    }

    @Override
    public void unpublishProduct(ProductId productId) {
        delegate.unpublishProduct(productId);
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
}
