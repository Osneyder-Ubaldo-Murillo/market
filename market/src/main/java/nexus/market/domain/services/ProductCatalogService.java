package nexus.market.domain.services;

import java.util.List;
import java.util.Objects;

import nexus.market.domain.enums.AuditSeverity;
import nexus.market.domain.exceptions.BusinessException;
import nexus.market.domain.models.Product;
import nexus.market.domain.ports.out.InventoryRepositoryPort;
import nexus.market.domain.ports.out.ProductRepositoryPort;
import nexus.market.domain.specifications.ProductPublishableSpecification;
import nexus.market.domain.valueobjects.Money;
import nexus.market.domain.valueobjects.OperationType;
import nexus.market.domain.valueobjects.ProductDescription;
import nexus.market.domain.valueobjects.ProductId;
import nexus.market.domain.valueobjects.ProductName;
import nexus.market.domain.valueobjects.ProductStatus;
import nexus.market.domain.valueobjects.ProductType;
import nexus.market.domain.valueobjects.SellerId;

/**
 * Servicio de dominio del catálogo de productos (SDD - Domain Services:
 * {@code ProductCatalogService}): creación, publicación, despublicación y
 * cambio de precio.
 */
public class ProductCatalogService {

    private final ProductRepositoryPort products;
    private final InventoryRepositoryPort inventories;
    private final ProductPublishableSpecification publishable;
    private final AuditService audit;

    public ProductCatalogService(ProductRepositoryPort products,
                                 InventoryRepositoryPort inventories,
                                 ProductPublishableSpecification publishable,
                                 AuditService audit) {
        this.products = Objects.requireNonNull(products, "products es obligatorio");
        this.inventories = Objects.requireNonNull(inventories, "inventories es obligatorio");
        this.publishable = Objects.requireNonNull(publishable, "publishable es obligatorio");
        this.audit = Objects.requireNonNull(audit, "audit es obligatorio");
    }

    /** Crea el producto en estado {@code INACTIVE} (sin publicar). */
    public Product createProduct(SellerId sellerId, ProductName name,
                                 ProductDescription description, ProductType type, Money price) {
        Product product = products.save(
                Product.create(sellerId, name, description, type, price));
        audit.record(OperationType.PRODUCT_CREATION, AuditSeverity.INFO,
                sellerId.toString(), "Producto creado: " + product.getProductId());
        return product;
    }

    /**
     * Publica el producto. Los productos físicos deben cumplir
     * {@code ProductPublishableSpecification} (inventario activo con stock);
     * los digitales siempre son publicables.
     */
    public void publishProduct(ProductId productId) {
        Product product = loadProduct(productId);
        if (!publishable.isSatisfiedBy(product)) {
            throw new BusinessException("NOT_PUBLISHABLE",
                    "Producto físico sin inventario disponible; no puede publicarse.");
        }
        product.publish();
        products.save(product);
        audit.record(OperationType.PRODUCT_PUBLISH, AuditSeverity.INFO,
                product.getSellerId().toString(), "Producto publicado: " + productId);
    }

    /** Oculta el producto ({@code ACTIVE → INACTIVE}). */
    public void unpublishProduct(ProductId productId) {
        Product product = loadProduct(productId);
        product.unpublish();
        products.save(product);
        audit.record(OperationType.PRODUCT_UNPUBLISH, AuditSeverity.INFO,
                product.getSellerId().toString(), "Producto despublicado: " + productId);
    }

    /** Actualiza el precio del producto. */
    public void updatePrice(ProductId productId, Money newPrice) {
        Product product = loadProduct(productId);
        product.updatePrice(newPrice);
        products.save(product);
        audit.record(OperationType.PRODUCT_UPDATE, AuditSeverity.INFO,
                product.getSellerId().toString(), "Precio actualizado: " + productId);
    }

    /** Consulta el catálogo visible (publicado y con stock). */
    public List<Product> findPublished() {
        return products.findPublished();
    }

    public Product findById(ProductId productId) {
        return loadProduct(productId);
    }

    public boolean isPublished(ProductId productId) {
        return loadProduct(productId).getStatus() == ProductStatus.ACTIVE;
    }

    private Product loadProduct(ProductId productId) {
        Objects.requireNonNull(productId, "productId es obligatorio");
        return products.findById(productId)
                .orElseThrow(() -> new BusinessException("PRODUCT_NOT_FOUND", "Producto inexistente."));
    }
}